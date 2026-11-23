namespace Pult.Core;

/// <summary>Theme choice: follow Windows, or the dark or light theme of the design code.
/// Before 0.3 the themes were "signal", "quiet" (light) and "orbit"; a new install follows the system.</summary>
public static class Themes
{
    public const string System = "system";
    public const string Dark = "dark";
    public const string Light = "light";

    public static string Normalize(string? theme) => theme switch
    {
        Light or "quiet" => Light,
        Dark or "signal" or "orbit" => Dark,
        _ => System
    };

    /// <summary>The theme actually drawn; "system" follows the Windows app mode.</summary>
    public static string Resolve(string? theme, bool systemLight) => Normalize(theme) switch
    {
        System => systemLight ? Light : Dark,
        var chosen => chosen
    };
}
