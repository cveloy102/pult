using System.ComponentModel;
using System.Diagnostics;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace Pult.Agent;

public partial class MainWindow : System.Windows.Window
{
    private readonly AgentHost host;
    private readonly bool demo;
    private readonly System.Windows.Forms.NotifyIcon tray;
    private readonly System.Drawing.Icon trayIcon;
    private bool demoStartup;
    private readonly DispatcherTimer refresh;
    private TextBlock? timerText;
    private TextBlock? codeText;
    private DateTimeOffset codeExpires;
    private DateTimeOffset nextUpdateCheck = DateTimeOffset.UtcNow;
    public MainWindow(AgentHost host, bool demo)
    {
        this.host = host; this.demo = demo; InitializeComponent(); ApplyTheme(host.Service.State.Theme);
        VersionLabel.Text = $"{AgentHost.Version} · работает в трее";
        refresh = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        using (var iconStream = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/Assets/pult.ico")).Stream)
        using (var sourceIcon = new System.Drawing.Icon(iconStream)) trayIcon = new System.Drawing.Icon(sourceIcon, 32, 32);
        tray = new System.Windows.Forms.NotifyIcon { Text = demo ? "Пульт · демо" : "Пульт", Icon = trayIcon, Visible = true };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Открыть Пульт", null, (_, _) => Dispatcher.Invoke(ShowWindow));
        menu.Items.Add("Подключить телефон", null, (_, _) => Dispatcher.Invoke(() => { ShowWindow(); PairPage(); }));
        menu.Items.Add("Проверить обновления", null, (_, _) => Dispatcher.Invoke(() => UpdateClick(this, new RoutedEventArgs())));
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Завершить агент", null, async (_, _) => { tray.Visible = false; refresh.Stop(); tray.Dispose(); trayIcon.Dispose(); await ((App)System.Windows.Application.Current).ExitAsync(); });
        tray.ContextMenuStrip = menu; tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowWindow);
        refresh.Tick += (_, _) => Refresh(); refresh.Start();
        Closing += (_, e) => { e.Cancel = true; Hide(); };
        RenderOverview();
    }
    private void ShowWindow() { Show(); WindowState = WindowState.Normal; Activate(); }
    public void Refresh()
    {
        RunningLabel.Text = host.Running ? demo ? "● ДЕМО · без системных команд" : "● АГЕНТ ЗАПУЩЕН" : "Запуск…";
        var status = host.Service.Status(AgentHost.Version);
        if (timerText is not null)
        {
            var job = status.Timer;
            timerText.Text = job is null ? "Не задан" : $"{ActionName(job.Action)} · {FormatRemaining(job.DueAt)}";
        }
        if (codeText is not null && DateTimeOffset.UtcNow >= codeExpires) codeText.Text = "Код истёк";
        if (status.Error is not null) Notice.Text = status.Error;
        if (!demo && host.Service.State.CheckUpdates && DateTimeOffset.UtcNow >= nextUpdateCheck)
        {
            nextUpdateCheck = DateTimeOffset.UtcNow.AddDays(1); _ = CheckUpdateBackground();
        }
    }
    private static string ActionName(string action) => action switch { "restart" => "Перезагрузка", "lock" => "Блокировка", _ => "Выключение" };
    private static string FormatRemaining(DateTimeOffset date) { var span = date - DateTimeOffset.UtcNow; return span <= TimeSpan.Zero ? "00:00" : $"{(int)span.TotalMinutes:00}:{span.Seconds:00}"; }
    private TextBlock Text(string value, int size = 14, bool muted = false)
    {
        var text = new TextBlock { Text = value, FontSize = size, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 13) };
        text.SetResourceReference(TextBlock.ForegroundProperty, muted ? "Sub" : "Ink"); return text;
    }
    private Button Button(string label, Action action, bool primary = false)
    {
        var button = new Button { Content = label, Margin = new Thickness(0, 8, 0, 7), HorizontalContentAlignment = HorizontalAlignment.Center };
        if (primary) button.Style = (Style)FindResource("Primary"); button.Click += (_, _) => { try { action(); } catch (Exception ex) { Notice.Text = ex.Message; } }; return button;
    }
    private void Clear(string name) { Page.Children.Clear(); PageScroll.ScrollToTop(); timerText = null; codeText = null; Notice.Text = ""; }
    private void Row(string label, TextBlock value)
    {
        var grid = new Grid { Margin = new Thickness(0, 9, 0, 9) }; grid.ColumnDefinitions.Add(new()); grid.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Auto) });
        var caption = Text(label, 13, true); caption.Margin = new(); grid.Children.Add(caption); value.Margin = new(); Grid.SetColumn(value, 1); grid.Children.Add(value); Page.Children.Add(grid);
    }
    private void RenderOverview()
    {
        Clear("overview"); Page.Children.Add(Text(host.Service.State.Name, 26)); Page.Children.Add(Text("Агент принимает команды только от привязанных телефонов.", 13, true));
        Row("Адрес ПК", Text(string.Join(" / ", AgentHost.Addresses), 13)); Row("Порт HTTPS", Text(Protocol.Port.ToString(), 13));
        Row("Телефоны", Text($"Привязано: {host.Service.Phones.Length}", 13));
        timerText = Text("Не задан", 13); Row("Таймер", timerText);
        Page.Children.Add(Button("Подключить телефон", PairPage, true));
        Page.Children.Add(Button("Отменить таймер на ПК", () =>
        {
            host.Service.CancelTimerLocal();
            Notice.Text = "Таймер отменён.";
        })); Refresh();
    }
    private void PairPage()
    {
        Clear("pair"); var (code, expires) = host.Service.BeginPairing(); codeExpires = expires;
        Page.Children.Add(Text("Подключить телефон", 25)); Page.Children.Add(Text("В приложении нажми «Найти ПК» или введи адрес ниже. Сверь отпечаток, затем введи этот код.", 13, true));
        var addresses = new TextBox { Text = string.Join(" / ", AgentHost.Addresses.Select(x => $"https://{x}:{Protocol.Port}")), IsReadOnly = true, Margin = new Thickness(0, 4, 0, 18) }; Page.Children.Add(addresses);
        codeText = Text(code[..3] + " " + code[3..], 39); Page.Children.Add(codeText); Page.Children.Add(Text("Одноразовый код действует 2 минуты.", 12, true));
        Page.Children.Add(Text("Отпечаток сертификата", 12, true));
        var fingerprint = new TextBox { Text = string.Join(" ", Enumerable.Range(0, 16).Select(i => host.Fingerprint.Substring(i * 4, 4))), IsReadOnly = true, TextWrapping = TextWrapping.Wrap, FontSize = 12, Margin = new Thickness(0, 0, 0, 15) }; Page.Children.Add(fingerprint);
        Page.Children.Add(Button("Создать новый код", PairPage));
    }
    private void RenderPhones()
    {
        Clear("phones"); Page.Children.Add(Text("Привязанные телефоны", 25));
        if (host.Service.Phones.Length == 0) Page.Children.Add(Text("Пока нет устройств. Подключи телефон с помощью одноразового кода.", 14, true));
        foreach (var phone in host.Service.Phones)
        {
            Page.Children.Add(Text(phone.Name, 18)); Page.Children.Add(Text($"Привязан {phone.PairedAt.LocalDateTime:g}", 12, true));
            Page.Children.Add(Button("Отозвать доступ", () => { if (MessageBox.Show($"Отозвать доступ телефона «{phone.Name}»?", "Пульт", MessageBoxButton.YesNo) == MessageBoxResult.Yes) { host.Service.Revoke(phone.Id); RenderPhones(); } }));
        }
        Page.Children.Add(Button("Подключить телефон", PairPage, true));
    }
    private void RenderSettings()
    {
        Clear("settings"); Page.Children.Add(Text("Настройки", 25)); Page.Children.Add(Text("Имя компьютера", 12, true));
        var name = new TextBox { Text = host.Service.State.Name, MaxLength = 80, Margin = new Thickness(0, 0, 0, 18) }; Page.Children.Add(name);
        Page.Children.Add(Text("Тема оформления", 14));
        var themes = new UniformGrid { Columns = 3 };
        foreach (var (key, label) in new[] { ("signal", "Сигнал"), ("quiet", "Тихий"), ("orbit", "Орбита") })
        {
            var button = Button(label, () => { host.Service.SaveSettings(name.Text, key, host.Service.State.CheckUpdates); ApplyTheme(key); foreach (Button choice in themes.Children) choice.Style = (Style)FindResource(choice.Tag?.ToString() == key ? "Primary" : typeof(Button)); Notice.Text = $"Тема «{label}» применена."; }, key == host.Service.State.Theme);
            button.Tag = key; button.Margin = new Thickness(0, 4, 8, 20); themes.Children.Add(button);
        }
        Page.Children.Add(themes);
        var updates = new CheckBox { Content = "Проверять обновления", IsChecked = host.Service.State.CheckUpdates }; Page.Children.Add(updates);
        var startup = new CheckBox { Content = "Запускать вместе с Windows", IsChecked = IsStartupEnabled() }; Page.Children.Add(startup);
        updates.Click += (_, _) =>
        {
            try { host.Service.SaveSettings(host.Service.State.Name, host.Service.State.Theme, updates.IsChecked == true); Notice.Text = "Проверка обновлений настроена."; }
            catch (Exception ex) { updates.IsChecked = host.Service.State.CheckUpdates; Notice.Text = $"Не удалось сохранить настройку: {ex.Message}"; }
        };
        startup.Click += (_, _) =>
        {
            try { SetStartup(startup.IsChecked == true); Notice.Text = startup.IsChecked == true ? "Автозапуск включён." : "Автозапуск выключен."; }
            catch (Exception ex) { startup.IsChecked = IsStartupEnabled(); Notice.Text = $"Не удалось изменить автозапуск: {ex.Message}"; }
        };
        Page.Children.Add(Button("Сохранить имя компьютера", () => { host.Service.SaveSettings(name.Text, host.Service.State.Theme, updates.IsChecked == true); Notice.Text = "Имя компьютера сохранено."; }, true));
        Page.Children.Add(Text("Закрытие окна сворачивает Пульт в трей. Для завершения используй меню значка в трее.", 12, true));
    }
    private void ApplyTheme(string theme)
    {
        var colors = theme switch
        {
            "quiet" => new[] { "#F6F3EC", "#EBE7DF", "#272C29", "#626B63", "#D8DCD3", "#425749", "#FFFDF6" },
            "orbit" => new[] { "#121724", "#1C2433", "#F1F4FB", "#A7B4CC", "#354259", "#B5CEFF", "#172642" },
            _ => new[] { "#101011", "#19191A", "#EEEBDF", "#A4A29A", "#343433", "#F04E48", "#111111" }
        };
        var names = new[] { "Base", "Surface", "Ink", "Sub", "Line", "Accent", "AccentInk" };
        for (var i = 0; i < names.Length; i++) System.Windows.Application.Current.Resources[names[i]] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        System.Windows.Application.Current.Resources["Radius"] = new CornerRadius(theme == "signal" ? 3 : 18);
        FontFamily = new FontFamily(theme == "signal" ? "Consolas" : "Segoe UI");
    }
    private bool IsStartupEnabled() { if (demo) return demoStartup; using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"); return key?.GetValue("Pult") is string; }
    private void SetStartup(bool enabled)
    {
        if (demo) { demoStartup = enabled; return; }
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (enabled) key.SetValue("Pult", $"\"{Environment.ProcessPath}\" --tray"); else key.DeleteValue("Pult", false);
    }
    private void OverviewClick(object sender, RoutedEventArgs e) => RenderOverview();
    private void PhonesClick(object sender, RoutedEventArgs e) => RenderPhones();
    private void SettingsClick(object sender, RoutedEventArgs e) => RenderSettings();
    private async void UpdateClick(object sender, RoutedEventArgs e)
    {
        Notice.Text = "Проверяю релизы агента…";
        try
        {
            using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            var release = await new ReleaseClient(http).FindAsync(host.Service.State.ReleaseRepository, "agent");
            if (release is null || release.Version <= new Version(AgentHost.Version)) { Notice.Text = "Установлена актуальная версия агента."; return; }
            if (MessageBox.Show($"Доступен Пульт для Windows {release.Version}.\nОткрыть релиз для скачивания?\n\nПри обновлении замени файлы агента. Настройки останутся в профиле Windows.", "Обновление Пульта", MessageBoxButton.YesNo) == MessageBoxResult.Yes)
                Process.Start(new ProcessStartInfo(release.Url) { UseShellExecute = true });
            Notice.Text = $"Доступна версия {release.Version}.";
        }
        catch { Notice.Text = "Не удалось проверить обновления. Управление по локальной сети продолжает работать."; }
    }
    private async Task CheckUpdateBackground()
    {
        try
        {
            using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            var release = await new ReleaseClient(http).FindAsync(host.Service.State.ReleaseRepository, "agent");
            if (release is not null && release.Version > new Version(AgentHost.Version))
            {
                Notice.Text = $"Доступен Пульт {release.Version}. Открой «Обновления».";
                tray.ShowBalloonTip(5000, "Обновление Пульта", $"Доступен агент Windows {release.Version}.", System.Windows.Forms.ToolTipIcon.Info);
            }
        }
        catch { /* Offline LAN control must not be interrupted by update checks. */ }
    }
}
