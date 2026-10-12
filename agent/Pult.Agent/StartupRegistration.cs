using Microsoft.Win32;

namespace Pult.Agent;

internal static class StartupRegistration
{
    internal static bool IsDisabledByWindows
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run");
            return key?.GetValue("Pult") is byte[] { Length: > 0 } bytes && bytes[0] is 3 or 7;
        }
    }
    internal static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            return key?.GetValue("Pult") is string command && command == $"\"{Environment.ProcessPath}\" --tray" && !IsDisabledByWindows;
        }
    }
    internal static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if(enabled) key.SetValue("Pult", $"\"{Environment.ProcessPath}\" --tray"); else key.DeleteValue("Pult", false);
    }
}
