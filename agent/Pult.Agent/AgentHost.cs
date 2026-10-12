using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Pult.Agent;

public sealed class AgentHost : IAsyncDisposable
{
    public static readonly string Version = typeof(AgentHost).Assembly.GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
        .Cast<System.Reflection.AssemblyInformationalVersionAttribute>().FirstOrDefault()?.InformationalVersion.Split('+')[0] ?? "0.1.0";
    private WebApplication? server;
    private UdpClient? discovery;
    private readonly CancellationTokenSource cancellation = new();
    private Task? timerTask;
    public AgentService Service { get; }
    private readonly X509Certificate2 certificate;
    public string Fingerprint { get; }
    public bool Running => server is not null;
    public AgentInfo Info => new(Service.State.Name, Version, Protocol.Version, Protocol.Capabilities, Fingerprint);
    public static string[] Addresses => NetworkInterface.GetAllNetworkInterfaces()
        .Where(x => x.OperationalStatus == OperationalStatus.Up && x.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(x => x.GetIPProperties().UnicastAddresses).Select(x => x.Address)
        .Where(x => x.AddressFamily == AddressFamily.InterNetwork && IsLocal(x)).Select(x => x.ToString()).Distinct().ToArray();

    public AgentHost(string directory, bool demo)
    {
        Directory.CreateDirectory(directory);
        Service = new(new FileStateStore(Path.Combine(directory, "settings.json")), new WindowsComputer(demo));
        certificate = CertificateStore.Load(directory);
        Fingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData));
    }
    public async Task StartAsync(bool loopbackOnly = false, int port = Protocol.Port)
    {
        if (server is not null) return;
        var builder = WebApplication.CreateSlimBuilder(); builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.Limits.MaxRequestBodySize = 4096;
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
            options.Limits.MaxConcurrentConnections = 64;
            options.Listen(loopbackOnly ? IPAddress.Loopback : IPAddress.Any, port, listen => { listen.Protocols = HttpProtocols.Http1; listen.UseHttps(certificate); });
        });
        builder.Services.ConfigureHttpJsonOptions(options => options.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase);
        var app = builder.Build();
        app.Use(async (context, next) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (!IsLocal(context.Connection.RemoteIpAddress) || context.Request.Headers.ContainsKey("Origin")) { context.Response.StatusCode = 403; return; }
            try { await next(context); }
            catch (RequestException ex) { context.Response.StatusCode = ex.Status; await context.Response.WriteAsJsonAsync(new { error = ex.Message }); }
            catch (Microsoft.AspNetCore.Http.BadHttpRequestException) { context.Response.StatusCode = 400; await context.Response.WriteAsJsonAsync(new { error = "Некорректный запрос." }); }
            catch (Exception) { context.Response.StatusCode = 500; await context.Response.WriteAsJsonAsync(new { error = "Не удалось выполнить команду. Проверь состояние агента." }); }
        });
        app.MapGet("/api/info", () => Info);
        app.MapPost("/api/pair", (PairRequest request, HttpContext context) => { RequireJson(context); return Service.Pair(request, context.Connection.RemoteIpAddress!.ToString(), Info); });
        app.MapGet("/api/status", (HttpContext context) => { Authenticate(context); return Service.Status(Version); });
        app.MapPost("/api/command", (Command command, HttpContext context) => { RequireJson(context); return Service.Execute(Authenticate(context), command); });
        try { await app.StartAsync(); server = app; }
        catch { await app.DisposeAsync(); throw; }
        timerTask = Task.Run(async () =>
        {
            using var ticker = new PeriodicTimer(TimeSpan.FromSeconds(1));
            try { while (await ticker.WaitForNextTickAsync(cancellation.Token)) { try { Service.Tick(); } catch { /* Failed persistence must not execute a timer. Retry the tick. */ } } }
            catch (OperationCanceledException) { }
        });
        if (!loopbackOnly)
        {
            try { discovery = new UdpClient(new IPEndPoint(IPAddress.Any, Protocol.DiscoveryPort)); _ = DiscoverAsync(); }
            catch (SocketException) { /* Manual connection remains available if discovery port is occupied. */ }
        }
    }
    private async Task DiscoverAsync()
    {
        var socket = discovery!;
        try
        {
            while (!cancellation.IsCancellationRequested)
            {
                var packet = await socket.ReceiveAsync(cancellation.Token);
                if (!IsLocal(packet.RemoteEndPoint.Address) || packet.Buffer.Length > 100) continue;
                var text = Encoding.UTF8.GetString(packet.Buffer);
                if (!text.StartsWith("PULT_DISCOVER_V1:") || !Guid.TryParse(text[17..], out _)) continue;
                var json = JsonSerializer.Serialize(new { requestId = text[17..], name = Info.Name, port = Protocol.Port, fingerprint = Fingerprint, protocol = Protocol.Version }, Protocol.Json);
                await socket.SendAsync(Encoding.UTF8.GetBytes(json), packet.RemoteEndPoint, cancellation.Token);
            }
        }
        catch (OperationCanceledException) { }
        catch (ObjectDisposedException) { }
        catch (SocketException) { }
    }
    private Phone Authenticate(HttpContext context)
    {
        var header = context.Request.Headers.Authorization.ToString();
        var phone = header.StartsWith("Bearer ", StringComparison.Ordinal) ? Service.Authenticate(header[7..]) : null;
        return phone ?? throw new RequestException(401, "Телефон не привязан или доступ отозван.");
    }
    private static void RequireJson(HttpContext context) { if (!context.Request.HasJsonContentType()) throw new RequestException(415, "Нужен запрос application/json."); }
    public static bool IsLocal(IPAddress? address)
    {
        if (address is null) return false;
        if (IPAddress.IsLoopback(address)) return true;
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        var bytes = address.GetAddressBytes();
        if (bytes.Length == 4) return bytes[0] == 10 || bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 || bytes[0] == 169 && bytes[1] == 254;
        return address.IsIPv6LinkLocal || (bytes[0] & 0xFE) == 0xFC;
    }
    public async ValueTask DisposeAsync()
    {
        cancellation.Cancel(); discovery?.Dispose();
        if (timerTask is not null) await timerTask;
        if (server is not null) { await server.StopAsync(); await server.DisposeAsync(); }
        cancellation.Dispose(); certificate.Dispose();
    }
}
