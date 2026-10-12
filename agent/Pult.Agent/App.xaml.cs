using System.Windows;

namespace Pult.Agent;

public partial class App : System.Windows.Application
{
    private Mutex? mutex;
    private AgentHost? host;
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var args = e.Args;
        var dataIndex = Array.IndexOf(args, "--data-dir");
        var directory = dataIndex >= 0 && dataIndex + 1 < args.Length ? Path.GetFullPath(args[dataIndex + 1]) : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pult");
        var testing = args.Contains("--integration-test");
        var demo = args.Contains("--demo");
        Directory.CreateDirectory(directory);
        var startupLog = Path.Combine(directory, "startup.log");
        void Log(string message) { try { File.AppendAllText(startupLog, $"{DateTimeOffset.Now:O} {message}\n"); } catch { } }
        Log($"Starting {AgentHost.Version}; tray={args.Contains("--tray")}; path={Environment.ProcessPath}");
        if (!testing)
        {
            mutex = new Mutex(true, demo ? @"Local\Pult.Agent.Demo" : @"Local\Pult.Agent", out var created);
            if (!created) { Log("Existing agent detected."); if(!args.Contains("--tray"))MessageBox.Show("Пульт уже запущен. Открой его через значок в трее.", "Пульт"); Shutdown(); return; }
        }
        try
        {
            host = new AgentHost(directory, args.Contains("--demo") || testing);
            if (testing)
            {
                await IntegrationCheck.Run(host, directory); await host.DisposeAsync(); host = null; Shutdown(); return;
            }
            var window = new MainWindow(host, args.Contains("--demo")); MainWindow = window;
            if (!args.Contains("--tray")) window.Show();
            await host.StartAsync(loopbackOnly: demo, port: demo ? 47598 : Protocol.Port); window.Refresh(); Log("Agent ready.");
        }
        catch (Exception ex)
        {
            Directory.CreateDirectory(directory); File.WriteAllText(Path.Combine(directory, "startup-error.txt"), ex.ToString());
            if (!testing) MessageBox.Show($"Не удалось запустить Пульт.\n{ex.Message}", "Пульт");
            Shutdown(1);
        }
    }
    public async Task ExitAsync()
    {
        if (host is not null) { await host.DisposeAsync(); host = null; }
        mutex?.Dispose(); mutex = null; Shutdown();
    }
}
