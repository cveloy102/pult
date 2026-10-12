using System.Security.Cryptography;

namespace Pult.Core;

/// <summary>Serializes state changes, timer execution, pairing and request deduplication.</summary>
public sealed class AgentService
{
    private readonly object gate = new();
    private readonly IStateStore store;
    private readonly IComputer computer;
    private readonly TimeProvider time;
    private string? pairingCode;
    private DateTimeOffset pairingExpires;
    private readonly Dictionary<string, (DateTimeOffset Start, int Count)> attempts = new();
    private DateTimeOffset lastTick;
    public AgentState State { get; }
    public string? LastError { get; private set; }

    public AgentService(IStateStore store, IComputer computer, TimeProvider? time = null)
    {
        this.store = store; this.computer = computer; this.time = time ?? TimeProvider.System;
        State = store.Load(); lastTick = this.time.GetUtcNow();
        // A persisted command may have run before an interrupted process could acknowledge it.
        if (State.Timer is { } job && job.DueAt <= lastTick)
        {
            LogTimer(job, "skipped");
            State.Timer = null; LastError = "Пропущенный таймер отменён после запуска агента.";
        }
        foreach (var receipt in State.Receipts.Where(x => x.Status == "pending").ToArray())
            ReplaceReceipt(receipt with { Status = "unknown" });
        store.Save(State);
    }

    public (string Code, DateTimeOffset Expires) BeginPairing()
    {
        lock (gate)
        {
            pairingCode = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
            pairingExpires = time.GetUtcNow().AddMinutes(2);
            return (pairingCode, pairingExpires);
        }
    }

    public PairResult Pair(PairRequest request, string address, AgentInfo info)
    {
        lock (gate)
        {
            var now = time.GetUtcNow();
            foreach (var key in attempts.Where(x => now - x.Value.Start > TimeSpan.FromMinutes(2)).Select(x => x.Key).ToArray()) attempts.Remove(key);
            // Include a global limit to prevent bypass via several source addresses.
            foreach (var key in new[] { address, "*" })
            {
                var attempt = attempts.GetValueOrDefault(key, (Start: now, Count: 0));
                attempts[key] = (attempt.Start, attempt.Count + 1);
                if (attempt.Count >= (key == "*" ? 20 : 5)) throw new RequestException(429, "Слишком много попыток. Подожди две минуты.");
            }
            if (string.IsNullOrWhiteSpace(request.Code) || pairingCode is null || now >= pairingExpires || !Protocol.Equal(pairingCode, request.Code))
                throw new RequestException(401, "Код неверный или истёк. Создай новый код на ПК.");
            if (string.IsNullOrWhiteSpace(request.Name) || request.Name.Length > 80) throw new RequestException(400, "Укажи название телефона до 80 символов.");
            if (State.Phones.Count >= 20) throw new RequestException(409, "Сначала удали один из привязанных телефонов.");
            var token = Protocol.Token();
            var phone = new Phone(Guid.NewGuid().ToString("N"), request.Name.Trim(), Protocol.Hash(token), now);
            State.Phones.Add(phone);
            try { store.Save(State); } catch { State.Phones.Remove(phone); throw; }
            pairingCode = null;
            return new(token, phone.Id, info);
        }
    }

    public Phone? Authenticate(string token)
    {
        if (token.Length != 64) return null;
        var hash = Protocol.Hash(token);
        lock (gate) return State.Phones.FirstOrDefault(x => Protocol.Equal(x.TokenHash, hash));
    }

    public Phone[] Phones { get { lock (gate) return State.Phones.ToArray(); } }
    public void Revoke(string id) { lock (gate) { State.Phones.RemoveAll(x => x.Id == id); store.Save(State); } }
    public TimerEvent? LastTimerEvent { get { lock(gate) return State.TimerEvents.LastOrDefault(); } }
    public TimerEvent[] TimerHistory { get { lock(gate) return State.TimerEvents.AsEnumerable().Reverse().ToArray(); } }
    public void CancelTimerLocal() { lock (gate) { if(State.Timer is {} timer)LogTimer(timer, "cancelled"); State.Timer = null; store.Save(State); LastError = null; } }
    private void LogTimer(TimerJob job, string action)
    {
        State.TimerEvents.Add(new(time.GetUtcNow(), job.Id, job.Action, action));
        if(State.TimerEvents.Count > 30)State.TimerEvents.RemoveRange(0, State.TimerEvents.Count - 30);
    }
    public void SaveSettings(string name, string theme, bool updates)
    {
        lock (gate)
        {
            State.Name = string.IsNullOrWhiteSpace(name) ? Environment.MachineName : name.Trim()[..Math.Min(name.Trim().Length, 80)];
            State.Theme = theme is "signal" or "quiet" or "orbit" ? theme : "signal";
            State.CheckUpdates = updates; store.Save(State);
        }
    }

