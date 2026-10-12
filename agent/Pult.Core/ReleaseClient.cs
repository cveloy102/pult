using System.Net.Http.Headers;
using System.Text.Json;

namespace Pult.Core;

public sealed record ReleaseAsset(string Name, string Url, string? Digest);
public sealed record AvailableRelease(string Tag, Version Version, string Url, string Notes, ReleaseAsset[] Assets);

public sealed class ReleaseClient(HttpClient http)
{
    public static Version? ParseVersion(string tag, string component)
    {
        var prefix = component + "-v";
        if (!tag.StartsWith(prefix, StringComparison.Ordinal) || !Version.TryParse(tag[prefix.Length..], out var version) || version.Build < 0 || version.Revision >= 0) return null;
        return version;
    }
    public async Task<AvailableRelease?> FindAsync(string repository, string component, CancellationToken cancellation = default)
    {
        if (!System.Text.RegularExpressions.Regex.IsMatch(repository, @"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) throw new ArgumentException("Некорректный репозиторий.");
        AvailableRelease? newest = null;
        for (var page = 1; page <= 100; page++)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{repository}/releases?per_page=100&page={page}");
            request.Headers.UserAgent.Add(new ProductInfoHeaderValue("Pult", "0.1.0"));
            using var response = await http.SendAsync(request, cancellation); response.EnsureSuccessStatusCode();
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellation));
            var count = 0;
            foreach (var item in document.RootElement.EnumerateArray())
            {
                count++;
                if (item.GetProperty("draft").GetBoolean() || item.GetProperty("prerelease").GetBoolean()) continue;
                var tag = item.GetProperty("tag_name").GetString()!;
                var version = ParseVersion(tag, component); if (version is null || newest is not null && version <= newest.Version) continue;
                var url = item.GetProperty("html_url").GetString()!;
                if (!url.StartsWith($"https://github.com/{repository}/releases/tag/", StringComparison.Ordinal)) continue;
                var assets = item.GetProperty("assets").EnumerateArray().Select(asset => new ReleaseAsset(asset.GetProperty("name").GetString()!, asset.GetProperty("browser_download_url").GetString()!, asset.TryGetProperty("digest", out var digest) ? digest.GetString() : null)).ToArray();
                newest = new(tag, version, url, item.GetProperty("body").GetString() ?? "", assets);
            }
            if (count < 100) return newest;
        }
        throw new IOException("Список релизов слишком велик. Открой GitHub Releases вручную.");
    }
}
