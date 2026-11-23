using Pult.Core;
using System.Net;
using System.Text.Json;

var passed = 0;
void Check(bool value, string name) { if (!value) throw new Exception(name); passed++; Console.WriteLine("PASS " + name); }
void Reject(Action action, int code, string name)
{
    try { action(); throw new Exception("Expected rejection: " + name); } catch (RequestException ex) { Check(ex.Status == code, name); }
}
var clock = new Clock(); var store = new Store(); var pc = new Computer(); var agent = new AgentService(store, pc, clock);
var info = new AgentInfo("Test", "0.1.0", 1, Protocol.Capabilities, "test");
var (code, _) = agent.BeginPairing(); var pairing = agent.Pair(new(code, "Android"), "a", info); var phone = agent.Authenticate(pairing.Token)!;
Check(phone is not null && !store.State.Phones.Any(x => x.TokenHash == pairing.Token), "tokens stored as hashes");
Reject(() => agent.Pair(new(code, "Other"), "b", info), 401, "pairing code single use");
agent.BeginPairing(); clock.Advance(121); Reject(() => agent.Pair(new("000000", "Expired"), "c", info), 401, "pairing expires");
var command = new Command(Guid.NewGuid().ToString(), "playPause");
agent.Execute(phone!, command); var duplicate = agent.Execute(phone!, command);
Check(duplicate.Duplicate && pc.Calls.Count == 1, "media toggle idempotent");
Reject(() => agent.Execute(phone!, command with { Action = "lock" }), 409, "request ID cannot change payload");
Reject(() => agent.Execute(phone!, new(Guid.NewGuid().ToString(), "volume", -1)), 400, "negative volume rejected");
Reject(() => agent.Execute(phone!, new(Guid.NewGuid().ToString(), "timer", TimerAction: "shell", DurationSeconds: 15)), 400, "timer action allowlist");
agent.Execute(phone!, new(Guid.NewGuid().ToString(), "timer", TimerAction: "shutdown", DurationSeconds: 10));
Reject(() => agent.Execute(phone!, new(Guid.NewGuid().ToString(), "timer", TimerAction: "lock", DurationSeconds: 10)), 409, "replacement consent required");
clock.Advance(5); agent.Tick(); Check(pc.Calls.Count == 1, "timer does not execute early");
clock.Advance(5); agent.Tick(); agent.Tick(); Check(pc.Calls.Count == 2 && agent.Status("test").Timer is null, "timer executes exactly once");
agent.Execute(phone!, new(Guid.NewGuid().ToString(), "timer", TimerAction: "lock", DurationSeconds: 10));
agent.Execute(phone!, new(Guid.NewGuid().ToString(), "cancelTimer")); clock.Advance(10); agent.Tick(); Check(pc.Calls.Count == 2, "cancelled timer never executes");
agent.Execute(phone!, new(Guid.NewGuid().ToString(), "timer", TimerAction: "shutdown", DurationSeconds: 10));
clock.Advance(120); agent.Tick(); Check(pc.Calls.Count == 2 && agent.LastError is not null, "overdue timer after sleep skipped");
agent.Execute(phone!, new(Guid.NewGuid().ToString(), "timer", TimerAction: "shutdown", DurationSeconds: 10));
clock.Advance(20); var restored = new AgentService(store, pc, clock); Check(restored.Status("test").Timer is null && pc.Calls.Count == 2, "overdue timer after restart skipped");
Check(restored.Execute(phone!, command).Duplicate && pc.Calls.Count == 2, "deduplication survives restart");
restored.Revoke(phone!.Id); Reject(() => restored.Execute(phone, new(Guid.NewGuid().ToString(), "lock")), 401, "revocation applies to queued commands");
var limitAgent = new AgentService(new Store(), new Computer(), clock); limitAgent.BeginPairing();
for(var i = 0; i < 5; i++) Reject(() => limitAgent.Pair(new("wrong", "Phone"), "attacker", info), 401, "wrong code rejected");
Reject(() => limitAgent.Pair(new("wrong", "Phone"), "attacker", info), 429, "pairing attempts limited");
Check(ReleaseClient.ParseVersion("android-v1.2.3", "agent") is null && ReleaseClient.ParseVersion("agent-v1.2.3-beta", "agent") is null, "release component and stable filter");
using var http = new HttpClient(new ReleaseHandler());
var release = await new ReleaseClient(http).FindAsync("owner/repo", "android");
Check(release?.Tag == "android-v1.4.0", "release pagination finds independent Android version");
Check(PowerCommand.Arguments("shutdown").SequenceEqual(new[]{"/s", "/t", "0"}), "shutdown maps to /s, never /r or /f");
Check(PowerCommand.Arguments("restart").SequenceEqual(new[]{"/r", "/t", "0"}), "restart maps to /r, never /s or /f");
foreach(var timerAction in new[]{"shutdown", "restart", "lock"})
{
    var timerStore = new Store(); var timerComputer = new Computer(); var timerClock = new Clock();
    var timerAgent = new AgentService(timerStore, timerComputer, timerClock);
    var timerCode = timerAgent.BeginPairing().Code;
    var timerPair = timerAgent.Pair(new(timerCode,"Timer phone"), "local", info); var timerPhone = timerAgent.Authenticate(timerPair.Token)!;
    var json = $"{{\"requestId\":\"{Guid.NewGuid()}\",\"action\":\"timer\",\"timerAction\":\"{timerAction}\",\"durationSeconds\":10,\"replace\":false}}";
    timerAgent.Execute(timerPhone, JsonSerializer.Deserialize<Command>(json, Protocol.Json)!);
    Check(timerAgent.Status("test").Timer?.Action == timerAction, $"{timerAction}: Android wire format retains selected timer action");
    timerAgent = new AgentService(timerStore, timerComputer, timerClock);
    Check(timerAgent.Status("test").Timer?.Action == timerAction, $"{timerAction}: timer action survives agent restart");
    timerClock.Advance(10); timerAgent.Tick(); timerAgent.Tick();
    Check(timerComputer.Calls.SequenceEqual(new[]{timerAction}), $"{timerAction}: timer executes only selected action once");
    Check(timerAgent.LastTimerEvent?.Action == timerAction && timerAgent.LastTimerEvent.Event == "dispatched", $"{timerAction}: persistent audit identifies dispatched action");
}
var installerName = "pult-agent-0.1.2-win-x64-setup.exe";
var installerUrl = $"https://github.com/owner/repo/releases/download/agent-v0.1.2/{installerName}";
var installerAsset = new ReleaseAsset(installerName, installerUrl, "sha256:" + new string('a',64));
var installerRelease = new AvailableRelease("agent-v0.1.2", new Version(0,1,2), "", "", [installerAsset]);
Check(InstallerPackage.Select(installerRelease,"owner/repo") == installerAsset, "update selects installer for exact component version and repository");
Check(InstallerPackage.Select(installerRelease with { Assets = [installerAsset with { Digest = null }] },"owner/repo") is null, "update rejects installer without digest");
Check(InstallerPackage.Select(installerRelease with { Assets = [installerAsset with { Url = "https://example.com/setup.exe" }] },"owner/repo") is null, "update rejects foreign installer URL");
Check(!InstallerPackage.IsDownloadUrl(new Uri("http://github.com/file")) && !InstallerPackage.IsDownloadUrl(new Uri("https://github.com.evil.example/file")), "installer redirects require exact trusted HTTPS hosts");
Check(TrayCountdown.Label(TimeSpan.FromSeconds(-1)) == "0с", "tray countdown never displays negative time");
Check(TrayCountdown.Label(TimeSpan.FromSeconds(59.1)) == "1", "tray countdown rounds up at minute boundary");
Check(TrayCountdown.Label(TimeSpan.FromSeconds(59)) == "59с", "tray shows seconds during last minute");
Check(TrayCountdown.Label(TimeSpan.FromMinutes(99)) == "99", "tray fits two digit minutes");
Check(TrayCountdown.Label(TimeSpan.FromMinutes(100)) == "2ч", "tray shows compact hours for long timers");
Check(TrayCountdown.Label(TimeSpan.FromHours(24)) == "24ч", "tray supports maximum timer duration");
var outputComputer = new Computer(); var outputStore = new Store(); var outputAgent = new AgentService(outputStore, outputComputer);
var outputPair = outputAgent.Pair(new(outputAgent.BeginPairing().Code, "Output phone"), "local", info);
var outputPhone = outputAgent.Authenticate(outputPair.Token)!;
Check(outputAgent.Status("test").Outputs?.Count(x => x.Selected) == 1, "output status marks current device");
var outputCommand = new Command(Guid.NewGuid().ToString(), "audioOutput", DeviceId:"headphones");
outputAgent.Execute(outputPhone, outputCommand);
Check(outputAgent.Status("test").Outputs?.Single(x => x.Selected).Id == "headphones", "output command selects requested device");
Check(outputAgent.Execute(outputPhone, outputCommand).Duplicate && outputComputer.Calls.Count == 1, "output command deduplicated");
Reject(() => outputAgent.Execute(outputPhone, outputCommand with {DeviceId="speakers"}), 409, "request ID cannot change output device");
Reject(() => outputAgent.Execute(outputPhone, new(Guid.NewGuid().ToString(),"audioOutput")), 400, "output command needs device ID");
Reject(() => outputAgent.Execute(outputPhone, new(Guid.NewGuid().ToString(),"audioOutput",42,DeviceId:"headphones")), 400, "output rejects volume parameter");
Reject(() => outputAgent.Execute(outputPhone, new(Guid.NewGuid().ToString(),"volume",42,DeviceId:"headphones")), 400, "other commands reject output parameter");
Reject(() => outputAgent.Execute(outputPhone, new(Guid.NewGuid().ToString(),"audioOutput",DeviceId:"disconnected")), 409, "disconnected output rejected");
Check(outputAgent.Status("test").Outputs?.Single(x => x.Selected).Id == "headphones", "failed switch preserves selected output");
var legacyId = Guid.NewGuid().ToString();
var legacyPayload = JsonSerializer.Serialize(new { requestId="", action="playPause", value=(int?)null, replace=false, timerAction=(string?)null, durationSeconds=(int?)null }, Protocol.Json);
outputStore.State.Receipts.Add(new(legacyId,outputPhone.Id,Protocol.Hash(legacyPayload),DateTimeOffset.UtcNow,"done"));
outputAgent = new AgentService(outputStore, outputComputer);
Check(outputAgent.Execute(outputPhone,new(legacyId,"playPause")).Duplicate, "existing command receipts survive output feature upgrade");
var settingsDirectory = Path.Combine(Path.GetTempPath(), "pult-tests-" + Guid.NewGuid().ToString("N"));
try
{
    var settingsPath = Path.Combine(settingsDirectory, "settings.json");
    new FileStateStore(settingsPath).Save(new AgentState { Name = "First" });
    new FileStateStore(settingsPath).Save(new AgentState { Name = "Second" });
    Check(File.Exists(settingsPath + ".bak") && new FileStateStore(settingsPath).Load().Name == "Second", "settings saved with previous version as backup");
    File.WriteAllText(settingsPath, "{\"name\":");
    var recoveredStore = new FileStateStore(settingsPath);
    Check(recoveredStore.Load().Name == "First" && recoveredStore.RecoveryNote is not null, "damaged settings restored from backup");
    Check(Directory.GetFiles(settingsDirectory, "settings.json.corrupt-*").Length == 1, "damaged settings kept aside for diagnostics");
    var recoveredAgent = new AgentService(new FileStateStore(settingsPath), new Computer());
    Check(recoveredAgent.LastError is not null && recoveredAgent.State.Name == "First", "agent starts and reports restored settings");
    File.WriteAllText(settingsPath, ""); File.WriteAllText(settingsPath + ".bak", "garbage");
    var resetStore = new FileStateStore(settingsPath);
    Check(resetStore.Load().Phones.Count == 0 && resetStore.RecoveryNote?.Contains("сброшены") == true, "agent starts with clean settings when backup is damaged too");
}
finally { if (Directory.Exists(settingsDirectory)) Directory.Delete(settingsDirectory, true); }
var expiryClock = new Clock(); var expiryStore = new Store();
expiryStore.State.Timer = new("expired", "lock", expiryClock.GetUtcNow().AddSeconds(-1));
var expiryAgent = new AgentService(expiryStore, new Computer(), expiryClock);
Check(expiryAgent.LastError is not null && expiryAgent.Snapshot().Error is not null, "skipped timer reported");
expiryClock.Advance(3601);
Check(expiryAgent.LastError is null && expiryAgent.Status("test").Error is null, "old agent errors expire after an hour");
// Wake-on-LAN
var wake = WakeTarget.Create([0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E], IPAddress.Parse("192.168.1.42"), IPAddress.Parse("255.255.255.0"));
Check(wake == new WakeTarget("00:1A:2B:3C:4D:5E", "192.168.1.255"), "wake target uses MAC and subnet broadcast");
Check(WakeTarget.Create([0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E], IPAddress.Parse("10.0.5.7"), IPAddress.Parse("255.255.0.0"))?.Broadcast == "10.0.255.255", "wake broadcast follows subnet mask");
Check(WakeTarget.Create(new byte[6], IPAddress.Parse("192.168.1.42"), null) is null && WakeTarget.Create([1, 2, 3], IPAddress.Parse("192.168.1.42"), null) is null, "invalid MAC is never offered for wake");
var wakeComputer = new WakeComputer(); var wakeAgent = new AgentService(new Store(), wakeComputer);
Check(wakeAgent.Status("test").Wake?.Single() == wakeComputer.Target && Protocol.Capabilities.Contains("wakeOnLan"), "status exposes wake target and capability");
Check(new AgentService(new Store(), new BrokenWakeComputer()).Status("test") is { Wake.Length: 0, Audio: not null }, "wake lookup failure does not break status");

