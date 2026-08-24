$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$WrapperDir = Join-Path $Root "gradle\wrapper"
$WrapperJar = Join-Path $WrapperDir "gradle-wrapper.jar"
$Gradlew = Join-Path $Root "gradlew"
$GradlewBat = Join-Path $Root "gradlew.bat"
$ExpectedWrapperSha256 = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

Write-Host ""
Write-Host "RemoteLink v0.5-alpha - preparar e compilar" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor DarkCyan
New-Item -ItemType Directory -Force -Path $WrapperDir | Out-Null

function Download-File([string]$Url, [string]$Destination) {
    Write-Host "Baixando $Url"
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination
}

if (-not (Test-Path $WrapperJar)) {
    Download-File "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar" $WrapperJar
}
$actualHash = (Get-FileHash -Algorithm SHA256 $WrapperJar).Hash.ToLowerInvariant()
if ($actualHash -ne $ExpectedWrapperSha256) {
    Remove-Item -Force $WrapperJar -ErrorAction SilentlyContinue
    throw "Hash invalido do gradle-wrapper.jar. O arquivo foi removido por seguranca."
}
Write-Host "[OK] Gradle Wrapper verificado por SHA-256." -ForegroundColor Green

if (-not (Test-Path $Gradlew)) {
    Download-File "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew" $Gradlew
}
if (-not (Test-Path $GradlewBat)) {
    Download-File "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew.bat" $GradlewBat
}

function Test-Java17OrNewer {
    try {
        $text = (& java -version 2>&1 | Out-String)
        if ($text -match 'version "([0-9]+)') {
            return ([int]$Matches[1] -ge 17)
        }
    } catch {}
    return $false
}

if (-not (Get-Command java -ErrorAction SilentlyContinue) -or -not (Test-Java17OrNewer)) {
    $javaCandidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) }

    if ($javaCandidates.Count -eq 0) {
        throw "JDK 17+ nao encontrado. Instale o Android Studio ou configure JAVA_HOME."
    }
    $env:JAVA_HOME = $javaCandidates[0]
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Write-Host "[OK] Usando JDK do Android Studio: $env:JAVA_HOME" -ForegroundColor Green
}

if (-not (Test-Java17OrNewer)) {
    throw "O Java encontrado e anterior ao JDK 17."
}

$sdkCandidates = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    "$env:LOCALAPPDATA\Android\Sdk"
) | Where-Object { $_ -and (Test-Path $_) }

if ($sdkCandidates.Count -eq 0) {
    throw "Android SDK nao encontrado. Instale o Android Studio + Android 16/API 36."
}

$sdk = (Resolve-Path $sdkCandidates[0]).Path
$androidJar = Join-Path $sdk "platforms\android-36\android.jar"
if (-not (Test-Path $androidJar)) {
    throw "Android API 36 nao encontrada em $sdk. Abra Android Studio > SDK Manager e instale Android 16/API 36."
}
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
$sdkForProperties = $sdk.Replace('\', '\\')
Set-Content -Path (Join-Path $Root "local.properties") -Value "sdk.dir=$sdkForProperties" -Encoding ASCII
Write-Host "[OK] Android SDK: $sdk" -ForegroundColor Green

Push-Location $Root
try {
    Write-Host ""
    Write-Host "Validando painel web..." -ForegroundColor Cyan
    if (Get-Command node -ErrorAction SilentlyContinue) {
        & node --check "app/src/main/assets/web/app.js"
        if ($LASTEXITCODE -ne 0) { throw "app.js possui erro de sintaxe" }
        & node --check "app/src/main/assets/web/reconnect.js"
        if ($LASTEXITCODE -ne 0) { throw "reconnect.js possui erro de sintaxe" }
    }

    Write-Host "Compilando APK debug..." -ForegroundColor Cyan
    & $GradlewBat --no-daemon --stacktrace assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle terminou com codigo $LASTEXITCODE"
    }

    $BuiltApk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $BuiltApk)) {
        throw "Build terminou, mas o APK esperado nao foi encontrado: $BuiltApk"
    }
    $OutputApk = Join-Path $Root "RemoteLink-v0.5-alpha-debug.apk"
    Copy-Item -Force $BuiltApk $OutputApk
    Write-Host ""
    Write-Host "[SUCESSO] APK criado:" -ForegroundColor Green
    Write-Host $OutputApk -ForegroundColor Yellow
} finally {
    Pop-Location
}
