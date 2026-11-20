# Sube JAR + frontend al servidor.
# Uso (desde la raíz del proyecto):
#   $env:SIT_DEPLOY_HOST = "tu.servidor.o.dominio"
#   $env:SIT_DEPLOY_USER = "sitdeploy"   # usuario SIN root; con sudo para systemctl
#   .\subir-servidor.ps1
# Llave por defecto: %USERPROFILE%\.ssh\sitvo_deploy  (o la ruta en SIT_DEPLOY_KEY)
#
# No hardcodear IP ni root en este archivo.
# La subida va en partes: este VPS corta scp de archivos grandes (Connection reset / Broken pipe).

$ErrorActionPreference = "Stop"
# PowerShell 7 corta el script cuando ssh escribe en pantalla, aunque el fallo sea esperado.
$global:PSNativeCommandUseErrorActionPreference = $false
$PSNativeCommandUseErrorActionPreference = $false

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
$deployKey = $env:SIT_DEPLOY_KEY
if ([string]::IsNullOrWhiteSpace($deployKey)) {
    $deployKey = Join-Path $env:USERPROFILE ".ssh\sitvo_deploy"
}
$sshOpts = @(
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=6",
    "-o", "TCPKeepAlive=yes",
    "-o", "IPQoS=none",
    "-o", "ConnectTimeout=20"
)
if (Test-Path $deployKey) {
    $sshOpts += @("-i", $deployKey, "-o", "IdentitiesOnly=yes")
}
$scpOpts = @("-O") + $sshOpts
# Hostinger corta la conexion SSH alrededor de 3 o 4 MB. Trozo de 1 MB termina antes del corte.
$chunkBytes = 1MB
$askPassFile = Join-Path $env:TEMP "sit-askpass.exe"

function Clear-DeploySecrets {
    Remove-Item Env:SIT_DEPLOY_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:SSH_ASKPASS -ErrorAction SilentlyContinue
    Remove-Item Env:SSH_ASKPASS_REQUIRE -ErrorAction SilentlyContinue
    Remove-Item Env:DISPLAY -ErrorAction SilentlyContinue
    if ($script:askPassFile -and (Test-Path $script:askPassFile)) {
        Remove-Item $script:askPassFile -Force -ErrorAction SilentlyContinue
    }
}

function Test-SitInstall {
    Invoke-Native { & ssh @sshOpts $SERVER "sudo -n /usr/local/sbin/sit-install ping" }
    return ($LASTEXITCODE -eq 0)
}

function Invoke-Install([string]$accion) {
    if ($accion -notin @("jar", "www", "restart", "ping")) { throw "Accion de instalacion no valida." }
    if ($userName -eq "root") {
        $out = Invoke-Native { & ssh @sshOpts $SERVER "/usr/local/sbin/sit-install $accion" }
    } else {
        $out = Invoke-Native { & ssh @sshOpts $SERVER "sudo -n /usr/local/sbin/sit-install $accion" }
    }
    if ($LASTEXITCODE -ne 0) { throw "No se pudo completar la instalacion ($accion)." }
    return $out
}

