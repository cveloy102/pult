using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;

namespace Pult.Agent;

internal static class TimerTrayIcon
{
    internal static Icon Create(Icon original, string label, string action)
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.Clear(Color.Transparent);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
        graphics.DrawIcon(original, new Rectangle(0, 0, 22, 22));
        var color = action switch { "restart" => Color.FromArgb(255, 194, 92), "lock" => Color.FromArgb(181, 206, 255), _ => Color.FromArgb(240, 78, 72) };
        using var badge = new SolidBrush(color);
        using var path = new GraphicsPath();
        path.AddArc(4, 15, 8, 8, 180, 90); path.AddArc(24, 15, 8, 8, 270, 90);
        path.AddArc(24, 24, 8, 8, 0, 90); path.AddArc(4, 24, 8, 8, 90, 90); path.CloseFigure();
        graphics.FillPath(badge, path);
        using var font = new Font("Segoe UI", label.Length > 2 ? 11 : 13, FontStyle.Bold, GraphicsUnit.Pixel);
        using var format = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        graphics.DrawString(label, font, Brushes.Black, new RectangleF(4, 15, 28, 17), format);
        var native = bitmap.GetHicon();
        try { using var borrowed = Icon.FromHandle(native); return (Icon)borrowed.Clone(); }
        finally { DestroyIcon(native); }
    }
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr icon);
}
