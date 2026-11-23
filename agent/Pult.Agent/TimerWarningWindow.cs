using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Pult.Agent;

/// <summary>Small always-on-top notice shown shortly before a timer runs, with a cancel button.</summary>
internal sealed class TimerWarningWindow : Window
{
    private readonly TextBlock title = new() { FontSize = 28, Margin = new Thickness(0, 0, 0, 4) };
    private readonly TextBlock countdown = new() { FontSize = 15, Margin = new Thickness(0, 0, 0, 12) };
    public string TimerId { get; }

    public TimerWarningWindow(TimerJob job, Action cancel)
    {
        TimerId = job.Id;
        Title = "Пульт · таймер"; Width = 340; SizeToContent = SizeToContent.Height;
        WindowStyle = WindowStyle.ToolWindow; ResizeMode = ResizeMode.NoResize; Topmost = true; ShowInTaskbar = false;
        ShowActivated = false; // Never steal focus from what the user is typing.
        SetResourceReference(BackgroundProperty, "Base"); SetResourceReference(ForegroundProperty, "Ink"); SetResourceReference(FontFamilyProperty, "Mono");
        title.SetResourceReference(FontFamilyProperty, "Display");
        title.Inlines.Add(new System.Windows.Documents.Run(ActionTitle(job.Action)));
        var cursor = new System.Windows.Documents.Run("_"); cursor.SetResourceReference(System.Windows.Documents.TextElement.ForegroundProperty, "Accent"); title.Inlines.Add(cursor);
        countdown.SetResourceReference(TextBlock.ForegroundProperty, "Sub");
        var hint = new TextBlock { Text = "Сохрани открытые документы или отмени таймер.", TextWrapping = TextWrapping.Wrap, FontSize = 12, Margin = new Thickness(0, 0, 0, 14) };
        hint.SetResourceReference(TextBlock.ForegroundProperty, "Sub");
        var cancelButton = new Button { Content = "Отменить таймер ↗", Margin = new Thickness(0, 0, 8, 0) };
        cancelButton.Style = (Style)System.Windows.Application.Current.FindResource("Primary");
        cancelButton.Click += (_, _) => { cancel(); Close(); };
        var hide = new Button { Content = "Скрыть", MinHeight = 54 };
        hide.Click += (_, _) => Close();
        var buttons = new Grid();
        buttons.ColumnDefinitions.Add(new ColumnDefinition()); buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(hide, 1); buttons.Children.Add(cancelButton); buttons.Children.Add(hide);
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
