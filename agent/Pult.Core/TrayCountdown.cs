namespace Pult.Core;

public static class TrayCountdown
{
    public static string Label(TimeSpan remaining)
    {
        var seconds = Math.Max(0, (int)Math.Ceiling(remaining.TotalSeconds));
        if (seconds < 60) return $"{seconds}с";
        var minutes = (int)Math.Ceiling(seconds / 60d);
        return minutes < 100 ? minutes.ToString() : $"{(int)Math.Ceiling(minutes / 60d)}ч";
    }
}
