using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;

namespace Pult.Agent;

internal static class IntegrationCheck
{
    public static async Task Run(AgentHost host, string directory)
    {
        await host.StartAsync(true, 47599);
        using var handler = new HttpClientHandler { UseProxy = false, ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert is not null && Convert.ToHexString(SHA256.HashData(cert.RawData)) == host.Fingerprint };
        using var http = new HttpClient(handler) { BaseAddress = new Uri("https://127.0.0.1:47599"), Timeout = TimeSpan.FromSeconds(10) };
        var checks = new List<string>();
        void Check(bool condition, string label) { if (!condition) throw new InvalidOperationException(label); checks.Add(label); }
        var unauthorized = await http.GetAsync("/api/status"); Check(unauthorized.StatusCode == HttpStatusCode.Unauthorized, "status requires token");
        var info = await http.GetFromJsonAsync<AgentInfo>("/api/info", Protocol.Json); Check(info?.Fingerprint == host.Fingerprint, "HTTPS identity");
        var (code, _) = host.Service.BeginPairing();
        var pairResponse = await http.PostAsJsonAsync("/api/pair", new PairRequest(code, "Integration phone")); pairResponse.EnsureSuccessStatusCode();
        var pair = await pairResponse.Content.ReadFromJsonAsync<PairResult>(Protocol.Json) ?? throw new Exception("pair response");
        Check(host.Service.Authenticate(pair.Token) is not null, "one-time pairing");
        var replayPair = await http.PostAsJsonAsync("/api/pair", new PairRequest(code, "Replay")); Check(replayPair.StatusCode == HttpStatusCode.Unauthorized, "pairing code cannot be reused");
        http.DefaultRequestHeaders.Authorization = new("Bearer", pair.Token);
        var command = new Command(Guid.NewGuid().ToString(), "volume", 61);
        var result = await http.PostAsJsonAsync("/api/command", command); result.EnsureSuccessStatusCode();
        var status = await http.GetFromJsonAsync<AgentStatus>("/api/status", Protocol.Json); Check(status?.Audio?.Volume == 61, "volume state through API");
        var repeated = await http.PostAsJsonAsync("/api/command", command); var repeatBody = await repeated.Content.ReadFromJsonAsync<CommandResult>(Protocol.Json); Check(repeatBody?.Duplicate == true, "duplicate requests not repeated");
        var invalid = await http.PostAsJsonAsync("/api/command", new Command(Guid.NewGuid().ToString(), "shell", 1)); Check(invalid.StatusCode == HttpStatusCode.BadRequest, "unknown command rejected");
        var timer = await http.PostAsJsonAsync("/api/command", new Command(Guid.NewGuid().ToString(), "timer", TimerAction: "lock", DurationSeconds: 120)); timer.EnsureSuccessStatusCode();
        var conflict = await http.PostAsJsonAsync("/api/command", new Command(Guid.NewGuid().ToString(), "timer", TimerAction: "shutdown", DurationSeconds: 60)); Check(conflict.StatusCode == HttpStatusCode.Conflict, "timer replacement requires consent");
        var cancel = await http.PostAsJsonAsync("/api/command", new Command(Guid.NewGuid().ToString(), "cancelTimer")); cancel.EnsureSuccessStatusCode();
        Check(host.Service.Status(AgentHost.Version).Timer is null, "timer cancellation");
        http.DefaultRequestHeaders.Add("Origin", "https://example.com"); var origin = await http.GetAsync("/api/status"); Check(origin.StatusCode == HttpStatusCode.Forbidden, "browser origins rejected"); http.DefaultRequestHeaders.Remove("Origin");
        host.Service.Revoke(pair.PhoneId); var revoked = await http.GetAsync("/api/status"); Check(revoked.StatusCode == HttpStatusCode.Unauthorized, "revoked phone rejected");
        Check(!AgentHost.IsLocal(IPAddress.Parse("8.8.8.8")) && AgentHost.IsLocal(IPAddress.Parse("192.168.1.2")), "LAN address boundary");
        File.WriteAllText(Path.Combine(directory, "integration-result.json"), JsonSerializer.Serialize(new { passed = checks.Count, checks }, Protocol.Json));
    }
}
