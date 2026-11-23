using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Pult.Agent;

/// <summary>Small always-on-top notice shown shortly before a timer runs, with a cancel button.</summary>
internal sealed class TimerWarningWindow : Window
{
    private readonly TextBlock title = new() { FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 6) };
    private readonly TextBlock countdown = new() { FontSize = 30, Margin = new Thickness(0, 0, 0, 12) };
    public string TimerId { get; }

    public TimerWarningWindow(TimerJob job, Action cancel)
    {
        TimerId = job.Id;
        Title = "Пульт · таймер"; Width = 340; SizeToContent = SizeToContent.Height;
        WindowStyle = WindowStyle.ToolWindow; ResizeMode = ResizeMode.NoResize; Topmost = true; ShowInTaskbar = false;
        ShowActivated = false; // Never steal focus from what the user is typing.
        SetResourceReference(BackgroundProperty, "Base"); SetResourceReference(ForegroundProperty, "Ink");
        title.Text = $"{ActionTitle(job.Action)} скоро";
        var hint = new TextBlock { Text = "Сохрани открытые документы или отмени таймер.", TextWrapping = TextWrapping.Wrap, FontSize = 12, Margin = new Thickness(0, 0, 0, 14) };
        hint.SetResourceReference(TextBlock.ForegroundProperty, "Sub");
        var cancelButton = new Button { Content = "Отменить таймер", Padding = new Thickness(12, 8, 12, 8), Margin = new Thickness(0, 0, 8, 0) };
        cancelButton.Style = (Style)System.Windows.Application.Current.FindResource("Primary");
        cancelButton.Click += (_, _) => { cancel(); Close(); };
        var hide = new Button { Content = "Скрыть", Padding = new Thickness(12, 8, 12, 8) };
        hide.Click += (_, _) => Close();
        var buttons = new StackPanel { Orientation = Orientation.Horizontal };
        buttons.Children.Add(cancelButton); buttons.Children.Add(hide);
        var panel = new StackPanel { Margin = new Thickness(20) };
        panel.Children.Add(title); panel.Children.Add(countdown); panel.Children.Add(hint); panel.Children.Add(buttons);
        Content = panel;
        Update(job);
        Loaded += (_, _) =>
        {
            var area = SystemParameters.WorkArea;
            Left = area.Right - ActualWidth - 16; Top = area.Bottom - ActualHeight - 16;
        };
    }

    public void Update(TimerJob job)
    {
        var seconds = Math.Max(0, (int)Math.Ceiling((job.DueAt - DateTimeOffset.UtcNow).TotalSeconds));
        countdown.Text = $"через {seconds} с";
    }

    private static string ActionTitle(string action) => action switch { "restart" => "Перезагрузка", "lock" => "Блокировка", _ => "Выключение" };
}
