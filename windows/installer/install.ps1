<#
    LibrePods for Windows — one-shot installer.

    Installs BOTH kernel drivers (test-signed on the fly):
      • LibrePodsAAP  — opens the AirPods AAP L2CAP channel (battery, ANC, mic, …).
      • LibrePodsMic  — a virtual microphone so any app can use the AirPods mic.
    Then copies the daemon + WinUI app to %LOCALAPPDATA%\LibrePods, registers the
    two elevated helper tasks (driver recovery, mic rename) and adds the apps to
    startup (the WinUI app launches minimised to the tray).

    RUN AS ADMINISTRATOR, and only AFTER you have:
      1. Backed up your BitLocker recovery key.
      2. Disabled Secure Boot in your firmware/BIOS.
      3. Enabled test signing:  bcdedit /set testsigning on   (then rebooted).

    Everything it needs is in this folder: driver packages (with catalogs), devcon
    and the apps. Nothing from Visual Studio, the Windows SDK or the WDK has to be
    installed — the drivers are signed with PowerShell's own Authenticode support.

    Usage (elevated):  powershell -ExecutionPolicy Bypass -File .\install.ps1
#>
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dest = Join-Path $env:LOCALAPPDATA 'LibrePods'

# ---- 0. preflight: fail BEFORE touching certificates or drivers -------------
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this from an ADMINISTRATOR PowerShell.'
}

# Test-signed drivers only load when the RUNNING boot has test signing on.
# SystemStartOptions describes the current boot (bcdedit shows the next one), so
# this also catches "turned it on but didn't reboot yet".
$bootOptions = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control').SystemStartOptions
if ($bootOptions -notmatch '\bTESTSIGNING\b') {
    throw @'
Test Mode is not active, so Windows would refuse to load the LibrePods drivers.
  1. Back up your BitLocker recovery key (if BitLocker is on).
  2. Disable Secure Boot in your firmware/BIOS (while it is on, bcdedit refuses).
  3. In an admin PowerShell:  bcdedit /set testsigning on
  4. Reboot ("Test Mode" shows in the bottom-right corner), then run this again.
'@
}

$devcon = Join-Path $here 'tools\devcon.exe'   # bundled; creates the ROOT\AudioCodec device
$aap = @{ sys = Join-Path $here 'driver\LibrePodsAAP.sys'; cat = Join-Path $here 'driver\librepodsaap.cat'; inf = Join-Path $here 'driver\LibrePodsAAP.inf' }
$mic = @{ sys = Join-Path $here 'driver-mic\AudioCodec.sys'; cat = Join-Path $here 'driver-mic\audiocodec.cat'; inf = Join-Path $here 'driver-mic\AudioCodec.inf' }
$required = @($aap.Values) + @($mic.Values) + @(
    $devcon
    (Join-Path $here 'librepodsd.exe')
    (Join-Path $here 'avcodec-61.dll')
    (Join-Path $here 'avutil-59.dll')
    (Join-Path $here 'swresample-5.dll')
    (Join-Path $here 'winui\librepods-winui.exe')
    (Join-Path $here 'fix-driver.ps1')
    (Join-Path $here 'rename-mic.ps1')
)
$missing = @($required | Where-Object { -not (Test-Path $_) })
if ($missing) {
    throw "This install folder is incomplete. Download the release again. Missing:`n  " + ($missing -join "`n  ")
}

# Run a native tool, echo its output, and fail on an exit code outside $ok.
# (Native stderr must not become a terminating error under 'Stop', so the
# preference is relaxed for the call itself.)
function Invoke-Tool([string]$what, [int[]]$ok, [scriptblock]$cmd) {
    $ErrorActionPreference = 'Continue'
    $out = & $cmd 2>&1 | Out-String
    $code = $LASTEXITCODE
    if ($out.Trim()) { Write-Host $out.TrimEnd() }
    if ($ok -notcontains $code) { throw "$what failed (exit code $code)." }
}

# ---- 1. test code-signing cert, trusted for driver loading ------------------
# Reuse the cert from an earlier run instead of piling up a new one every time.
$cert = Get-ChildItem Cert:\LocalMachine\My |
    Where-Object { $_.Subject -eq 'CN=LibrePods Test Cert' -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date).AddDays(30) } |
    Sort-Object NotAfter -Descending | Select-Object -First 1
