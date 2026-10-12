using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;

namespace Pult.Agent;

internal static class TimerTrayIcon
{
    internal static Icon Create(string label, string action)
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.Clear(Color.Transparent);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        var color = action switch { "restart" => Color.FromArgb(255, 194, 92), "lock" => Color.FromArgb(181, 206, 255), _ => Color.FromArgb(240, 78, 72) };
        using var background = new SolidBrush(Color.FromArgb(16, 16, 17));
        using var ink = new SolidBrush(color);
        using var path = new GraphicsPath();
        path.AddArc(0, 0, 4, 4, 180, 90); path.AddArc(28, 0, 4, 4, 270, 90);
        path.AddArc(28, 28, 4, 4, 0, 90); path.AddArc(0, 28, 4, 4, 90, 90); path.CloseFigure();
        graphics.FillPath(background, path);
        // Units are in the tooltip; keep the full tray area for readable digits.
        var digits = string.Concat(label.Where(char.IsDigit));
        using var format = (StringFormat)StringFormat.GenericTypographic.Clone();
        format.Alignment = StringAlignment.Center; format.LineAlignment = StringAlignment.Center;
        var fontSize = 32f;
        while (fontSize > 8)
        {
            using var candidate = new Font("Consolas", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
            var measured = graphics.MeasureString(digits, candidate, new SizeF(100, 100), format);
            if (measured.Width <= 30 && measured.Height <= 32) break;
            fontSize -= .5f;
        }
        using var font = new Font("Consolas", fontSize, FontStyle.Bold, GraphicsUnit.Pixel);
        graphics.DrawString(digits, font, ink, new RectangleF(1, 0, 30, 32), format);
        var native = bitmap.GetHicon();
        try { using var borrowed = Icon.FromHandle(native); return (Icon)borrowed.Clone(); }
        finally { DestroyIcon(native); }
    }
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr icon);
}
