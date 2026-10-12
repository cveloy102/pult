param([string]$Version = '0.1.1', [int]$VersionCode = 1001)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$signingDir = Join-Path $projectRoot 'work/signing'
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
$keyStore = Join-Path $signingDir 'pult.jks'
$passwordFile = Join-Path $signingDir 'password.dpapi'
if (!(Test-Path $passwordFile)) {
    if (Test-Path $keyStore) { throw 'Existing key without password: restore the password backup.' }
    $passwordBytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
    $password = [Convert]::ToBase64String($passwordBytes)
    $password | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $passwordFile
}
$secure = Get-Content $passwordFile | ConvertTo-SecureString
$credential = New-Object System.Management.Automation.PSCredential('pult', $secure)
$env:PULT_STORE_PASSWORD = $credential.GetNetworkCredential().Password
$env:PULT_KEY_PASSWORD = $env:PULT_STORE_PASSWORD
$env:PULT_KEY_ALIAS = 'pult'
$env:PULT_KEYSTORE = $keyStore
try {
    if (!(Test-Path $keyStore)) {
        & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $keyStore -storetype JKS -alias pult -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Pult, O=Personal' -storepass:env PULT_STORE_PASSWORD -keypass:env PULT_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    }
    Push-Location (Join-Path $projectRoot 'android')
    try {
        & ./gradlew.bat --offline --no-daemon :app:assembleRelease :app:lintRelease "-PversionName=$Version" "-PversionCode=$VersionCode"
        if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    } finally { Pop-Location }
} finally {
    Remove-Item Env:PULT_STORE_PASSWORD,Env:PULT_KEY_PASSWORD,Env:PULT_KEY_ALIAS,Env:PULT_KEYSTORE -ErrorAction SilentlyContinue
}
