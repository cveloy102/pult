using System.ComponentModel;
using System.Diagnostics;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
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
    private readonly System.Windows.Forms.ToolStripMenuItem timerMenu;
    private readonly System.Windows.Forms.ToolStripMenuItem cancelTimerMenu;
    private System.Drawing.Icon? countdownIcon;
    private string? countdownIconKey;
    private TextBlock? timerHistory;
    private bool checkingUpdate;
    private string? shownError;
    private TimerWarningWindow? warning;
    private string? warnedTimer;
    private StackPanel? networkPanel;
    private TextBlock? networkSummary;
    private Button? cancelTimerButton;
    private System.Windows.Controls.Image? qrImage;
    public MainWindow(AgentHost host, bool demo)
    {
        this.host = host; this.demo = demo; InitializeComponent(); ApplyTheme(host.Service.State.Theme);
        // "Как в системе": follow the Windows light/dark switch while the agent runs.
        SystemEvents.UserPreferenceChanged += (_, e) =>
        {
            if (e.Category == UserPreferenceCategory.General && Themes.Normalize(host.Service.State.Theme) == Themes.System)
                Dispatcher.BeginInvoke(() => ApplyTheme(host.Service.State.Theme));
        };
        VersionLabel.Text = $"{AgentHost.Version} · работает в трее";
        refresh = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        using (var iconStream = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/Assets/pult.ico")).Stream)
        using (var sourceIcon = new System.Drawing.Icon(iconStream)) trayIcon = new System.Drawing.Icon(sourceIcon, 32, 32);
        tray = new System.Windows.Forms.NotifyIcon { Text = demo ? "Пульт · демо" : "Пульт", Icon = trayIcon, Visible = true };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Открыть Пульт", null, (_, _) => Dispatcher.Invoke(ShowWindow));
        timerMenu = new System.Windows.Forms.ToolStripMenuItem("Нет активного таймера", null, (_, _) => Dispatcher.Invoke(() => { ShowWindow(); RenderOverview(); }));
        cancelTimerMenu = new System.Windows.Forms.ToolStripMenuItem("Отменить таймер", null, (_, _) => Dispatcher.Invoke(CancelActiveTimer));
        menu.Items.Add(timerMenu); menu.Items.Add(cancelTimerMenu);
        menu.Items.Add("Подключить телефон", null, (_, _) => Dispatcher.Invoke(() => { ShowWindow(); PairPage(); }));
        menu.Items.Add("Проверить обновления", null, (_, _) => Dispatcher.Invoke(() => UpdateClick(this, new RoutedEventArgs())));
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Завершить агент", null, async (_, _) => { tray.Visible = false; refresh.Stop(); tray.Dispose(); countdownIcon?.Dispose(); trayIcon.Dispose(); await ((App)System.Windows.Application.Current).ExitAsync(); });
        tray.ContextMenuStrip = menu; tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowWindow);
        refresh.Tick += (_, _) => Refresh(); refresh.Start();
        Closing += (_, e) => { e.Cancel = true; Hide(); };
        RenderOverview();
    }
    private void ShowWindow() { Show(); WindowState = WindowState.Normal; Activate(); }
    public void Refresh()
    {
        RunningLabel.Text = host.Running ? demo ? "● ДЕМО · без системных команд" : "● АГЕНТ ЗАПУЩЕН" : "Запуск…";
        // Called every second: use the cheap snapshot, not Status() with audio device enumeration.
        var (job, error) = host.Service.Snapshot();
        if (TimerWarning.ShouldShow(job, DateTimeOffset.UtcNow, warnedTimer))
        {
            warnedTimer = job!.Id; warning?.Close();
            var shown = new TimerWarningWindow(job, CancelActiveTimer);
            shown.Closed += (_, _) => { if (warning == shown) warning = null; };
            warning = shown; shown.Show();
        }
        if (warning is not null) { if (job is null || job.Id != warning.TimerId) warning.Close(); else warning.Update(job); }
        timerMenu.Enabled = cancelTimerMenu.Enabled = job is not null;
        timerMenu.Text = job is null ? "Нет активного таймера" : $"{ActionName(job.Action)} · {FormatRemaining(job.DueAt)}";
        tray.Text = job is null ? "Пульт" : $"Пульт · {ActionName(job.Action)} {FormatRemaining(job.DueAt)}";
        var badge = job is null ? null : TrayCountdown.Label(job.DueAt - DateTimeOffset.UtcNow);
        var iconKey = job is null ? null : job.Action + ":" + badge;
        if (iconKey != countdownIconKey)
        {
            var next = job is null ? null : TimerTrayIcon.Create(badge!, job.Action);
            tray.Icon = next ?? trayIcon;
            countdownIcon?.Dispose(); countdownIcon = next; countdownIconKey = iconKey;
        }
        if(timerHistory is not null)
        {
            var history = string.Join("\n", host.Service.TimerHistory.Take(6).Select(item => $"{item.Time.LocalDateTime:dd.MM HH:mm:ss} · {ActionName(item.Action)} · {TimerEventName(item.Event)}"));
            timerHistory.Text = history.Length == 0 ? "Таймеров пока не было." : history;
        }
        if (cancelTimerButton is not null) cancelTimerButton.IsEnabled = job is not null;
        if (timerText is not null)
        {
            timerText.Text = job is null ? "Не задан" : $"{ActionName(job.Action)} · {FormatRemaining(job.DueAt)}";
        }
        if (codeText is not null && DateTimeOffset.UtcNow >= codeExpires) { codeText.Text = "Код истёк"; if (qrImage is not null) qrImage.Opacity = 0.15; }
        if (error != shownError) { shownError = error; if (error is not null) Notice.Text = error; }
        if (!demo && host.Service.State.CheckUpdates && DateTimeOffset.UtcNow >= nextUpdateCheck)
        {
            nextUpdateCheck = DateTimeOffset.UtcNow.AddDays(1); _ = CheckUpdateBackground();
        }
    }
    private static string ActionName(string action) => action switch { "restart" => "Перезагрузка", "lock" => "Блокировка", "shutdown" => "Выключение", _ => "Неизвестное действие" };
    private static string TimerEventName(string action) => action switch { "scheduled" => "запущен", "replaced" => "заменён", "cancelled" => "отменён", "dispatched" => "передача команды Windows", "failed" => "ошибка", "skipped" => "пропущен и отменён", _ => action };
    private static string FormatRemaining(DateTimeOffset date) { var span = date - DateTimeOffset.UtcNow; return span <= TimeSpan.Zero ? "00:00:00" : $"{(int)span.TotalHours:00}:{span.Minutes:00}:{span.Seconds:00}"; }
    private void CancelActiveTimer() { host.Service.CancelTimerLocal(); Notice.Text = "Таймер отменён."; Refresh(); }
    private TextBlock Text(string value, int size = 14, bool muted = false)
    {
        var text = new TextBlock { Text = value, FontSize = size, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 13) };
        text.SetResourceReference(TextBlock.ForegroundProperty, muted ? "Sub" : "Ink"); return text;
    }
    private Button Button(string label, Action action, bool primary = false, string? style = null)
    {
        var button = new Button { Content = label, Margin = new Thickness(0, 0, 0, 8) };
        if (primary || style is not null) button.Style = (Style)FindResource(style ?? "Primary");
        button.Click += (_, _) => { try { action(); } catch (Exception ex) { Notice.Text = ex.Message; } }; return button;
    }
    /// <summary>Screen title in the display face; [cursor] adds the accent "_".</summary>
    private TextBlock Heading(string value, bool cursor = true)
    {
        var title = new TextBlock { FontSize = 28, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 10) };
        title.SetResourceReference(FontFamilyProperty, "Display"); title.SetResourceReference(TextBlock.ForegroundProperty, "Ink");
        title.Inlines.Add(new System.Windows.Documents.Run(value));
        if (cursor) { var mark = new System.Windows.Documents.Run("_"); mark.SetResourceReference(System.Windows.Documents.TextElement.ForegroundProperty, "Accent"); title.Inlines.Add(mark); }
        return title;
    }
    /// <summary>Section heading, e.g. "// История таймеров".</summary>
    private TextBlock Section(string value) { var text = Text(value, 13); text.Margin = new Thickness(0, 12, 0, 10); return text; }
    /// <summary>Adds a card to the page and returns the panel its rows go into.</summary>
    private StackPanel Card(Panel? parent = null)
    {
        var rows = new StackPanel(); var card = new Border { Child = rows };
        card.SetResourceReference(StyleProperty, "Card"); (parent ?? Page).Children.Add(card); return rows;
    }
    /// <summary>Adds a child to a card, with a hairline above every row but the first.</summary>
    private static void AddRow(StackPanel card, UIElement content, Thickness? padding = null)
    {
        var row = new Border { Child = content, Padding = padding ?? new Thickness(0, 15, 0, 15), BorderThickness = new Thickness(0, card.Children.Count == 0 ? 0 : 1, 0, 0) };
        row.SetResourceReference(Border.BorderBrushProperty, "Line"); card.Children.Add(row);
    }
    private void Row(StackPanel card, string label, TextBlock value, Action? open = null)
    {
        var grid = new Grid { Background = Brushes.Transparent }; grid.ColumnDefinitions.Add(new()); grid.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Auto) });
        var caption = Text(label, 12, true); caption.Margin = new(); caption.VerticalAlignment = VerticalAlignment.Center; grid.Children.Add(caption);
        value.Margin = new Thickness(12, 0, 0, 0); value.TextAlignment = TextAlignment.Right; Grid.SetColumn(value, 1); grid.Children.Add(value);
        if (open is not null) { grid.Cursor = Cursors.Hand; grid.MouseLeftButtonUp += (_, _) => open(); }
        AddRow(card, grid);
    }
    private void Clear(string name)
    {
        Page.Children.Clear(); PageScroll.ScrollToTop(); timerText = null; timerHistory = null; codeText = null; networkPanel = null; networkSummary = null; cancelTimerButton = null; qrImage = null; Notice.Text = "";
        var tabs = new (Button Tab, string[] Pages)[] { (OverviewTab, ["overview"]), (PhonesTab, ["phones", "pair"]), (NetworkTab, ["network"]), (SettingsTab, ["settings"]) };
        foreach (var (tab, pages) in tabs) tab.Tag = pages.Contains(name) ? "on" : null;
    }
    private void RenderOverview()
    {
        Clear("overview"); Page.Children.Add(Heading(host.Service.State.Name, false)); Page.Children.Add(Text("Агент принимает команды только от привязанных телефонов.", 13, true));
        var card = Card();
        Row(card, "Адрес ПК", Text(string.Join(" / ", AgentHost.Addresses), 13)); Row(card, "Порт HTTPS", Text(Protocol.Port.ToString(), 13));
        Row(card, "Телефоны", Text($"Привязано: {host.Service.Phones.Length}", 13));
        timerText = Text("Не задан", 13); Row(card, "Таймер", timerText);
        networkSummary = Text("Проверяю…", 13); Row(card, "Сеть", networkSummary, RenderNetwork);
        Page.Children.Add(Button("Подключить телефон ↗", PairPage, true));
        cancelTimerButton = Button("Отменить таймер на ПК ×", CancelActiveTimer); Page.Children.Add(cancelTimerButton);
        Page.Children.Add(Section("// История таймеров"));
        timerHistory = Text("", 12, true); timerHistory.Margin = new(); AddRow(Card(), timerHistory);
        Refresh(); RunNetworkCheck();
    }
    private void RenderNetwork()
    {
        Clear("network"); Page.Children.Add(Heading("Сеть"));
        Page.Children.Add(Text("Тип сети и правила брандмауэра Windows. Тот же результат записывает Pult.Agent.exe --network-check.", 13, true));
        networkPanel = new StackPanel(); Page.Children.Add(networkPanel);
        Page.Children.Add(Text("Не открывайте TCP 47591 и UDP 47592 в интернет: Пульт работает только в домашней сети.", 12, true));
        RunNetworkCheck();
    }
    private void PairPage()
    {
        Clear("pair"); var (code, expires) = host.Service.BeginPairing(); codeExpires = expires;
        Page.Children.Add(Heading("Подключить телефон"));
        Page.Children.Add(Text("Отсканируй QR-код в приложении: Настройки → «Сканировать QR-код». Отпечаток и код проверятся сами.", 13, true));
        qrImage = new System.Windows.Controls.Image { Source = QrImage(PairingLink.Create(AgentHost.Addresses, Protocol.Port, host.Fingerprint, code, host.Service.State.Name)), Width = 240, Height = 240, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 2, 0, 16) };
        RenderOptions.SetBitmapScalingMode(qrImage, BitmapScalingMode.NearestNeighbor); Page.Children.Add(qrImage);
        Page.Children.Add(Text("Без камеры: в приложении нажми «Найти ПК» или введи адрес ниже. Сверь отпечаток, затем введи код.", 13, true));
        var addresses = new TextBox { Text = string.Join(" / ", AgentHost.Addresses.Select(x => $"https://{x}:{Protocol.Port}")), IsReadOnly = true, Margin = new Thickness(0, 4, 0, 18) }; Page.Children.Add(addresses);
        codeText = Text(code[..3] + " " + code[3..], 39); codeText.SetResourceReference(FontFamilyProperty, "Display"); codeText.Margin = new Thickness(0, 0, 0, 4);
        Page.Children.Add(codeText); Page.Children.Add(Text("Одноразовый код действует 2 минуты.", 12, true));
        Page.Children.Add(Text("Отпечаток сертификата", 12, true));
        var fingerprint = new TextBox { Text = string.Join(" ", Enumerable.Range(0, 16).Select(i => host.Fingerprint.Substring(i * 4, 4))), IsReadOnly = true, TextWrapping = TextWrapping.Wrap, FontSize = 12, Margin = new Thickness(0, 0, 0, 15) }; Page.Children.Add(fingerprint);
        Page.Children.Add(Button("Создать новый код ↻", PairPage));
    }
    private static BitmapImage QrImage(string text)
    {
        using var generator = new QRCoder.QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCoder.QRCodeGenerator.ECCLevel.M);
        var image = new BitmapImage(); image.BeginInit(); image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = new MemoryStream(new QRCoder.PngByteQRCode(data).GetGraphic(10)); image.EndInit(); image.Freeze();
        return image;
    }
    /// <summary>Called once the HTTPS server is listening: the first overview is drawn before startup finishes.</summary>
    public void AgentStarted() => RunNetworkCheck();
    /// <summary>Runs the network check and shows it on the network tab, the overview row and the tab badge.</summary>
    private async void RunNetworkCheck()
    {
        var panel = networkPanel; var summary = networkSummary;
        if (panel is not null) { panel.Children.Clear(); panel.Children.Add(Text("Проверяю сеть и брандмауэр…", 12, true)); }
        var checks = await Task.Run(() => NetworkInspector.Run(host.Running, host.DiscoveryRunning));
        var problem = checks.Any(x => x.Level != "ok");
        NetworkBadge.Text = problem ? "  !" : "";
        if (summary is not null && networkSummary == summary)
        {
            summary.Inlines.Clear();
            var dot = new System.Windows.Documents.Run(problem ? "!" : "●"); dot.SetResourceReference(System.Windows.Documents.TextElement.ForegroundProperty, problem ? "Accent" : "Online");
            summary.Inlines.Add(dot); summary.Inlines.Add(new System.Windows.Documents.Run(problem ? " Нужна проверка →" : " В порядке →"));
        }
        if (panel is null || networkPanel != panel) return;
        panel.Children.Clear();
        var card = Card(panel);
        foreach (var check in checks)
        {
            var grid = new Grid(); grid.ColumnDefinitions.Add(new() { Width = new GridLength(24) }); grid.ColumnDefinitions.Add(new());
            var mark = Text(check.Level == "ok" ? "●" : check.Level == "warn" ? "▲" : "!", 13); mark.Margin = new();
            mark.SetResourceReference(TextBlock.ForegroundProperty, check.Level == "ok" ? "Online" : "Accent"); grid.Children.Add(mark);
            var lines = new StackPanel(); Grid.SetColumn(lines, 1); grid.Children.Add(lines);
            var title = Text(check.Title, 13); title.Margin = new(); lines.Children.Add(title);
            if (check.Level != "ok") { var detail = Text(check.Detail, 12, true); detail.Margin = new Thickness(0, 3, 0, 0); lines.Children.Add(detail); }
            AddRow(card, grid, new Thickness(0, 13, 0, 13));
        }
        if (checks.Any(x => x.Fix == "firewall")) panel.Children.Add(Button("Разрешить в брандмауэре ↗", AllowFirewall, true));
        if (checks.Any(x => x.Fix == "network")) panel.Children.Add(Button("Открыть параметры сети →", NetworkInspector.OpenNetworkSettings));
        panel.Children.Add(Button("Проверить снова ↻", RunNetworkCheck));
    }
    private async void AllowFirewall()
    {
        if (MessageBox.Show("Windows попросит права администратора.\n\nПравила брандмауэра для Пульта будут заменены одним разрешающим правилом для частных и доменных сетей. Общедоступные сети остаются закрытыми.", "Пульт", MessageBoxButton.OKCancel) != MessageBoxResult.OK) return;
        var allowed = await Task.Run(NetworkInspector.AllowInFirewall);
        Notice.Text = allowed ? "Разрешение в брандмауэре добавлено." : "Разрешение не добавлено: запрос администратора отклонён или не выполнен.";
        RunNetworkCheck();
    }
    private void RenderPhones()
    {
        Clear("phones"); Page.Children.Add(Heading("Привязанные телефоны"));
        if (host.Service.Phones.Length == 0) Page.Children.Add(Text("Пока нет устройств. Подключи телефон с помощью одноразового кода.", 13, true));
        else
        {
            var card = Card();
            foreach (var phone in host.Service.Phones)
            {
                var grid = new Grid(); grid.ColumnDefinitions.Add(new()); grid.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Auto) });
                var lines = new StackPanel { VerticalAlignment = VerticalAlignment.Center }; grid.Children.Add(lines);
                var name = Text(phone.Name, 15); name.Margin = new(); lines.Children.Add(name);
                var paired = Text($"Привязан {phone.PairedAt.LocalDateTime:g}", 12, true); paired.Margin = new Thickness(0, 3, 0, 0); lines.Children.Add(paired);
                var revoke = Button("Отозвать доступ ×", () => { if (MessageBox.Show($"Отозвать доступ телефона «{phone.Name}»?", "Пульт", MessageBoxButton.YesNo) == MessageBoxResult.Yes) { host.Service.Revoke(phone.Id); RenderPhones(); } }, style: "Quiet");
                revoke.Margin = new(); revoke.VerticalAlignment = VerticalAlignment.Center; Grid.SetColumn(revoke, 1); grid.Children.Add(revoke);
                AddRow(card, grid, new Thickness(0, 12, 0, 12));
            }
        }
        Page.Children.Add(Button("Подключить телефон ↗", PairPage, true));
    }
    private void RenderSettings()
    {
        Clear("settings"); Page.Children.Add(Heading("Настройки")); Page.Children.Add(Text("Имя компьютера", 12, true));
        var name = new TextBox { Text = host.Service.State.Name, MaxLength = 80, Margin = new Thickness(0, 0, 0, 8) }; Page.Children.Add(name);
        var saveName = Button("Сохранить имя компьютера", () => { }); Page.Children.Add(saveName);
        Page.Children.Add(Section("// Тема оформления"));
        var themes = new UniformGrid { Columns = 3, Margin = new Thickness(0, 0, 0, 4) };
        var current = Themes.Normalize(host.Service.State.Theme);
        foreach (var (key, label) in new[] { (Themes.System, "Как в системе"), (Themes.Dark, "Тёмная"), (Themes.Light, "Светлая") })
        {
            var option = Button(label, () =>
            {
                host.Service.SaveSettings(host.Service.State.Name, key, host.Service.State.CheckUpdates); ApplyTheme(key);
                foreach (Button choice in themes.Children) choice.Tag = Equals(choice.CommandParameter, key) ? "on" : null;
                Notice.Text = $"Тема «{label}» применена.";
            }, style: "Option");
            option.CommandParameter = key; option.Tag = key == current ? "on" : null;
            option.HorizontalContentAlignment = HorizontalAlignment.Left; option.Margin = new Thickness(0, 0, key == Themes.Light ? 0 : 6, 0);
            themes.Children.Add(option);
        }
        Page.Children.Add(themes);
        Page.Children.Add(Section("// Запуск и обновления"));
        var switches = Card();
        var updates = new CheckBox { Content = "Проверять обновления", IsChecked = host.Service.State.CheckUpdates }; AddRow(switches, updates, new Thickness(0, 2, 0, 2));
        var startup = new CheckBox { Content = "Запускать вместе с Windows", IsChecked = IsStartupEnabled() }; AddRow(switches, startup, new Thickness(0, 2, 0, 2));
        if(!demo) Page.Children.Add(Text("Запуск через Планировщик задач: при входе в твой профиль, через 15 секунд, без прав администратора.", 12, true));
        updates.Click += (_, _) =>
        {
            try { host.Service.SaveSettings(host.Service.State.Name, host.Service.State.Theme, updates.IsChecked == true); Notice.Text = "Проверка обновлений настроена."; }
            catch (Exception ex) { updates.IsChecked = host.Service.State.CheckUpdates; Notice.Text = $"Не удалось сохранить настройку: {ex.Message}"; }
        };
        startup.Click += (_, _) =>
        {
            try { SetStartup(startup.IsChecked == true); startup.IsChecked = IsStartupEnabled(); Notice.Text = startup.IsChecked == true ? "Автозапуск включён." : "Автозапуск выключен."; }
            catch (Exception ex) { startup.IsChecked = IsStartupEnabled(); Notice.Text = $"Не удалось изменить автозапуск: {ex.Message}"; }
        };
        saveName.Click += (_, _) => { host.Service.SaveSettings(name.Text, host.Service.State.Theme, updates.IsChecked == true); Notice.Text = "Имя компьютера сохранено."; };
        Page.Children.Add(Text("Закрытие окна сворачивает Пульт в трей. Для завершения используй меню значка в трее.", 12, true));
    }
    /// <summary>Windows "app mode": AppsUseLightTheme is 0 for dark; missing means light.</summary>
    private static bool WindowsUsesLightTheme()
    {
        try { return Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) is not int value || value != 0; }
        catch { return true; }
    }
    /// <summary>Swaps the color tokens of the design code; fonts and corners are the same in both themes.</summary>
    private static void ApplyTheme(string theme)
    {
        var colors = Themes.Resolve(theme, WindowsUsesLightTheme()) == Themes.Light
            ? new[] { "#F5F2EB", "#EEEBE4", "#E7E2D8", "#191918", "#68645D", "#D5D0C7", "#CF332D", "#FFFFFF", "#296744", "#D5D0C7", "#999389", "#68645D", "#FFFFFF" }
            : new[] { "#101010", "#181818", "#242220", "#E8E4D9", "#AAA79F", "#353535", "#F0443E", "#101010", "#8CDBAD", "#303030", "#79766E", "#E8E4D9", "#E8E4D9" };
        var names = new[] { "Base", "Surface", "Plate", "Ink", "Sub", "Line", "Accent", "AccentInk", "Online", "SwitchOff", "SwitchBorder", "ThumbOff", "ThumbOn" };
        for (var i = 0; i < names.Length; i++) System.Windows.Application.Current.Resources[names[i]] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
    }
    private bool IsStartupEnabled() => demo ? demoStartup : StartupRegistration.IsEnabled;
    private void SetStartup(bool enabled)
    {
        if (demo) { demoStartup = enabled; return; }
        StartupRegistration.Set(enabled);
    }
    private void OverviewClick(object sender, RoutedEventArgs e) => RenderOverview();
    private void PhonesClick(object sender, RoutedEventArgs e) => RenderPhones();
    private void NetworkClick(object sender, RoutedEventArgs e) => RenderNetwork();
    private void SettingsClick(object sender, RoutedEventArgs e) => RenderSettings();
    private async void UpdateClick(object sender, RoutedEventArgs e)
    {
        if(checkingUpdate) return;
        checkingUpdate = true;
        Notice.Text = "Проверяю релизы агента…";
        try
        {
            using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            var release = await new ReleaseClient(http, AgentHost.Version).FindAsync(host.Service.State.ReleaseRepository, "agent");
            if (release is null || release.Version <= new Version(AgentHost.Version)) { Notice.Text = "Установлена актуальная версия агента."; return; }
            if(host.Service.Snapshot().Timer is not null) { Notice.Text = "Перед обновлением отмени активный таймер."; return; }
            var asset = AgentUpdates.SelectInstaller(release, host.Service.State.ReleaseRepository);
            if(asset is null)
            {
                if(MessageBox.Show($"Доступен агент {release.Version}, но проверенного установщика в релизе нет. Открыть страницу релиза?", "Пульт", MessageBoxButton.YesNo) == MessageBoxResult.Yes) Process.Start(new ProcessStartInfo(release.Url) { UseShellExecute = true });
                return;
            }
            if (MessageBox.Show($"Доступен Пульт для Windows {release.Version}.\nСкачать и запустить установщик обновления?\n\nАгент завершится перед установкой. Привязка телефона, сертификат и настройки сохранятся. По завершении установки Пульт запустится снова.", "Обновление Пульта", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            Notice.Text = "Скачиваю и проверяю установщик…";
            var installer = await AgentUpdates.Download(asset, release, host.Service.State.ReleaseRepository);
            if(host.Service.Snapshot().Timer is not null) { Notice.Text = "Появился активный таймер. Отмени его перед обновлением."; return; }
            Process.Start(new ProcessStartInfo(installer) { UseShellExecute = true });
            await ((App)System.Windows.Application.Current).ExitAsync();
            Notice.Text = $"Доступна версия {release.Version}.";
        }
        catch { Notice.Text = "Не удалось проверить обновления. Управление по локальной сети продолжает работать."; }
        finally { checkingUpdate = false; }
    }
    private async Task CheckUpdateBackground()
    {
        try
        {
            using var http = new System.Net.Http.HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            var release = await new ReleaseClient(http, AgentHost.Version).FindAsync(host.Service.State.ReleaseRepository, "agent");
            if (release is not null && release.Version > new Version(AgentHost.Version))
            {
                Notice.Text = $"Доступен Пульт {release.Version}. Открой «Обновления».";
                tray.ShowBalloonTip(5000, "Обновление Пульта", $"Доступен агент Windows {release.Version}.", System.Windows.Forms.ToolTipIcon.Info);
            }
        }
        catch { /* Offline LAN control must not be interrupted by update checks. */ }
    }
}
