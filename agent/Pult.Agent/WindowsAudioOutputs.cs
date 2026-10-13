using System.Runtime.InteropServices;

namespace Pult.Agent;

public sealed partial class WindowsComputer
{
    private string demoOutput = "demo-speakers";
    public AudioOutput[] GetOutputs()
    {
        if (demo) return [new("demo-speakers", "Колонки", demoOutput == "demo-speakers"), new("demo-headphones", "Наушники", demoOutput == "demo-headphones")];
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDeviceCollection? collection = null; IMMDevice? current = null;
        try
        {
            string? currentId = null;
            if (enumerator.GetDefaultAudioEndpoint(0, 1, out current) >= 0) currentId = DeviceId(current);
            Marshal.ThrowExceptionForHR(enumerator.EnumAudioEndpoints(0, 1, out collection));
            Marshal.ThrowExceptionForHR(collection.GetCount(out var count));
            var outputs = new List<AudioOutput>();
            for (uint i = 0; i < count; i++)
            {
                IMMDevice? device = null; IPropertyStore? store = null;
                try
                {
                    Marshal.ThrowExceptionForHR(collection.Item(i, out device));
                    var id = DeviceId(device);
                    Marshal.ThrowExceptionForHR(device.OpenPropertyStore(0, out store));
                    var key = new PropertyKey { Format = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), Id = 14 };
                    Marshal.ThrowExceptionForHR(store.GetValue(ref key, out var name));
                    try
                    {
                        var display = name.Type == 31 ? Marshal.PtrToStringUni(name.Pointer) : null;
                        outputs.Add(new(id, string.IsNullOrWhiteSpace(display) ? "Аудиоустройство" : display, string.Equals(id, currentId, StringComparison.OrdinalIgnoreCase)));
                    }
                    finally { PropVariantClear(ref name); }
                }
                catch (COMException) { /* Hot-unplug during enumeration: skip the removed endpoint. */ }
                finally { if (store is not null) Marshal.ReleaseComObject(store); if (device is not null) Marshal.ReleaseComObject(device); }
            }
            return outputs.OrderByDescending(x => x.Selected).ThenBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
        }
        finally { if (current is not null) Marshal.ReleaseComObject(current); if (collection is not null) Marshal.ReleaseComObject(collection); Marshal.ReleaseComObject(enumerator); }
    }
    public void SetOutput(string id)
    {
        if (!GetOutputs().Any(x => x.Id == id)) throw new RequestException(409, "Устройство отключено или недоступно. Выбери другое из списка.");
        if (demo) { demoOutput = id; return; }
        var previousConsole = DefaultOutputId(0); var previousMedia = DefaultOutputId(1);
        var policy = (IPolicyConfig)new PolicyConfigClient();
        try
        {
            // General Windows/media output. Leave a separately configured call device intact.
            Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(id, 0));
            Marshal.ThrowExceptionForHR(policy.SetDefaultEndpoint(id, 1));
        }
        catch (COMException)
        {
            // The roles are separate Windows operations; restore them on a partial failure.
            if (previousConsole is not null) policy.SetDefaultEndpoint(previousConsole, 0);
            if (previousMedia is not null) policy.SetDefaultEndpoint(previousMedia, 1);
            throw new RequestException(409, "Windows не смогла переключить вывод. Проверь подключение устройства.");
        }
        finally { Marshal.ReleaseComObject(policy); }
        if (!GetOutputs().Any(x => x.Id == id && x.Selected)) throw new RequestException(409, "Вывод изменился во время переключения. Обнови список.");
    }
    private static string DeviceId(IMMDevice device)
    {
        Marshal.ThrowExceptionForHR(device.GetId(out var pointer));
        try { return Marshal.PtrToStringUni(pointer) ?? throw new COMException("No endpoint ID."); }
        finally { Marshal.FreeCoTaskMem(pointer); }
    }
    private static string? DefaultOutputId(int role)
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator(); IMMDevice? device = null;
        try { return enumerator.GetDefaultAudioEndpoint(0, role, out device) >= 0 ? DeviceId(device) : null; }
        finally { if(device is not null)Marshal.ReleaseComObject(device); Marshal.ReleaseComObject(enumerator); }
    }
    [StructLayout(LayoutKind.Sequential)] private struct PropertyKey { public Guid Format; public uint Id; }
    [StructLayout(LayoutKind.Explicit, Size = 24)] private struct PropertyValue { [FieldOffset(0)] public ushort Type; [FieldOffset(8)] public IntPtr Pointer; }
    [DllImport("ole32.dll")] private static extern int PropVariantClear(ref PropertyValue value);
    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceCollection { [PreserveSig] int GetCount(out uint count); [PreserveSig] int Item(uint index, out IMMDevice device); }
    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count); [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropertyValue value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropertyValue value); [PreserveSig] int Commit();
    }
    // Windows policy interop: same interface used by EarTrumpet. Only SetDefaultEndpoint is called.
    // https://github.com/File-New-Project/EarTrumpet/blob/master/EarTrumpet/Interop/MMDeviceAPI/IPolicyConfig.cs
    [ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")] private class PolicyConfigClient { }
    [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPolicyConfig
    {
        void Unused1(); void Unused2(); void Unused3(); void Unused4(); void Unused5(); void Unused6(); void Unused7(); void Unused8();
        void Unused9(); void Unused10();
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
    }
}
