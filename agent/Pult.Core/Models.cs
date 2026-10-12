using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Pult.Core;

public static class Protocol
{
    public const int Version = 1;
    public const int Port = 47591;
    public const int DiscoveryPort = 47592;
    public static readonly string[] Capabilities = ["shutdown", "restart", "lock", "timer", "volume", "playPause"];
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    public static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    public static string Token() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
    public static bool Equal(string a, string b) => CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));
}

public sealed record Phone(string Id, string Name, string TokenHash, DateTimeOffset PairedAt);
public sealed record TimerJob(string Id, string Action, DateTimeOffset DueAt);
public sealed record TimerEvent(DateTimeOffset Time, string TimerId, string Action, string Event);
public sealed record Receipt(string Id, string PhoneId, string PayloadHash, DateTimeOffset CreatedAt, string Status);
public sealed record Command(string RequestId, string Action, int? Value = null, bool Replace = false, string? TimerAction = null, int? DurationSeconds = null);
public sealed record AudioState(int Volume, bool Muted);
public sealed record AgentInfo(string Name, string Version, int Protocol, string[] Capabilities, string Fingerprint);
public sealed record AgentStatus(string Name, string Version, int Protocol, string[] Capabilities, TimerJob? Timer, AudioState? Audio, string? Error);
public sealed record PairRequest(string Code, string Name);
public sealed record PairResult(string Token, string PhoneId, AgentInfo Agent);
public sealed record CommandResult(bool Accepted, bool Duplicate, string Message);

public sealed class AgentState
{
    public string Name { get; set; } = Environment.MachineName;
    public string Theme { get; set; } = "signal";
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
}

public sealed class FileStateStore(string path) : IStateStore
{
    public AgentState Load() => File.Exists(path)
        ? JsonSerializer.Deserialize<AgentState>(File.ReadAllText(path), Protocol.Json) ?? throw new IOException("Настройки повреждены.")
        : new();

    public void Save(AgentState state)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(state, Protocol.Json));
        File.Move(temp, path, true);
    }
}

public interface IComputer
{
    AudioState GetAudio();
    void Execute(string action, int? value);
}

public sealed class RequestException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}
