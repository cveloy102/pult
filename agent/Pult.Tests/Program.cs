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
Console.WriteLine($"{passed} checks passed; no real OS actions.");

sealed class Store : IStateStore
{
    public AgentState State = new();
    public AgentState Load() => JsonSerializer.Deserialize<AgentState>(JsonSerializer.Serialize(State, Protocol.Json), Protocol.Json)!;
    public void Save(AgentState state) => State = JsonSerializer.Deserialize<AgentState>(JsonSerializer.Serialize(state, Protocol.Json), Protocol.Json)!;
}
sealed class Computer : IComputer
{
    public List<string> Calls = []; public AudioState GetAudio() => new(38, false);
    public void Execute(string action, int? value) => Calls.Add(action);
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
