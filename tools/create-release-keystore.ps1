$ErrorActionPreference = 'Stop'

Write-Host "RemoteLink - criação da chave de assinatura release" -ForegroundColor Cyan
Write-Host "Esta chave identifica oficialmente todas as futuras versões do app." -ForegroundColor Yellow
Write-Host "Guarde o arquivo e as senhas em local seguro. NÃO envie a chave para o repositório." -ForegroundColor Yellow
Write-Host ""

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    throw "keytool não encontrado. Instale/ative um JDK 17 e execute novamente."
}

$outDir = Join-Path $PSScriptRoot "private-signing"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$keystore = Join-Path $outDir "remotelink-release.jks"

if (Test-Path $keystore) {
    throw "A chave já existe em $keystore. Não sobrescreva uma chave usada em releases anteriores."
}

Write-Host "O keytool solicitará a senha da chave e informações do certificado." -ForegroundColor Green
Write-Host "Use o alias sugerido: remotelink" -ForegroundColor Green
Write-Host ""

& $keytool.Source -genkeypair -v `
    -keystore $keystore `
    -alias remotelink `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) {
    throw "Não foi possível criar a chave."
}

Write-Host ""
Write-Host "Chave criada em:" -ForegroundColor Cyan
Write-Host $keystore
Write-Host ""
Write-Host "Próximo passo no GitHub > Settings > Secrets and variables > Actions:" -ForegroundColor Cyan
Write-Host "1. ANDROID_KEYSTORE_BASE64  = conteúdo Base64 do arquivo .jks"
Write-Host "2. ANDROID_KEYSTORE_PASSWORD = senha do keystore"
Write-Host "3. ANDROID_KEY_ALIAS          = remotelink"
Write-Host "4. ANDROID_KEY_PASSWORD       = senha da chave/alias"
Write-Host ""
Write-Host "Para copiar o .jks como Base64 para a área de transferência:" -ForegroundColor Cyan
Write-Host "[Convert]::ToBase64String([IO.File]::ReadAllBytes('$keystore')) | Set-Clipboard"
Write-Host ""
Write-Host "Faça também um backup offline da chave. Se ela for perdida, versões assinadas por ela não poderão ser atualizadas por cima." -ForegroundColor Yellow
