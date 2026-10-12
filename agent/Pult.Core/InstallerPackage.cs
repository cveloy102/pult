using System.Text.RegularExpressions;

namespace Pult.Core;

public static class InstallerPackage
{
    public static ReleaseAsset? Select(AvailableRelease release, string repository)
    {
        var name = $"pult-agent-{release.Version}-win-x64-setup.exe";
        var url = $"https://github.com/{repository}/releases/download/{release.Tag}/{name}";
        return release.Assets.FirstOrDefault(asset => asset.Name == name && asset.Url == url &&
            asset.Digest is not null && Regex.IsMatch(asset.Digest, @"^sha256:[a-fA-F0-9]{64}$"));
    }
    public static bool IsDownloadUrl(Uri uri) => uri.Scheme == "https" && uri.Port == 443 && string.IsNullOrEmpty(uri.UserInfo) &&
        uri.Host is "github.com" or "release-assets.githubusercontent.com" or "objects.githubusercontent.com";
}
