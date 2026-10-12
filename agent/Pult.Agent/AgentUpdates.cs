using System.Net;
using System.Net.Http;
using System.Security.Cryptography;

namespace Pult.Agent;

internal static class AgentUpdates
{
    internal static ReleaseAsset? SelectInstaller(AvailableRelease release, string repository) => InstallerPackage.Select(release, repository);
    internal static async Task<string> Download(ReleaseAsset asset, AvailableRelease release, string repository)
    {
        if(SelectInstaller(release, repository) != asset) throw new IOException("Установщик не соответствует релизу.");
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pult", "updates");
        Directory.CreateDirectory(directory);
        var destination = Path.Combine(directory, asset.Name); var temporary = destination + ".part";
        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(5) };
        var uri = new Uri(asset.Url);
        try
        {
            for(var redirects = 0; redirects < 6; redirects++)
            {
                if(!InstallerPackage.IsDownloadUrl(uri)) throw new IOException("Некорректный адрес скачивания.");
                using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead);
                if(response.StatusCode is HttpStatusCode.Redirect or HttpStatusCode.MovedPermanently or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect or HttpStatusCode.SeeOther)
                {
                    uri = new Uri(uri, response.Headers.Location ?? throw new IOException("Нет адреса файла.")); continue;
                }
                response.EnsureSuccessStatusCode();
                const long limit = 200 * 1024 * 1024;
                if(response.Content.Headers.ContentLength > limit) throw new IOException("Файл слишком большой.");
                await using(var input = await response.Content.ReadAsStreamAsync())
                await using(var output = File.Create(temporary))
                {
                    var buffer = new byte[81920]; long total = 0; int count;
                    while((count = await input.ReadAsync(buffer)) > 0) { total += count; if(total > limit)throw new IOException("Файл слишком большой."); await output.WriteAsync(buffer.AsMemory(0, count)); }
                }
                await using(var downloaded = File.OpenRead(temporary))
                {
                    var actual = Convert.ToHexString(await SHA256.HashDataAsync(downloaded));
                    if(!Protocol.Equal(actual, asset.Digest![7..].ToUpperInvariant())) throw new IOException("Контрольная сумма установщика не совпадает.");
                }
                File.Move(temporary, destination, true); return destination;
            }
            throw new IOException("Слишком много перенаправлений.");
        }
        catch { if(File.Exists(temporary))File.Delete(temporary); throw; }
    }
}