if ($cert) {
    Write-Host '==> Reusing the LibrePods test code-signing certificate...'
} else {
    Write-Host '==> Creating a test code-signing certificate...'
    $cert = New-SelfSignedCertificate -Type CodeSigningCert `
        -Subject 'CN=LibrePods Test Cert' `
        -CertStoreLocation Cert:\LocalMachine\My `
        -KeyUsage DigitalSignature -KeyExportPolicy Exportable
}
Write-Host '==> Trusting it for driver loading...'
foreach ($name in 'Root', 'TrustedPublisher') {
    $s = New-Object System.Security.Cryptography.X509Certificates.X509Store($name, 'LocalMachine')
    $s.Open('ReadWrite'); $s.Add($cert); $s.Close()
}

# ---- 2. sign both driver packages -------------------------------------------
# The catalogs are prebuilt (inf2cat, at release time) and cover the .sys by its
# Authenticode hash, which embedding a signature in the .sys does not change.
function Sign([string]$path) {
    $r = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -HashAlgorithm SHA256
    if (-not $r.SignerCertificate) { throw "Could not sign ${path}: $($r.StatusMessage)" }
    if ($r.Status -ne 'Valid') { Write-Warning "$(Split-Path -Leaf $path): signed, but reported $($r.Status): $($r.StatusMessage)" }
}
Write-Host '==> Signing LibrePodsAAP...'
Sign $aap.sys; Sign $aap.cat
Write-Host '==> Signing LibrePodsMic...'
Sign $mic.sys; Sign $mic.cat

# Stop a running copy so the drivers aren't held open and the exes can be replaced.
Get-Process -Name 'librepods-winui', 'librepodsd' -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500

# ---- 3. install LibrePodsAAP (PnP profile driver, via pnputil) --------------
Write-Host '==> Removing any previously installed LibrePodsAAP package...'
$oem = $null
pnputil /enum-drivers | ForEach-Object {
    if ($_ -match 'Published Name\s*:\s*(oem\d+\.inf)') { $oem = $matches[1] }
    if ($_ -match 'Original Name\s*:\s*LibrePodsAAP\.inf' -and $oem) {
        pnputil /delete-driver $oem /uninstall /force | Out-Null
    }
}
Write-Host '==> Installing LibrePodsAAP...'
# 259 = added, but no matching device yet (AirPods not paired); 3010 = reboot needed.
Invoke-Tool 'Installing LibrePodsAAP (pnputil)' @(0, 259, 3010) { pnputil /add-driver $aap.inf /install }

# ---- 4. install LibrePodsMic (ROOT-enumerated device, via devcon) -----------
Write-Host '==> Removing any existing ROOT\AudioCodec (mic) device...'
Invoke-Tool 'Removing the old mic device (devcon)' @(0, 1, 2) { & $devcon remove 'ROOT\AudioCodec' }
Start-Sleep -Seconds 1
Write-Host '==> Installing LibrePodsMic (virtual microphone)...'
# devcon: 0 = done, 1 = done but a reboot is needed.
Invoke-Tool 'Installing LibrePodsMic (devcon)' @(0, 1) { & $devcon install $mic.inf 'ROOT\AudioCodec' }
$micDev = Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains 'ROOT\AudioCodec' }
if (-not $micDev) { throw 'devcon reported success, but no ROOT\AudioCodec device exists.' }

# ---- 5. copy the apps -------------------------------------------------------
# The daemon owns the driver + AAP session + mic; the WinUI app is its IPC client.
Write-Host "==> Copying the apps to $dest"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
foreach ($f in 'librepodsd.exe', 'avcodec-61.dll', 'avutil-59.dll', 'swresample-5.dll', 'fix-driver.ps1', 'rename-mic.ps1') {
    Copy-Item (Join-Path $here $f) $dest -Force
}
# The WinUI app ships as a self-contained folder.
Copy-Item (Join-Path $here 'winui') $dest -Recurse -Force
# Files from a downloaded zip carry the internet zone mark; clear it on the
# installed copy so startup and the scheduled tasks don't get blocked or prompted.
Get-ChildItem $dest -Recurse -File | Unblock-File

# ---- 6. elevated on-demand helper tasks -------------------------------------
# The daemon runs unelevated and fires these with `schtasks /run`, which runs them
# elevated WITHOUT a UAC prompt:
#   • LibrePods Fix Driver — recover the AAP devnode from Code 38 without a reboot,
#     after repeated driver-open failures (daemon/src/devnode.rs). It re-checks
#     the devnode and no-ops when healthy.
#   • LibrePods Rename Mic — show the mic under the connected device's name; the
#     daemon writes it to micname.txt first (daemon/src/rename.rs). Idempotent.
function Register-HelperTask([string]$name, [string]$script, [string]$description) {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$(Join-Path $dest $script)`""
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" `
        -LogonType Interactive -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 2) -StartWhenAvailable
    Register-ScheduledTask -TaskName $name -Action $action -Principal $principal `
        -Settings $settings -Description $description -Force | Out-Null
}
Write-Host '==> Registering the elevated helper tasks...'
Register-HelperTask 'LibrePods Fix Driver' 'fix-driver.ps1' 'Recover the LibrePods AAP devnode from Code 38 (no reboot).'
Register-HelperTask 'LibrePods Rename Mic' 'rename-mic.ps1' 'Rename the LibrePods virtual mic to the connected device name.'

# ---- 7. auto-start at login -------------------------------------------------
# The daemon is the always-on background process (per-user, in the session — NOT a
# SYSTEM service, which couldn't touch the user's audio/mic). The WinUI app starts
# minimised to the tray (--tray) and is the UI; closing its window hides it back.
Write-Host '==> Adding the daemon + WinUI app to startup...'
$startup = [Environment]::GetFolderPath('Startup')
$ws = New-Object -ComObject WScript.Shell

$lnkd = $ws.CreateShortcut((Join-Path $startup 'LibrePods Daemon.lnk'))
$lnkd.TargetPath = Join-Path $dest 'librepodsd.exe'
$lnkd.WorkingDirectory = $dest
$lnkd.Description = 'LibrePods background daemon'
$lnkd.Save()

$winui = Join-Path $dest 'winui\librepods-winui.exe'
$lnk = $ws.CreateShortcut((Join-Path $startup 'LibrePods.lnk'))
$lnk.TargetPath = $winui
$lnk.Arguments = '--tray'
$lnk.WorkingDirectory = Split-Path $winui
$lnk.Description = 'LibrePods AirPods control'
$lnk.Save()

Write-Host ''
Write-Host '==> Done. A reboot is needed to finish the driver install.' -ForegroundColor Green
Write-Host '    After reboot, connect your AirPods — the WinUI app (tray) shows battery'
Write-Host '    + Noise Control, and the LibrePods microphone appears in Sound > Input'
Write-Host '    (and in Discord etc.), renamed to your AirPods once they connect.'
