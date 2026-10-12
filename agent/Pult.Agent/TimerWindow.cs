using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Pult.Agent;

internal sealed class TimerWindow : Window
{
    private readonly TextBlock actionText = new() { FontSize = 24, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock countdown = new() { FontSize = 46, FontFamily = new FontFamily("Consolas"), Margin = new Thickness(0, 15, 0, 8) };
    private readonly TextBlock deadline = new() { FontSize = 12, Margin = new Thickness(0, 0, 0, 18) };
    private readonly TextBlock error = new() { FontSize = 12, TextWrapping = TextWrapping.Wrap };
    internal TimerWindow(Action cancel, ImageSource icon)
    {
        Title = "Пульт · таймер"; Icon = icon; Width = 390; Height = 390; ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = true; ShowActivated = false;
        SetResourceReference(BackgroundProperty, "Base"); SetResourceReference(ForegroundProperty, "Ink");
        var panel = new StackPanel { Margin = new Thickness(24) };
        var label = new TextBlock { Text = "АКТИВНЫЙ ТАЙМЕР", FontSize = 10, Margin = new Thickness(0, 0, 0, 12) }; label.SetResourceReference(TextBlock.ForegroundProperty, "Sub");
        panel.Children.Add(label); panel.Children.Add(actionText); panel.Children.Add(countdown); panel.Children.Add(deadline);
        var button = new Button { Content = "Отменить таймер", Style = (Style)System.Windows.Application.Current.FindResource("Primary") };
        button.Click += (_, _) => { try { cancel(); } catch(Exception ex) { error.Text = $"Не удалось отменить: {ex.Message}"; } };
        panel.Children.Add(button); panel.Children.Add(error);
        panel.Children.Add(new TextBlock { Text = "Закрытие окна оставляет таймер в трее.", FontSize = 11, Margin = new Thickness(0, 12, 0, 0) });
        Content = panel;
        var area = SystemParameters.WorkArea; Left = Math.Max(area.Left, area.Right - Width - 24); Top = Math.Max(area.Top, area.Bottom - Height - 24);
        Closing += HideInsteadOfClosing;
    }
    private void HideInsteadOfClosing(object? sender, CancelEventArgs e) { e.Cancel = true; Hide(); }
    internal void Update(TimerJob job, string action, string remaining, FontFamily font)
    {
        actionText.Text = action + " ПК"; countdown.Text = remaining;
        deadline.Text = $"{job.DueAt.LocalDateTime:dd.MM · HH:mm:ss}"; FontFamily = font;
    }
}
