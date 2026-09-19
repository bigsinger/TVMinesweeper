[CmdletBinding()]
param(
    [switch]$Offline,
    [switch]$SkipChecks,
    [string]$JdkPath,
    [string]$SdkPath
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$workRoot = 'E:\temp\TVMinesweeper'
New-Item -ItemType Directory -Force $workRoot | Out-Null

if (-not $JdkPath) {
    $jdkCandidates = @($env:JAVA_HOME, 'D:\Java\jdk-11.0.1')
    foreach ($candidate in $jdkCandidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
            $versionOutput = Get-Content (Join-Path $candidate 'release') -Raw
            if ($versionOutput -match 'JAVA_VERSION="11\.') { $JdkPath = $candidate; break }
        }
    }
}
if (-not $JdkPath -or -not (Test-Path (Join-Path $JdkPath 'bin\java.exe'))) {
    throw 'JDK 11 is required. Pass -JdkPath or set JAVA_HOME.'
}
$env:JAVA_HOME = $JdkPath
$env:PATH = "$JdkPath\bin;$env:PATH"
$env:TEMP = $workRoot
$env:TMP = $workRoot

if (-not $SdkPath) {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'D:\Android\Sdk')) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'platforms\android-28\android.jar'))) {
            $SdkPath = $candidate; break
        }
    }
}
if (-not $SdkPath -or -not (Test-Path (Join-Path $SdkPath 'platforms\android-28\android.jar'))) {
    throw 'Android SDK platform 28 is required. Pass -SdkPath or set ANDROID_HOME.'
}
$env:ANDROID_HOME = $SdkPath
$env:ANDROID_SDK_ROOT = $SdkPath

$signingPath = $env:TVMINESWEEPER_SIGNING_PROPERTIES
if (-not $signingPath) { $signingPath = Join-Path $workRoot 'signing.properties' }
if (-not (Test-Path -LiteralPath $signingPath)) {
    $keyStorePath = Join-Path $workRoot 'release.keystore'
    if (Test-Path -LiteralPath $keyStorePath) {
        throw 'A release keystore already exists without signing.properties. Restore its credentials; do not overwrite it.'
    }
    $randomBytes = New-Object byte[] 32
    $randomSource = [Security.Cryptography.RandomNumberGenerator]::Create()
    $randomSource.GetBytes($randomBytes)
    $randomSource.Dispose()
    $password = [Convert]::ToBase64String($randomBytes)
    $env:TVMINESWEEPER_KEY_PASSWORD = $password
    & (Join-Path $JdkPath 'bin\keytool.exe') -genkeypair -noprompt -keystore $keyStorePath -storetype JKS -alias tvminesweeper -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TVMinesweeper, OU=Development, O=BigSinger, C=CN' -storepass:env TVMINESWEEPER_KEY_PASSWORD -keypass:env TVMINESWEEPER_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Release signing key generation failed.' }
    $signingText = "storeFile=$($keyStorePath.Replace('\', '/'))`nstorePassword=$password`nkeyAlias=tvminesweeper`nkeyPassword=$password`n"
    [IO.File]::WriteAllText($signingPath, $signingText, [Text.UTF8Encoding]::new($false))
    Remove-Item Env:\TVMINESWEEPER_KEY_PASSWORD
    $password = $null
}
$env:TVMINESWEEPER_SIGNING_PROPERTIES = $signingPath

# Reuse an installed distribution even if it was originally fetched from another mirror.
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$distributionRoot = Join-Path $gradleHome 'wrapper\dists\gradle-6.9.4-bin'
$installedGradle = Get-ChildItem -LiteralPath $distributionRoot -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue | Select-Object -First 1
$gradleCommand = if ($installedGradle) { $installedGradle.FullName } else { Join-Path $projectRoot 'gradlew.bat' }
$buildArguments = @('--console=plain', '--no-daemon')
if ($Offline) { $buildArguments += '--offline' }
if (-not $SkipChecks) { $buildArguments += @('testReleaseUnitTest', 'lintRelease') }
$buildArguments += 'assembleRelease'
Push-Location $projectRoot
try {
    & $gradleCommand @buildArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    $versionLine = Get-Content (Join-Path $projectRoot 'version.properties') | Where-Object { $_ -like 'VERSION_NAME=*' }
    $version = ($versionLine -split '=', 2)[1]
    $apkPath = Join-Path $projectRoot "release\TVMinesweeper-release-$version.apk"
    if (-not (Test-Path -LiteralPath $apkPath)) { throw "Release APK was not produced: $apkPath" }
    Write-Host "Release ready: $apkPath"
} finally {
    Pop-Location
}
