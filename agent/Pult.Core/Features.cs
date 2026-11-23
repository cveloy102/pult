using System.Net;

namespace Pult.Core;

/// <summary>Where the phone sends a Wake-on-LAN magic packet while the PC is off.</summary>
public sealed record WakeTarget(string Mac, string Broadcast)
{
    public static WakeTarget? Create(byte[] mac, IPAddress address, IPAddress? mask)
    {
        if (mac.Length != 6 || mac.All(x => x == 0) || mac.All(x => x == 0xFF)) return null;
        var ip = address.GetAddressBytes();
        if (ip.Length != 4) return null;
        var bits = mask?.GetAddressBytes() is { Length: 4 } m ? m : [255, 255, 255, 0];
        var broadcast = new byte[4];
        for (var i = 0; i < 4; i++) broadcast[i] = (byte)(ip[i] | ~bits[i]);
        return new(string.Join(":", mac.Select(x => x.ToString("X2"))), new IPAddress(broadcast).ToString());
    }
}

/// <summary>Pairing data shown as a QR code on the PC: addresses, certificate pin and one-time code.</summary>
public static class PairingLink
{
    public static string Create(IEnumerable<string> addresses, int port, string fingerprint, string code, string name)
    {
        var hosts = string.Join(",", addresses.Select(x => $"{x}:{port}"));
        return $"pult://pair?v=1&a={Uri.EscapeDataString(hosts)}&f={fingerprint}&c={code}&n={Uri.EscapeDataString(name)}";
    }
}

/// <summary>Shows a cancellable warning on the PC shortly before a timer runs.</summary>
public static class TimerWarning
{
    public static readonly TimeSpan Lead = TimeSpan.FromSeconds(60);
    public static bool ShouldShow(TimerJob? job, DateTimeOffset now, string? shownFor) =>
        job is not null && job.Id != shownFor && job.DueAt - now <= Lead && job.DueAt > now;
}

public sealed record FirewallRule(bool Enabled, bool Inbound, bool Allow, int Profiles, int Protocol)
{
    public const int Tcp = 6, Udp = 17, Any = 256;
    public bool Covers(int protocol) => Protocol == Any || Protocol == protocol;
}

/// <summary>One line of the agent's network check. Level: ok, warn, error. Fix: network, firewall or null.</summary>
public sealed record NetworkCheck(string Level, string Title, string Detail, string? Fix = null);

public static class NetworkDiagnosis
{
    public const int Domain = 1, Private = 2, Public = 4;

    /// <param name="profiles">Firewall profiles of connected networks (bit mask).</param>
    /// <param name="firewallOn">Profiles where Windows Firewall is enabled (bit mask).</param>
    /// <param name="rules">Inbound and outbound rules that name the agent executable.</param>
    public static NetworkCheck[] Evaluate(int profiles, int firewallOn, IReadOnlyList<FirewallRule> rules, bool serverRunning, bool discoveryRunning)
    {
        var result = new List<NetworkCheck>();
        result.Add(serverRunning
            ? new("ok", "Агент принимает подключения", $"HTTPS, порт {Protocol.Port}.")
            : new("error", "Агент не принимает подключения", $"Порт {Protocol.Port} не открыт. Перезапусти Пульт; возможно, порт занят другой программой."));
        if (profiles == 0)
        {
            result.Add(new("warn", "Нет подключения к сети", "Подключи ПК к домашней сети."));
            return [.. result];
        }
        result.Add((profiles & Public) != 0
            ? new("warn", "Сеть общедоступная", "Windows закрывает входящие подключения в общедоступных сетях. Для домашней сети выбери тип «Частная».", "network")
            : new("ok", (profiles & Domain) != 0 ? "Сеть домена" : "Частная сеть", "Тип сети подходит для Пульта."));

        var checkedProfiles = profiles & firewallOn;
        if (checkedProfiles == 0)
        {
            result.Add(new("ok", "Брандмауэр не ограничивает Пульт", "Брандмауэр Windows выключен для этой сети."));
            return [.. result];
        }
        var tcp = State(checkedProfiles, rules, FirewallRule.Tcp);
        result.Add(tcp switch
        {
            RuleState.Allowed => new("ok", "Брандмауэр разрешает Пульт", "Входящие подключения телефона разрешены."),
            RuleState.Blocked => new("error", "Брандмауэр блокирует Пульт", "Есть запрещающее правило для агента: телефон не сможет подключиться.", "firewall"),
            _ => new("error", "Нет разрешения в брандмауэре", "Для агента нет разрешающего правила: телефон не сможет подключиться.", "firewall"),
        });
        if (tcp == RuleState.Allowed && State(checkedProfiles, rules, FirewallRule.Udp) != RuleState.Allowed)
            result.Add(new("warn", "Поиск ПК может не работать", "UDP для автоматического поиска не разрешён. Подключение по адресу работает.", "firewall"));
        if (!discoveryRunning)
            result.Add(new("warn", "Автоматический поиск недоступен", $"Порт {Protocol.DiscoveryPort} занят. Подключайся по адресу."));
        return [.. result];
    }

    private enum RuleState { Missing, Allowed, Blocked }

    // Every checked profile must allow the protocol; a matching block rule wins, as in Windows Firewall.
    private static RuleState State(int profiles, IReadOnlyList<FirewallRule> rules, int protocol)
    {
        var state = RuleState.Allowed;
        foreach (var profile in new[] { Domain, Private, Public }.Where(p => (profiles & p) != 0))
        {
            var matching = rules.Where(r => r.Enabled && r.Inbound && (r.Profiles & profile) != 0 && r.Covers(protocol)).ToArray();
            if (matching.Any(r => !r.Allow)) return RuleState.Blocked;
            if (!matching.Any(r => r.Allow)) state = RuleState.Missing;
        }
        return state;
    }
}
