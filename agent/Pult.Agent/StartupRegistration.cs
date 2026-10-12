using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Security.Principal;

namespace Pult.Agent;

internal static class StartupRegistration
{
    internal static string TaskName => $"Pult Agent ({Environment.UserName})";
    private static dynamic Connect()
    {
        dynamic service = Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service") ?? throw new IOException("Планировщик задач недоступен."))!;
        service.Connect(); return service;
    }
    internal static bool IsEnabled
    {
        get
        {
            dynamic service = Connect(); dynamic root = service.GetFolder(@"\");
            try
            {
                dynamic task = root.GetTask(TaskName);
                try { dynamic action = task.Definition.Actions.Item(1); return task.Enabled && string.Equals((string)action.Path, Environment.ProcessPath, StringComparison.OrdinalIgnoreCase) && (string)action.Arguments == "--tray"; }
                finally { Marshal.FinalReleaseComObject(task); }
            }
            catch (COMException ex) when ((uint)ex.HResult == 0x80070002) { return false; }
            finally { Marshal.FinalReleaseComObject(root); Marshal.FinalReleaseComObject(service); }
        }
    }
    internal static void Set(bool enabled)
    {
        dynamic service = Connect(); dynamic root = service.GetFolder(@"\");
        try
        {
            if (enabled)
            {
                var sid = WindowsIdentity.GetCurrent().User!.Value;
                dynamic definition = service.NewTask(0);
                try
                {
                    definition.RegistrationInfo.Description = "Пульт: агент в трее при входе текущего пользователя.";
                    definition.RegistrationInfo.Author = "Pult";
                    definition.Principal.UserId = sid; definition.Principal.LogonType = 3; definition.Principal.RunLevel = 0;
                    definition.Settings.Enabled = true; definition.Settings.AllowDemandStart = true;
                    definition.Settings.StartWhenAvailable = true; definition.Settings.DisallowStartIfOnBatteries = false;
                    definition.Settings.StopIfGoingOnBatteries = false; definition.Settings.ExecutionTimeLimit = "PT0S";
                    definition.Settings.RestartCount = 3; definition.Settings.RestartInterval = "PT1M";
                    definition.Settings.MultipleInstances = 2;
                    dynamic trigger = definition.Triggers.Create(9); trigger.UserId = sid; trigger.Delay = "PT15S";
                    dynamic action = definition.Actions.Create(0); action.Path = Environment.ProcessPath!;
                    action.Arguments = "--tray"; action.WorkingDirectory = Path.GetDirectoryName(Environment.ProcessPath)!;
                    dynamic registered = root.RegisterTaskDefinition(TaskName, definition, 6, sid, null, 3, null);
                    Marshal.FinalReleaseComObject(registered); Marshal.FinalReleaseComObject(action); Marshal.FinalReleaseComObject(trigger);
                }
                finally { Marshal.FinalReleaseComObject(definition); }
            }
            else
            {
                try { root.DeleteTask(TaskName, 0); }
                catch (COMException ex) when ((uint)ex.HResult == 0x80070002) { }
            }
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            key?.DeleteValue("Pult", false);
        }
        finally { Marshal.FinalReleaseComObject(root); Marshal.FinalReleaseComObject(service); }
    }
}
