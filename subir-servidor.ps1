# Sube JAR + frontend al servidor.
# Uso (desde la raíz del proyecto):
#   $env:SIT_DEPLOY_HOST = "tu.servidor.o.dominio"
#   $env:SIT_DEPLOY_USER = "sitdeploy"   # usuario SIN root; con sudo para systemctl
#   .\subir-servidor.ps1
#
# No hardcodear IP ni root en este archivo.

$ErrorActionPreference = "Stop"

$hostName = $env:SIT_DEPLOY_HOST
$userName = $env:SIT_DEPLOY_USER
if ([string]::IsNullOrWhiteSpace($hostName)) {
    Write-Host "ERROR: define SIT_DEPLOY_HOST (IP o dominio del VPS). Ejemplo:" -ForegroundColor Red
    Write-Host '  $env:SIT_DEPLOY_HOST = "sitvo.net"' -ForegroundColor Yellow
    exit 1
}
if ([string]::IsNullOrWhiteSpace($userName)) {
    Write-Host "ERROR: define SIT_DEPLOY_USER (no uses root). Ejemplo:" -ForegroundColor Red
    Write-Host '  $env:SIT_DEPLOY_USER = "sitdeploy"' -ForegroundColor Yellow
    exit 1
}
if ($userName -eq "root") {
    Write-Host "AVISO: desplegar como root no es recomendable. Preferible un usuario con sudo." -ForegroundColor Yellow
}

$SERVER = "${userName}@${hostName}"

Write-Host "=== Destino: $SERVER ===" -ForegroundColor Cyan

Write-Host "=== 1. Subiendo JAR al servidor ===" -ForegroundColor Cyan
scp build/libs/sit-0.0.1-SNAPSHOT.jar "${SERVER}:/opt/sit/"
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "`n=== 2. Subiendo frontend al servidor ===" -ForegroundColor Cyan
$browser = Join-Path $PSScriptRoot "frontend\dist\sit-frontend\browser"
if (-not (Test-Path (Join-Path $browser "index.html"))) {
    Write-Host "ERROR: no existe $browser\index.html. Ejecuta antes: cd frontend; npm run build" -ForegroundColor Red
    exit 1
}
$mainLocal = (Get-ChildItem (Join-Path $browser "main-*.js") | Select-Object -First 1).Name
Write-Host "Build local: $mainLocal (debe cambiar en el servidor tras subir)" -ForegroundColor Yellow
scp -r "$browser\*" "${SERVER}:/var/www/sit/"
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Host "Tras reiniciar, verifica en el sitio el script main-*.js (no debe ser el hash viejo)." -ForegroundColor Yellow

Write-Host "`n=== 3. Reiniciar el backend en el servidor ===" -ForegroundColor Green
Write-Host "Ejecuta: ssh ${SERVER} 'sudo systemctl restart sit'" -ForegroundColor Yellow
Write-Host "O conectate y ejecuta: sudo systemctl restart sit" -ForegroundColor Yellow