function Enable-SitInstall {
    if ($userName -eq "root") { return }
    if (Test-SitInstall) { return }
    Write-Host "sitdeploy no tiene contrasena de sudo. La que se escribio antes no sirve." -ForegroundColor Yellow
    Write-Host "Se pide la contrasena de root, una sola vez, para dejarle ese permiso." -ForegroundColor Yellow
    Write-Host "No es la frase de la llave." -ForegroundColor Yellow
    $rootOk = $false
    for ($intento = 1; $intento -le 3 -and -not $rootOk; $intento++) {
        $secure = Read-Host "Contrasena de root" -AsSecureString
        $plain = [System.Net.NetworkCredential]::new("", $secure).Password
        $secure = $null
        if ([string]::IsNullOrEmpty($plain)) {
            Write-Host "No escribiste nada." -ForegroundColor Yellow
            continue
        }
        $savedPass = $env:SIT_DEPLOY_PASS
        $savedAsk = $env:SSH_ASKPASS
        $savedRequire = $env:SSH_ASKPASS_REQUIRE
        $savedDisplay = $env:DISPLAY
        $setup = @'
#!/bin/bash
set -euo pipefail
cat > /usr/local/sbin/sit-install << "END"
#!/bin/bash
set -euo pipefail
case "${1:-}" in
  ping)
    echo ok
    ;;
  jar)
    mkdir -p /opt/sit
    install -o root -g root -m 644 /tmp/sit-partial-sit-0.0.1-SNAPSHOT.jar /opt/sit/sit-0.0.1-SNAPSHOT.jar
    stat -c %s /opt/sit/sit-0.0.1-SNAPSHOT.jar
    ;;
  www)
    test -f /tmp/sit-www-new/index.html
    mkdir -p /var/www/sit
    find /var/www/sit -mindepth 1 -delete
    cp -a /tmp/sit-www-new/. /var/www/sit/
    rm -rf /tmp/sit-www-new /tmp/sit-frontend-deploy.zip
    ;;
  restart)
    systemctl restart sit
    ;;
  *)
    echo "uso: sit-install ping|jar|www|restart" >&2
    exit 2
    ;;
esac
END
chmod 755 /usr/local/sbin/sit-install
cat > /etc/sudoers.d/sitdeploy-install << "END"
sitdeploy ALL=(root) NOPASSWD: /usr/local/sbin/sit-install ping, /usr/local/sbin/sit-install jar, /usr/local/sbin/sit-install www, /usr/local/sbin/sit-install restart
END
chmod 440 /etc/sudoers.d/sitdeploy-install
visudo -cf /etc/sudoers.d/sitdeploy-install
'@
        $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($setup))
        $rootOpts = @(
            "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=6",
            "-o", "TCPKeepAlive=yes",
            "-o", "IPQoS=none",
            "-o", "ConnectTimeout=20",
            "-o", "PreferredAuthentications=password,keyboard-interactive",
            "-o", "PubkeyAuthentication=no",
            "-o", "NumberOfPasswordPrompts=1"
        )
        try {
            Enable-AskPass $plain
            $plain = $null
            Invoke-Native { & ssh @rootOpts "root@$hostName" "echo $b64 | base64 -d | bash" }
            if ($LASTEXITCODE -eq 0) { $rootOk = $true }
            else { Write-Host "Root no acepto la contrasena." -ForegroundColor Yellow }
        } finally {
            $plain = $null
            $env:SIT_DEPLOY_PASS = $savedPass
            $env:SSH_ASKPASS = $savedAsk
            $env:SSH_ASKPASS_REQUIRE = $savedRequire
            $env:DISPLAY = $savedDisplay
        }
    }
    if (-not $rootOk) {
        Write-Host "No se pudo autorizar a sitdeploy." -ForegroundColor Red
        exit 1
    }
    if (-not (Test-SitInstall)) {
        Write-Host "Root entro, pero sitdeploy sigue sin permiso." -ForegroundColor Red
        exit 1
    }
    Write-Host "Listo: sitdeploy ya puede copiar y reiniciar sin contrasena." -ForegroundColor Green
}