    public AgentStatus Status(string version)
    {
        lock (gate)
        {
            AudioState? audio = null; string? error = LastError;
            try { audio = computer.GetAudio(); } catch { error ??= "Аудиоустройство сейчас недоступно."; }
            return new(State.Name, version, Protocol.Version, Protocol.Capabilities, State.Timer, audio, error);
        }
    }

    public CommandResult Execute(Phone phone, Command request)
    {
        lock (gate)
        {
            if (!State.Phones.Any(x => x.Id == phone.Id)) throw new RequestException(401, "Доступ телефона отозван.");
            if (!Guid.TryParse(request.RequestId, out _)) throw new RequestException(400, "Нужен идентификатор запроса UUID.");
            Validate(request);
            var hash = Protocol.Hash(System.Text.Json.JsonSerializer.Serialize(request with { RequestId = "" }, Protocol.Json));
            var previous = State.Receipts.FirstOrDefault(x => x.Id == request.RequestId && x.PhoneId == phone.Id);
            if (previous is not null)
            {
                if (previous.PayloadHash != hash) throw new RequestException(409, "Идентификатор уже использован для другой команды.");
                if (previous.Status != "done") throw new RequestException(409, "Результат предыдущей попытки неизвестен. Проверь состояние ПК; команда не повторена.");
                return new(true, true, "Команда уже принята.");
            }
            if (request.Action == "timer" && State.Timer is not null && !request.Replace)
                throw new RequestException(409, "Таймер уже запущен. Подтверди его замену.");
            var receipt = new Receipt(request.RequestId, phone.Id, hash, time.GetUtcNow(), "pending");
            State.Receipts.Add(receipt);
            if (State.Receipts.Count > 1000) State.Receipts.RemoveRange(0, State.Receipts.Count - 1000);
            // Persist intent before invoking the OS; a crash must never cause a second shutdown/toggle.
            store.Save(State);
            try
            {
                if (request.Action == "timer")
                {
                    if(State.Timer is {} previousTimer) LogTimer(previousTimer, "replaced");
                    State.Timer = new(Guid.NewGuid().ToString("N"), request.TimerAction!, time.GetUtcNow().AddSeconds(request.DurationSeconds!.Value));
                    LogTimer(State.Timer, "scheduled");
                }
                else if (request.Action == "cancelTimer") { if(State.Timer is {} cancelled)LogTimer(cancelled, "cancelled"); State.Timer = null; }
                else computer.Execute(request.Action, request.Value);
                ReplaceReceipt(receipt with { Status = "done" }); store.Save(State); LastError = null;
                return new(true, false, "Команда принята.");
            }
            catch
            {
                ReplaceReceipt(receipt with { Status = "unknown" });
                try { store.Save(State); } catch { /* Preserve original failure. */ }
                throw;
            }
        }
    }

    private static void Validate(Command request)
    {
        if (request.Action != "timer" && (request.TimerAction is not null || request.DurationSeconds is not null || request.Replace))
            throw new RequestException(400, "Параметры таймера допустимы только для команды timer.");
        switch (request.Action)
        {
            case "shutdown": case "restart": case "lock": case "playPause": case "cancelTimer":
                if (request.Value is not null) throw new RequestException(400, "Эта команда не принимает значение."); break;
            case "volume":
                if (request.Value is not >= 0 or not <= 100) throw new RequestException(400, "Громкость должна быть от 0 до 100."); break;
            case "volumeStep":
                if (request.Value is not (-5 or 5)) throw new RequestException(400, "Шаг громкости: −5 или +5."); break;
            case "timer":
                if (request.Value is not null || request.DurationSeconds is not >= 1 or not <= 86400 || request.TimerAction is not ("shutdown" or "restart" or "lock"))
                    throw new RequestException(400, "Таймер: от 1 секунды до 24 часов.");
                break;
            default: throw new RequestException(400, "Неизвестная команда.");
        }
    }

    public void Tick()
    {
        lock (gate)
        {
            var now = time.GetUtcNow(); var gap = now - lastTick; lastTick = now;
            if (State.Timer is not { } job || job.DueAt > now) return;
            State.Timer = null;
            if (gap > TimeSpan.FromSeconds(30)) { LogTimer(job, "skipped"); store.Save(State); LastError = "Таймер пропущен во время сна или остановки агента и отменён."; return; }
            LogTimer(job, "dispatched"); store.Save(State);
            try { computer.Execute(job.Action, null); LastError = null; }
            catch (Exception ex) { LogTimer(job, "failed"); store.Save(State); LastError = $"Не удалось выполнить таймер: {ex.Message}"; }
        }
    }
    private void ReplaceReceipt(Receipt receipt)
    {
        var index = State.Receipts.FindIndex(x => x.Id == receipt.Id && x.PhoneId == receipt.PhoneId);
        if (index >= 0) State.Receipts[index] = receipt;
    }
}