// QR pairing link
var link = PairingLink.Create(["192.168.1.5", "10.0.0.2"], 47591, new string('A', 64), "123456", "Мой ПК & игры");
Check(link == "pult://pair?v=1&a=192.168.1.5%3A47591%2C10.0.0.2%3A47591&f=" + new string('A', 64) + "&c=123456&n=%D0%9C%D0%BE%D0%B9%20%D0%9F%D0%9A%20%26%20%D0%B8%D0%B3%D1%80%D1%8B", "pairing QR contains addresses, pin, code and escaped name");

// Timer warning
var warnNow = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
var warnJob = new TimerJob("job", "shutdown", warnNow.AddSeconds(60));
Check(TimerWarning.ShouldShow(warnJob, warnNow, null) && !TimerWarning.ShouldShow(warnJob with { DueAt = warnNow.AddSeconds(61) }, warnNow, null), "timer warning appears 60 seconds before");
Check(!TimerWarning.ShouldShow(warnJob, warnNow, "job") && TimerWarning.ShouldShow(warnJob with { Id = "replacement" }, warnNow, "job"), "timer warning shown once per timer");
Check(!TimerWarning.ShouldShow(null, warnNow, null) && !TimerWarning.ShouldShow(warnJob with { DueAt = warnNow }, warnNow, null), "no warning without a pending timer");