function Enable-AskPass([string]$plain) {
    # La contraseña va en una variable. El programa solo la imprime con un salto de linea LF.
    # Console.WriteLine en Windows mete un retorno de carro y SSH la rechaza.
    $env:SIT_DEPLOY_PASS = $plain
    $cls = "P" + [guid]::NewGuid().ToString("N")
    $code = @"
using System;
using System.Text;
public class $cls {
  public static void Main() {
    string p = Environment.GetEnvironmentVariable("SIT_DEPLOY_PASS");
    if (string.IsNullOrEmpty(p)) return;
    byte[] b = new UTF8Encoding(false).GetBytes(p + "\n");
    var s = Console.OpenStandardOutput();
    s.Write(b, 0, b.Length);
    s.Flush();
  }
}
"@
    $script:askPassFile = Join-Path $env:TEMP ("sit-askpass-" + [guid]::NewGuid().ToString("N") + ".exe")
    Add-Type -TypeDefinition $code -OutputAssembly $script:askPassFile -OutputType ConsoleApplication -Language CSharp
    $env:SSH_ASKPASS = $script:askPassFile
    $env:SSH_ASKPASS_REQUIRE = "force"
    $env:DISPLAY = "localhost:0"
}

function Invoke-Native([scriptblock]$Command) {
    $global:PSNativeCommandUseErrorActionPreference = $false
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $script:NativeError = ""
    $code = 1
    try {
        & $Command
        if ($null -ne $LASTEXITCODE) { $code = $LASTEXITCODE }
    } catch {
        $script:NativeError = $_.Exception.Message
        if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { $code = $LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $prev
        $global:LASTEXITCODE = $code
    }
}

function Invoke-Remote([string]$remoteCmd) {
    for ($try = 1; $try -le 5; $try++) {
        Invoke-Native { & ssh @sshOpts $SERVER $remoteCmd }
        if ($LASTEXITCODE -eq 0) { return }
        if ($LASTEXITCODE -ne 255) { throw "Fallo en el servidor: $remoteCmd" }
        if ($try -eq 5) { throw "Fallo SSH: $remoteCmd" }
        Write-Host "El servidor cerro SSH (intento $try/5). Reintento en 15s..." -ForegroundColor Yellow
        Start-Sleep -Seconds 15
    }
}

function Get-RemoteByteCount([string]$path) {
    $raw = Invoke-Native { & ssh @sshOpts -o Compression=yes $SERVER "stat -c %s '$path' 2>/dev/null || echo 0" }
    $line = @("$raw" -split "[\r\n]+" | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1)
    $n = 0L
    [void][long]::TryParse("$line", [ref]$n)
    return $n
}

# Una sola conexion SSH. Si el servidor la corta, sigue desde el byte que ya llego.
function Send-FileInChunks([string]$localPath, [string]$remotePath) {
    $localSize = (Get-Item $localPath).Length
    $safeName = ([IO.Path]::GetFileName($remotePath) -replace '[^A-Za-z0-9._-]', '_')
    $partial = "/tmp/sit-partial-$safeName"

    $localHash = (Get-FileHash -Algorithm SHA256 -Path $localPath).Hash.ToLower()
    $already = Get-RemoteByteCount $remotePath
    if ($already -eq $localSize) {
        $remoteHashRaw = Invoke-Native { & ssh @sshOpts $SERVER "sha256sum '$remotePath'" }
        $remoteHash = ("$remoteHashRaw" -split '\s+')[0].Trim().ToLower()
        if ($remoteHash -eq $localHash) {
            Write-Host "Ya esta igual en el servidor: $remotePath" -ForegroundColor Green
            return
        }
    }

    $haveNow = Get-RemoteByteCount $partial
    if ($haveNow -gt 0) {
        $prefixHash = [System.Security.Cryptography.SHA256]::Create()
        $fsCheck = [System.IO.File]::OpenRead($localPath)
        $localPrefix = ""
        try {
            $left = [Math]::Min($haveNow, $localSize)
            $bufCheck = New-Object byte[] 65536
            while ($left -gt 0) {
                $take = [Math]::Min($bufCheck.Length, $left)
                $read = $fsCheck.Read($bufCheck, 0, $take)
                if ($read -le 0) { break }
                [void]$prefixHash.TransformBlock($bufCheck, 0, $read, $bufCheck, 0)
                $left -= $read
            }
            [void]$prefixHash.TransformFinalBlock($bufCheck, 0, 0)
            $localPrefix = ([BitConverter]::ToString($prefixHash.Hash)).Replace("-", "").ToLower()
        } finally {
            $fsCheck.Close()
            $prefixHash.Dispose()
        }
        $remotePrefixRaw = Invoke-Native { & ssh @sshOpts $SERVER "dd if='$partial' bs=65536 count=$([Math]::Ceiling($haveNow / 65536)) status=none | head -c $haveNow | sha256sum" }
        $remotePrefix = ("$remotePrefixRaw" -split '\s+')[0].Trim().ToLower()
        if ($remotePrefix -ne $localPrefix) {
            Invoke-Remote "rm -f '$partial'"
        }
    }

    $last = -1L
    $stuck = 0
    while ($true) {
        $have = Get-RemoteByteCount $partial
        if ($have -gt $localSize) {
            Invoke-Remote "rm -f '$partial'"
            $have = 0L
        }
        if ($have -eq $localSize) { break }
        if ($have -eq $last) { $stuck++ } else { $stuck = 0; $last = $have }
        if ($stuck -ge 8) { throw "La subida no avanzo despues de varios intentos ($remotePath)." }

        $mbHave = [math]::Round($have / 1MB, 1)
        $mbAll = [math]::Round($localSize / 1MB, 1)
        Write-Host ("Subiendo {0}/{1} MB..." -f $mbHave, $mbAll)
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = "ssh"
        $psi.Arguments = "-C " + ($sshOpts -join " ") + " $SERVER `"cat >> '$partial'`""
        $psi.UseShellExecute = $false
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardError = $true
        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        [void]$proc.Start()
        $fs = [System.IO.File]::OpenRead($localPath)
        try {
            $fs.Position = $have
            $stream = $proc.StandardInput.BaseStream
            $buf = New-Object byte[] 65536
            $nextReport = $have + 1MB
            while ($fs.Position -lt $localSize) {
                if ($proc.HasExited) { break }
                $n = $fs.Read($buf, 0, $buf.Length)
                if ($n -le 0) { break }
                $stream.Write($buf, 0, $n)
                if ($fs.Position -ge $nextReport) {
                    $pct = [math]::Floor(100.0 * $fs.Position / $localSize)
                    Write-Host ("  {0}/{1} MB ({2}%)" -f [math]::Round($fs.Position / 1MB, 1), $mbAll, $pct)
                    $nextReport += 1MB
                }
            }
            $stream.Flush()
            $stream.Close()
        } catch {
            try { if (-not $proc.HasExited) { $proc.Kill() } } catch {}
        } finally {
            $fs.Close()
        }
        if (-not $proc.HasExited) { [void]$proc.WaitForExit(20000) }
        $now = Get-RemoteByteCount $partial
        if ($now -le $have) {
            Write-Host "Se corto. Sigue en 3s desde $mbHave MB..." -ForegroundColor Yellow
            Start-Sleep -Seconds 3
        }
    }

    if ($userName -ne "root" -and $remotePath -notlike "/tmp/*") {
        $remoteSizeText = Invoke-Install "jar"
    } else {
        $placeBody = "mv -f '$partial' '$remotePath' && chmod 644 '$remotePath' && stat -c %s '$remotePath'"
        $remoteSizeText = Invoke-Native { & ssh @sshOpts $SERVER $placeBody }
    }
    if ($LASTEXITCODE -ne 0) { throw "No se pudo colocar $remotePath en el servidor." }
    $remoteSize = 0L
    [void][long]::TryParse((@("$remoteSizeText" -split "[\r\n]+" | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1)), [ref]$remoteSize)
    if ($remoteSize -ne $localSize) {
        throw "Tamaño distinto: local=$localSize remoto=$remoteSize ($remotePath)"
    }
    Write-Host "OK $remotePath ($remoteSize bytes)" -ForegroundColor Green
}

Write-Host "=== Destino: $SERVER ===" -ForegroundColor Cyan
Write-Host "Probando acceso SSH..." -ForegroundColor Cyan
$probeErr = Join-Path $env:TEMP "sit-ssh-probe.txt"
$sshOk = $false
for ($try = 1; $try -le 4; $try++) {
    Invoke-Native { & ssh @sshOpts -o BatchMode=yes $SERVER "echo ok" 1>$null 2>$probeErr }
    if ($LASTEXITCODE -eq 0) { $sshOk = $true; break }
    $errText = if (Test-Path $probeErr) { Get-Content $probeErr -Raw -ErrorAction SilentlyContinue } else { "" }
    $errText = "$errText $($script:NativeError)"
    $cerrado = $errText -match "Connection closed|Connection reset|Connection timed out|kex_exchange"
    if (-not $cerrado) { break }
    Write-Host "El servidor cerró el puerto 22 (intento $try/4). Espera 20s..." -ForegroundColor Yellow
    Start-Sleep -Seconds 20
}
Remove-Item $probeErr -Force -ErrorAction SilentlyContinue
if (-not $sshOk -and (Test-Path $deployKey)) {
    Write-Host "Se pide la frase de la llave. Sin frase, Enter no abre." -ForegroundColor Yellow
    for ($intento = 1; $intento -le 3 -and -not $sshOk; $intento++) {
        $secure = Read-Host "Frase de la llave" -AsSecureString
        $plain = [System.Net.NetworkCredential]::new("", $secure).Password
        $secure = $null
        if ([string]::IsNullOrEmpty($plain)) {
            Write-Host "No escribiste nada." -ForegroundColor Yellow
            continue
        }
        Enable-AskPass $plain
        $plain = $null
        Invoke-Native { & ssh @sshOpts -o NumberOfPasswordPrompts=1 -o PreferredAuthentications=publickey $SERVER "echo ok" 1>$null 2>$probeErr }
        if ($LASTEXITCODE -eq 0) { $sshOk = $true; break }
        $detalle = if (Test-Path $probeErr) { (Get-Content $probeErr -Raw -ErrorAction SilentlyContinue) } else { "" }
        if ("$detalle $($script:NativeError)" -match "Connection closed|Connection reset|timed out") {
            Write-Host "El servidor cerro el puerto 22. Espera 30s..." -ForegroundColor Yellow
            Start-Sleep -Seconds 30
        } else {
            Write-Host "La frase no abrio la llave." -ForegroundColor Yellow
            Clear-DeploySecrets
        }
    }
    Remove-Item $probeErr -Force -ErrorAction SilentlyContinue
    if ($sshOk) { Write-Host "Acceso SSH correcto." -ForegroundColor Green }
}
if (-not $sshOk) {
    Write-Host "La llave no entro. Se pide la contrasena SSH de $userName." -ForegroundColor Yellow
    for ($intento = 1; $intento -le 3 -and -not $sshOk; $intento++) {
        $secure = Read-Host "Contrasena SSH de $SERVER" -AsSecureString
        $plain = [System.Net.NetworkCredential]::new("", $secure).Password
        $secure = $null
        if ([string]::IsNullOrEmpty($plain)) {
            Write-Host "No escribiste nada." -ForegroundColor Yellow
            continue
        }
        Enable-AskPass $plain
        $plain = $null
        Invoke-Native { & ssh @sshOpts -o NumberOfPasswordPrompts=1 -o PreferredAuthentications=password,keyboard-interactive $SERVER "echo ok" 1>$null 2>$probeErr }
        if ($LASTEXITCODE -eq 0) { $sshOk = $true; break }
        $detalle = if (Test-Path $probeErr) { (Get-Content $probeErr -Raw -ErrorAction SilentlyContinue) } else { "" }
        if ("$detalle $($script:NativeError)" -match "Connection closed|Connection reset|timed out") {
            Write-Host "El servidor cerro el puerto 22. Espera 30s..." -ForegroundColor Yellow
            Start-Sleep -Seconds 30
        } else {
            Write-Host "El servidor rechazo la contrasena." -ForegroundColor Yellow
        }
    }
    Remove-Item $probeErr -Force -ErrorAction SilentlyContinue
    if (-not $sshOk) {
        Write-Host "No se pudo entrar. Revisa la llave o la contrasena SSH y vuelve a correr el script." -ForegroundColor Red
        exit 1
    }
    Write-Host "Acceso SSH correcto." -ForegroundColor Green
}

Enable-SitInstall

try {
    $jar = Join-Path $PSScriptRoot "build\libs\sit-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        Write-Host "ERROR: no existe $jar. Compila antes el backend." -ForegroundColor Red
        exit 1
    }

    Write-Host "`n=== 1. Subiendo JAR ===" -ForegroundColor Cyan
    Send-FileInChunks $jar "/opt/sit/sit-0.0.1-SNAPSHOT.jar"

    Write-Host "`n=== 2. Subiendo frontend ===" -ForegroundColor Cyan
    $browser = Join-Path $PSScriptRoot "frontend\dist\sit-frontend\browser"
    if (-not (Test-Path (Join-Path $browser "index.html"))) {
        Write-Host "ERROR: no existe $browser\index.html. Ejecuta antes: cd frontend; npm run build" -ForegroundColor Red
        exit 1
    }
    $mainLocal = (Get-ChildItem (Join-Path $browser "main-*.js") | Select-Object -First 1).Name
    Write-Host "Build local: $mainLocal" -ForegroundColor Yellow
    $zip = Join-Path $env:TEMP "sit-frontend-deploy.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path (Join-Path $browser "*") -DestinationPath $zip
    Send-FileInChunks $zip "/tmp/sit-frontend-deploy.zip"
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    $prep = @'
set -e
echo "Descomprimiendo frontend..."
rm -rf "$HOME/sit-www-build"
mkdir -p "$HOME/sit-www-build"
set +e
unzip -o /tmp/sit-frontend-deploy.zip -d "$HOME/sit-www-build"
uz=$?
set -e
if [ "$uz" -gt 1 ]; then
  echo "No se pudo descomprimir el frontend." >&2
  exit 1
fi
test -f "$HOME/sit-www-build/index.html"
if [ -e /tmp/sit-www-new ] && ! rm -rf /tmp/sit-www-new 2>/dev/null; then
  echo "Limpiando la carpeta temporal con el permiso de root..."
  sudo -n /usr/local/sbin/sit-install www
fi
mkdir -p /tmp/sit-www-new
cp -a "$HOME/sit-www-build/." /tmp/sit-www-new/
rm -rf "$HOME/sit-www-build"
test -f /tmp/sit-www-new/index.html
echo "Frontend preparado."
'@
    $prepB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($prep))
    Invoke-Native { & ssh @sshOpts $SERVER "echo $prepB64 | base64 -d | bash" }
    if ($LASTEXITCODE -ne 0) { throw "No se pudo preparar el frontend." }
    if ($userName -eq "root") {
        Invoke-Remote "mkdir -p /var/www/sit && find /var/www/sit -mindepth 1 -delete && cp -a /tmp/sit-www-new/. /var/www/sit/ && rm -rf /tmp/sit-www-new /tmp/sit-frontend-deploy.zip"
        Write-Host "`n=== 3. Reiniciando backend ===" -ForegroundColor Green
        Invoke-Remote "systemctl restart sit"
    } else {
        Invoke-Install "www"
        Write-Host "`n=== 3. Reiniciando backend ===" -ForegroundColor Green
        Invoke-Install "restart"
    }
    Write-Host "Listo. Revisa https://sitvo.net y que el script cargado sea $mainLocal" -ForegroundColor Green
} finally {
    Clear-DeploySecrets
}
