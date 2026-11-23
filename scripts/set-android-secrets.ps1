# Uploads the local Android release signing key to GitHub Actions secrets.
# Run it yourself from the repository root: values go straight to `gh` via stdin and are never printed.
param([string]$Repository = 'cveloy102/pult')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$keyStore = Join-Path $projectRoot 'work/signing/pult.jks'
$passwordFile = Join-Path $projectRoot 'work/signing/password.dpapi'
if (!(Test-Path $keyStore) -or !(Test-Path $passwordFile)) { throw 'Не найден work/signing/pult.jks или password.dpapi.' }
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw 'Сначала выполните: gh auth login' }

$secure = Get-Content $passwordFile | ConvertTo-SecureString
$password = (New-Object System.Management.Automation.PSCredential('pult', $secure)).GetNetworkCredential().Password
$keyBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyStore))
try {
    $secrets = [ordered]@{
        ANDROID_KEYSTORE_BASE64 = $keyBase64
        ANDROID_STORE_PASSWORD  = $password
        ANDROID_KEY_ALIAS       = 'pult'
        ANDROID_KEY_PASSWORD    = $password
    }
    foreach ($name in $secrets.Keys) {
        $secrets[$name] | gh secret set $name --repo $Repository
        if ($LASTEXITCODE -ne 0) { throw "Не удалось записать секрет $name." }
        Write-Output "OK  $name"
    }
} finally {
    $password = $null; $keyBase64 = $null; $secrets = $null
}
Write-Output "Секреты Android записаны. Проверка: gh secret list --repo $Repository"