// Network diagnosis
const int P = NetworkDiagnosis.Private, U = NetworkDiagnosis.Public;
string Levels(NetworkCheck[] checks) => string.Join(",", checks.Select(x => x.Level));
var allowAny = new FirewallRule(true, true, true, P, FirewallRule.Any);
Check(Levels(NetworkDiagnosis.Evaluate(P, P, [allowAny], true, true)) == "ok,ok,ok", "private network with allow rule is healthy");
var publicNet = NetworkDiagnosis.Evaluate(U, U | P, [allowAny], true, true);
Check(publicNet[1] is { Level: "warn", Fix: "network" } && publicNet[2] is { Level: "error", Fix: "firewall" }, "public network flagged; private-only rule does not cover it");
Check(NetworkDiagnosis.Evaluate(P, P, [allowAny, new FirewallRule(true, true, false, P, FirewallRule.Tcp)], true, true)[2].Title.Contains("блокирует"), "block rule wins over allow rule");
Check(NetworkDiagnosis.Evaluate(P, P, [], true, true)[2] is { Level: "error", Fix: "firewall" }, "missing rule reported with fix");
Check(NetworkDiagnosis.Evaluate(P, P, [new FirewallRule(false, true, false, P, FirewallRule.Any), allowAny], true, true)[2].Level == "ok", "disabled block rule ignored");
Check(NetworkDiagnosis.Evaluate(P, P, [new FirewallRule(true, true, true, P, FirewallRule.Tcp)], true, true).Last() is { Level: "warn", Fix: "firewall" }, "TCP-only rule warns about discovery");
Check(Levels(NetworkDiagnosis.Evaluate(P, 0, [], true, true)) == "ok,ok,ok", "firewall off needs no rule");
Check(NetworkDiagnosis.Evaluate(P, P, [allowAny], false, false) is [{ Level: "error" }, _, _, { Level: "warn" }], "stopped server and busy discovery reported");
Check(NetworkDiagnosis.Evaluate(0, P, [], true, true) is [_, { Title: "Нет подключения к сети" }], "no network reported");
Check(Themes.Normalize(null) == Themes.Dark && Themes.Normalize("signal") == Themes.Dark && Themes.Normalize("orbit") == Themes.Dark, "old dark themes become dark");
Check(Themes.Normalize("quiet") == Themes.Light && Themes.Normalize(Themes.Light) == Themes.Light, "old light theme becomes light");
var themed = new AgentService(new Store(), new Computer(), clock); themed.SaveSettings("PC", "orbit", true);
Check(themed.State.Theme == Themes.Dark, "saved theme is normalized");
Console.WriteLine($"{passed} checks passed; no real OS actions.");

