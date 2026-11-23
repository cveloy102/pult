using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace Pult.Agent;

public sealed partial class WindowsComputer
{
    public WakeTarget[] GetWakeTargets()
    {
        if (demo) return [new("02:50:55:4C:54:01", "192.168.1.255")];
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(x => x.OperationalStatus == OperationalStatus.Up &&
                x.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet or NetworkInterfaceType.Wireless80211)
            // A default gateway separates the real home adapter from Hyper-V/WSL/VPN virtual switches.
            .Where(x => x.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)))
            // Wired adapters first: Wake-on-LAN over Wi-Fi is rarely supported.
            .OrderBy(x => x.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
            .SelectMany(x => x.GetIPProperties().UnicastAddresses
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && AgentHost.IsLocal(a.Address) && !IPAddress.IsLoopback(a.Address) && a.Address.GetAddressBytes()[0] != 169)
                .Select(a => WakeTarget.Create(x.GetPhysicalAddress().GetAddressBytes(), a.Address, a.IPv4Mask)))
            .OfType<WakeTarget>().Distinct().Take(4).ToArray();
    }
}

/// <summary>Reads network type and Windows Firewall rules for the agent. Reading needs no administrator rights.</summary>
internal static class NetworkInspector
{
    public static NetworkCheck[] Run(bool? serverRunning = null, bool? discoveryRunning = null)
    {
        var path = Environment.ProcessPath ?? "";
        var listeners = IPGlobalProperties.GetIPGlobalProperties();
        var server = serverRunning ?? listeners.GetActiveTcpListeners().Any(x => x.Port == Protocol.Port);
        var discovery = discoveryRunning ?? listeners.GetActiveUdpListeners().Any(x => x.Port == Protocol.DiscoveryPort);
        try { return NetworkDiagnosis.Evaluate(Profiles(), FirewallOn(), Rules(path), server, discovery); }
        catch (Exception ex) { return [new("warn", "Проверка сети не удалась", ex.Message)]; }
    }

    private static int Profiles()
    {
        dynamic manager = Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B"))!)!;
        try
        {
            var profiles = 0;
            foreach (dynamic network in manager.GetNetworks(1)) // NLM_ENUM_NETWORK_CONNECTED
            {
                int category = network.GetCategory();
                profiles |= category switch { 1 => NetworkDiagnosis.Private, 2 => NetworkDiagnosis.Domain, _ => NetworkDiagnosis.Public };
                Marshal.FinalReleaseComObject(network);
            }
            return profiles;
        }
        finally { Marshal.FinalReleaseComObject(manager); }
    }

    private static dynamic Policy() => Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2") ?? throw new IOException("Брандмауэр Windows недоступен."))!;

    private static int FirewallOn()
    {
        dynamic policy = Policy();
        try
        {
            var enabled = 0;
            foreach (var profile in new[] { NetworkDiagnosis.Domain, NetworkDiagnosis.Private, NetworkDiagnosis.Public })
                if ((bool)policy.FirewallEnabled[profile]) enabled |= profile;
            return enabled;
        }
        finally { Marshal.FinalReleaseComObject(policy); }
    }

    private static FirewallRule[] Rules(string path)
    {
        dynamic policy = Policy();
        try
        {
            var result = new List<FirewallRule>();
            foreach (dynamic rule in policy.Rules)
            {
                try
                {
                    string? application = rule.ApplicationName;
                    if (application is not null && string.Equals(Environment.ExpandEnvironmentVariables(application), path, StringComparison.OrdinalIgnoreCase))
                        result.Add(new FirewallRule((bool)rule.Enabled, (int)rule.Direction == 1, (int)rule.Action == 1, (int)rule.Profiles, (int)rule.Protocol));
                }
                finally { Marshal.FinalReleaseComObject(rule); }
            }
            return [.. result];
        }
        finally { Marshal.FinalReleaseComObject(policy); }
    }

    /// <summary>Replaces this program's firewall rules with one allow rule for private and domain networks. Asks for UAC consent.</summary>
    public static bool AllowInFirewall()
    {
        var path = Environment.ProcessPath ?? throw new IOException("Не найден путь программы.");
        var program = $"program=\"{path}\"";
        var start = new ProcessStartInfo("cmd.exe",
            $"/c netsh advfirewall firewall delete rule name=all {program} >nul & netsh advfirewall firewall add rule name=\"Pult Agent\" dir=in action=allow {program} profile=private,domain enable=yes")
        { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden };
        try
        {
            using var process = Process.Start(start);
            if (process is null) return false;
            process.WaitForExit(30000);
            return process.HasExited && process.ExitCode == 0;
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223) { return false; } // UAC declined.
    }

    public static void OpenNetworkSettings() => Process.Start(new ProcessStartInfo("ms-settings:network-status") { UseShellExecute = true });
}
