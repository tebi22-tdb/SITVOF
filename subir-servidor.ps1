# Script para subir SITVO al servidor 77.37.74.122
# Ejecutar en PowerShell desde la raíz del proyecto: .\subir-servidor.ps1
# Te pedirá la contraseña de root dos veces (JAR y frontend).

$ErrorActionPreference = "Stop"
$SERVER = "root@77.37.74.122"

Write-Host "=== 1. Subiendo JAR al servidor ===" -ForegroundColor Cyan
scp build/libs/sit-0.0.1-SNAPSHOT.jar "${SERVER}:/opt/sit/"
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "`n=== 2. Subiendo frontend al servidor ===" -ForegroundColor Cyan
Set-Location frontend\dist\sit-frontend\browser
scp -r * "${SERVER}:/var/www/sit/"
Set-Location ..\..\..\..
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "`n=== 3. Reiniciar el backend en el servidor ===" -ForegroundColor Green
Write-Host "Ejecuta por SSH: ssh ${SERVER} 'sudo systemctl restart sit'" -ForegroundColor Yellow
Write-Host "O conectate y ejecuta: sudo systemctl restart sit" -ForegroundColor Yellow
Write-Host "`nLuego abre: http://77.37.74.122 (deberias ver primero el login)" -ForegroundColor Cyan