sealed class Store : IStateStore
{
    public AgentState State = new();
    public AgentState Load() => JsonSerializer.Deserialize<AgentState>(JsonSerializer.Serialize(State, Protocol.Json), Protocol.Json)!;
    public void Save(AgentState state) => State = JsonSerializer.Deserialize<AgentState>(JsonSerializer.Serialize(state, Protocol.Json), Protocol.Json)!;
}
sealed class Computer : IComputer
{
    private string selectedOutput = "speakers";
    public List<string> Calls = []; public AudioState GetAudio() => new(38, false);
    public void Execute(string action, int? value) => Calls.Add(action);
    public AudioOutput[] GetOutputs() => [new("speakers","Speakers",selectedOutput=="speakers"),new("headphones","Headphones",selectedOutput=="headphones")];
    public void SetOutput(string id) {if(!GetOutputs().Any(x=>x.Id==id))throw new RequestException(409,"Disconnected"); selectedOutput=id; Calls.Add("audioOutput:"+id);}
}
sealed class WakeComputer : IComputer
{
    public readonly WakeTarget Target = new("00:1A:2B:3C:4D:5E", "192.168.1.255");
    public AudioState GetAudio() => new(38, false);
    public void Execute(string action, int? value) { }
    public WakeTarget[] GetWakeTargets() => [Target];
}
sealed class BrokenWakeComputer : IComputer
{
    public AudioState GetAudio() => new(38, false);
    public void Execute(string action, int? value) { }
    public WakeTarget[] GetWakeTargets() => throw new InvalidOperationException("adapter removed");
}
sealed class Clock : TimeProvider
{
    private DateTimeOffset now = new(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
    public override DateTimeOffset GetUtcNow() => now; public void Advance(int seconds) => now = now.AddSeconds(seconds);
}
sealed class ReleaseHandler : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        object Entry(string tag) => new { tag_name = tag, draft = false, prerelease = false, html_url = "https://github.com/owner/repo/releases/tag/" + tag, body = "", assets = Array.Empty<object>() };
        var data = request.RequestUri!.Query.EndsWith("page=1") ? Enumerable.Range(0, 100).Select(i => Entry("agent-v1.0.0")).ToArray() : new[] { Entry("android-v1.4.0"), Entry("android-v1.3.0") };
        return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(data)) });
    }
}
