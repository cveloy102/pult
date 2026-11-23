using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Pult.Core;

public static class Protocol
{
    public const int Version = 1;
    public const int Port = 47591;
    public const int DiscoveryPort = 47592;
    public static readonly string[] Capabilities = ["shutdown", "restart", "lock", "timer", "volume", "playPause", "audioOutput", "wakeOnLan"];
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    public static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    public static string Token() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
    public static bool Equal(string a, string b) => CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
}

public sealed record Phone(string Id, string Name, string TokenHash, DateTimeOffset PairedAt);
public sealed record TimerJob(string Id, string Action, DateTimeOffset DueAt);
public sealed record TimerEvent(DateTimeOffset Time, string TimerId, string Action, string Event);
public sealed record Receipt(string Id, string PhoneId, string PayloadHash, DateTimeOffset CreatedAt, string Status);
public sealed record Command(string RequestId, string Action, int? Value = null, bool Replace = false, string? TimerAction = null, int? DurationSeconds = null, [property: System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)] string? DeviceId = null);
public sealed record AudioState(int Volume, bool Muted);
public sealed record AudioOutput(string Id, string Name, bool Selected);
public sealed record AgentInfo(string Name, string Version, int Protocol, string[] Capabilities, string Fingerprint);
public sealed record AgentStatus(string Name, string Version, int Protocol, string[] Capabilities, TimerJob? Timer, AudioState? Audio, string? Error, AudioOutput[]? Outputs = null, WakeTarget[]? Wake = null);
public sealed record PairRequest(string Code, string Name);
public sealed record PairResult(string Token, string PhoneId, AgentInfo Agent);
public sealed record CommandResult(bool Accepted, bool Duplicate, string Message);

public sealed class AgentState
{
    public string Name { get; set; } = Environment.MachineName;
    public string Theme { get; set; } = Themes.Dark;
    public string ReleaseRepository { get; set; } = "cveloy102/pult";
    public bool CheckUpdates { get; set; } = true;
    public TimerJob? Timer { get; set; }
    public List<TimerEvent> TimerEvents { get; set; } = [];
    public List<Phone> Phones { get; set; } = [];
    public List<Receipt> Receipts { get; set; } = [];
}

public interface IStateStore
{
    AgentState Load();
    void Save(AgentState state);
    /// <summary>Set by Load when damaged settings were restored or reset.</summary>
    string? RecoveryNote => null;
}

public sealed class FileStateStore(string path) : IStateStore
{
    private string Backup => path + ".bak";
    public string? RecoveryNote { get; private set; }

    public AgentState Load()
    {
        if (!File.Exists(path) && !File.Exists(Backup)) return new();
        if (TryRead(path, out var state)) return state;
        // A damaged file must not keep the agent (and the user's remote) from starting.
        Quarantine();
        if (TryRead(Backup, out state))
        {
            RecoveryNote = "Настройки были повреждены и восстановлены из резервной копии.";
            return state;
        }
        RecoveryNote = "Настройки были повреждены и сброшены. Привяжи телефоны заново.";
        return new();
    }

    private static bool TryRead(string file, out AgentState state)
    {
        state = null!;
        if (!File.Exists(file)) return false;
        try { state = JsonSerializer.Deserialize<AgentState>(File.ReadAllText(file), Protocol.Json)!; }
        catch (JsonException) { return false; }
        return state is not null;
    }

    private void Quarantine()
    {
        if (File.Exists(path)) File.Move(path, $"{path}.corrupt-{DateTime.UtcNow:yyyyMMddHHmmss}", true);
    }

    public void Save(AgentState state)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var temp = path + ".tmp";
        using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, state, Protocol.Json);
            stream.Flush(true); // Settings are saved right before shutdown/restart commands.
        }
        if (File.Exists(path)) File.Replace(temp, path, Backup, true);
        else File.Move(temp, path);
    }
}

public interface IComputer
{
    AudioState GetAudio();
    void Execute(string action, int? value);
    AudioOutput[] GetOutputs() => [];
    WakeTarget[] GetWakeTargets() => [];
    void SetOutput(string id) => throw new RequestException(409, "Переключение вывода недоступно.");
}

public sealed class RequestException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}
