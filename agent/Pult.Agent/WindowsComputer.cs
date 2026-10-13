using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Pult.Agent;

public sealed partial class WindowsComputer(bool demo) : IComputer
{
    private int demoVolume = 38;
    public AudioState GetAudio() => demo ? new(demoVolume, false) : WithAudio(endpoint =>
    {
        Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out var level));
        Marshal.ThrowExceptionForHR(endpoint.GetMute(out var muted));
        return new AudioState((int)Math.Round(level * 100), muted);
    });
    public void Execute(string action, int? value)
    {
        if (demo) { if (action == "volume") demoVolume = value!.Value; if (action == "volumeStep") demoVolume = Math.Clamp(demoVolume + value!.Value, 0, 100); return; }
        switch (action)
        {
            case "shutdown": case "restart":
                // No /f: Windows is allowed to ask about unsaved documents.
                var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "shutdown.exe")) { CreateNoWindow = true, UseShellExecute = false };
                foreach(var argument in PowerCommand.Arguments(action)) start.ArgumentList.Add(argument);
                using (var process = Process.Start(start))
                {
                    if (process is null || !process.WaitForExit(5000) || process.ExitCode != 0) throw new InvalidOperationException("Windows не приняла команду питания.");
                }
                break;
            case "lock": if (!LockWorkStation()) throw new InvalidOperationException("Не удалось заблокировать сеанс Windows."); break;
            case "playPause":
                var inputs = new[] { KeyInput(0xB3, 0), KeyInput(0xB3, 2) };
                if (SendInput(2, inputs, Marshal.SizeOf<Input>()) != 2) throw new InvalidOperationException("Не удалось отправить медиаклавишу.");
                break;
            case "volume": case "volumeStep":
                WithAudio(endpoint =>
                {
                    Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out var current));
                    var next = action == "volume" ? value!.Value / 100f : Math.Clamp(current + value!.Value / 100f, 0, 1);
                    Marshal.ThrowExceptionForHR(endpoint.SetMasterVolumeLevelScalar(next, IntPtr.Zero));
                    return true;
                }); break;
            default: throw new InvalidOperationException("Неизвестная системная команда.");
        }
    }

    private static T WithAudio<T>(Func<IAudioEndpointVolume, T> action)
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDevice? device = null; object? endpoint = null;
        try
        {
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));
            var iid = typeof(IAudioEndpointVolume).GUID;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out endpoint));
            return action((IAudioEndpointVolume)endpoint);
        }
        finally
        {
            if (endpoint is not null) Marshal.ReleaseComObject(endpoint);
            if (device is not null) Marshal.ReleaseComObject(device);
            Marshal.ReleaseComObject(enumerator);
        }
    }
    [DllImport("user32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool LockWorkStation();
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, Input[] inputs, int size);
    [StructLayout(LayoutKind.Sequential)] private struct Input { public uint Type; public InputUnion Data; }
    [StructLayout(LayoutKind.Explicit)] private struct InputUnion
    {
        [FieldOffset(0)] public KeyboardInput Keyboard;
        [FieldOffset(0)] public MouseInput Mouse;
    }
    [StructLayout(LayoutKind.Sequential)] private struct MouseInput { public int X, Y; public uint Data, Flags, Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)] private struct KeyboardInput { public ushort Key, Scan; public uint Flags, Time; public UIntPtr Extra; }
    private static Input KeyInput(ushort key, uint flags) => new() { Type = 1, Data = new() { Keyboard = new() { Key = key, Flags = flags } } };

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] private class MMDeviceEnumerator { }
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int flow, int mask, out IMMDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object endpoint);
        [PreserveSig] int OpenPropertyStore(uint access, out IPropertyStore properties);
        [PreserveSig] int GetId(out IntPtr id);
        [PreserveSig] int GetState(out uint state);
    }
    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(IntPtr callback);
        [PreserveSig] int UnregisterControlChangeNotify(IntPtr callback);
        [PreserveSig] int GetChannelCount(out uint count);
        [PreserveSig] int SetMasterVolumeLevel(float level, IntPtr context);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, IntPtr context);
        [PreserveSig] int GetMasterVolumeLevel(out float level);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(uint channel, float level, IntPtr context);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, IntPtr context);
        [PreserveSig] int GetChannelVolumeLevel(uint channel, out float level);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, IntPtr context);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
