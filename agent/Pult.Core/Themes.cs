namespace Pult.Core;

/// <summary>The two themes of the design code. Before 0.3 the themes were "signal", "quiet" (light) and "orbit".</summary>
public static class Themes
{
    public const string Dark = "dark";
    public const string Light = "light";

    public static string Normalize(string? theme) => theme is Light or "quiet" ? Light : Dark;
}
