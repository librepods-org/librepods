<#
    LibrePods - unattended recovery of the AAP driver devnode. NO REBOOT.

    Two ways in:
      * The "LibrePods Fix Driver" scheduled task (RunLevel Highest), which
        install.ps1 registers and the daemon triggers with `schtasks /run` when it
        can no longer open the driver while Windows still has the AirPods
        connected - see daemon/src/devnode.rs. Already elevated, runs hidden.
      * By hand. This needs admin, so the script asks for it through UAC and
        relaunches itself elevated.

    WHY THE LADDER LOOKS LIKE THIS (measured 2026-08-31, from a real Code 38):

      sc query LibrePodsAAP  ->  STATE: STOPPED, WIN32_EXIT_CODE: 31

    The driver image was NOT loaded, yet the devnode still reported
    CM_PROB_DRIVER_FAILED_PRIOR_UNLOAD. So "a previous instance is still in
    memory" was not literally true: the stale state lives in PnP, in the
    Bluetooth branch ABOVE our leaf node. That is why:

      * `pnputil /restart-device <leaf>` answered "System reboot is needed to
        complete configuration operations!" and left it in CM_PROB_NEED_RESTART;
      * `remove-device` + `scan-devices` put it straight back to Code 38 - the
        rescan just rebinds into the same stale branch.

    The lever that actually rebuilds that branch is restarting the Bluetooth
    RADIO (walk the parent chain: leaf -> BTH\MS_BTHBRB\... -> USB\VID_8087...),
    which tears down and re-enumerates every Bluetooth child. Hence the order
    below, with remove+rescan demoted to after it rather than before.
#>
[CmdletBinding()]
param(
    # Set only by the self-elevation relaunch below, so the elevated window stays
    # open long enough to read the result. The scheduled task never passes it.
    [switch]$Relaunched,
    # Skip step 3. Restarting the radio drops EVERY Bluetooth connection for a
    # few seconds - harmless for the AirPods, but a Bluetooth mouse/keyboard will
    # blink out. They all come back on their own.
    [switch]$SkipRadioReset
)

$ErrorActionPreference = 'Continue'

# ---- admin ------------------------------------------------------------------
# pnputil restart/remove-device need admin. Ask up front rather than failing
# halfway through with "Access denied". Under the scheduled task this is already
# true, so it is a no-op there.
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host 'This needs administrator rights - asking for elevation...' -ForegroundColor Yellow
    $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Relaunched')
    if ($SkipRadioReset) { $argv += '-SkipRadioReset' }
    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $argv
    } catch {
        Write-Host 'Elevation was declined - the driver cannot be recovered without it.' -ForegroundColor Red
        Write-Host 'Run this from an elevated PowerShell, or use `fixdriver` in the debug cockpit.' -ForegroundColor DarkGray
        exit 1
    }
    exit 0
}

# ---- logging ----------------------------------------------------------------
$log = Join-Path $env:LOCALAPPDATA 'LibrePods\fix-driver.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
function Log($m) {
    $line = '{0:HH:mm:ss.fff} {1}' -f (Get-Date), $m
    Add-Content -LiteralPath $log -Value $line
    Write-Host $line
}
function Done($code) {
    if ($Relaunched) { Write-Host ''; Read-Host 'Press Enter to close' | Out-Null }
    exit $code
}

# ---- helpers ----------------------------------------------------------------
# Match on the driver SERVICE, not the friendly name: the device can be renamed
# (the app renames the AirPods), the service name comes from the INF and cannot.
function Get-AapDevice {
    Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue | Where-Object {
        (Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName 'DEVPKEY_Device_Service' -ErrorAction SilentlyContinue).Data -eq 'LibrePodsAAP'
    } | Select-Object -First 1
}

# Poll for the devnode to come back healthy. After a radio reset the whole
# Bluetooth tree is re-enumerated and our node only reappears once the AirPods
# reconnect, so absence here is "not yet", not "failed".
function Wait-Healthy([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        Start-Sleep -Seconds 2
        $d = Get-AapDevice
        if ($d -and $d.Status -eq 'OK') { return $d }
    } while ((Get-Date) -lt $deadline)
    return $d
}

# Walk up the parent chain until we leave the Bluetooth enumerators - that node
# is the radio itself (e.g. USB\VID_8087&PID_0032\... for Intel).
function Get-BtRadio($InstanceId) {
    $id = $InstanceId
    for ($i = 0; $i -lt 8 -and $id; $i++) {
        $parent = (Get-PnpDeviceProperty -InstanceId $id -KeyName 'DEVPKEY_Device_Parent' -ErrorAction SilentlyContinue).Data
        if (-not $parent) { return $null }
        if ($parent -notlike 'BTH*') { return Get-PnpDevice -InstanceId $parent -ErrorAction SilentlyContinue }
        $id = $parent
    }
    return $null
}

# ---- find the devnode -------------------------------------------------------
$dev = Get-AapDevice
if (-not $dev) {
    Log 'no LibrePodsAAP devnode - the AirPods are not connected to Windows. Nothing to do.'
    Done 0
}
if ($dev.Status -eq 'OK') {
    Log "devnode is healthy ($($dev.Status)/$($dev.Problem)) - nothing to do."
    Done 0
}

Log "devnode $($dev.InstanceId)"
Log "  state: $($dev.Status) / $($dev.Problem)"
$svc = sc.exe query LibrePodsAAP 2>&1 | Select-String 'STATE' | ForEach-Object { $_.ToString().Trim() }
Log "  service: $svc"

# ---- 1. free the exclusive handle -------------------------------------------
# The devnode cannot unload while a handle is open. Clear any straggler.
$held = Get-Process librepodsd, librepods-winui, librepods-tray, librepods -ErrorAction SilentlyContinue
if ($held) {
    Log "  stopping handle holders: $(($held | ForEach-Object { $_.ProcessName }) -join ', ')"
    $held | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 700
}

# ---- 2. restart our devnode (cheapest, least disruptive) --------------------
Log '[1/3] pnputil /restart-device (our devnode)'
& pnputil /restart-device $dev.InstanceId 2>&1 | ForEach-Object { Log "    $_" }
Start-Sleep -Seconds 2
$dev = Get-AapDevice
Log "  -> $(if ($dev) { "$($dev.Status) / $($dev.Problem)" } else { 'devnode gone' })"
if ($dev -and $dev.Status -eq 'OK') { Log 'recovered at step 1.'; $ok = $true }

# ---- 3. restart the Bluetooth radio -----------------------------------------
# Rebuilds the whole Bluetooth child branch, which is where the stale PnP state
# actually lives. Every BT connection drops for a few seconds and comes back.
if (-not $ok -and -not $SkipRadioReset) {
    $radio = Get-BtRadio $dev.InstanceId
    if ($radio) {
        Log "[2/3] restarting the Bluetooth radio: $($radio.FriendlyName)"
        Log '      (all Bluetooth connections drop for a few seconds)'
        & pnputil /restart-device $radio.InstanceId 2>&1 | ForEach-Object { Log "    $_" }
        $dev = Wait-Healthy 25
        if ($dev) {
            Log "  -> $($dev.Status) / $($dev.Problem)"
            if ($dev.Status -eq 'OK') { Log 'recovered at step 2.'; $ok = $true }
        } else {
            Log '  -> devnode not republished yet - reconnect the AirPods; it should come back healthy.'
        }
    } else {
        Log '[2/3] could not locate the Bluetooth radio in the parent chain - skipping.'
    }
} elseif (-not $ok) {
    Log '[2/3] skipped (-SkipRadioReset).'
}

# ---- 3b. PnP said a reboot is required - believe it -------------------------
# CM_PROB_NEED_RESTART after a restart-device is PnP telling us the stack cannot
# be torn down live: a reference is held that survives even a radio restart
# (measured 2026-08-31: both the leaf AND the Intel radio answered "System reboot
# is needed"). Step 3 below cannot fix that - it only removes the devnode, whose
# rescan then reports Code 38 again, making the state look worse than it is. So
# stop here and say so honestly instead of churning the device further.
if (-not $ok -and $dev -and $dev.Problem -eq 'CM_PROB_NEED_RESTART') {
    Log ''
    Log 'REBOOT REQUIRED. PnP reports CM_PROB_NEED_RESTART after restarting both the'
    Log 'devnode and the Bluetooth radio - a reference into the Bluetooth stack is'
    Log 'held that cannot be released while Windows is running. Nothing this script'
    Log 'can do clears that; removing the devnode would only obscure the diagnosis.'
    Done 2
}

# ---- 4. last resort: drop the devnode and re-enumerate ----------------------
# Deliberately LAST: on its own this just rebinds into the stale branch and
# lands back on Code 38 (measured). It only helps once step 2 has rebuilt that
# branch underneath it.
if (-not $ok -and $dev) {
    Log '[3/3] removing the devnode and rescanning'
    & pnputil /remove-device $dev.InstanceId 2>&1 | ForEach-Object { Log "    $_" }
    & pnputil /scan-devices 2>&1 | Out-Null
    $dev = Wait-Healthy 15
    if ($dev) {
        Log "  -> $($dev.Status) / $($dev.Problem)"
        if ($dev.Status -eq 'OK') { Log 'recovered at step 3.'; $ok = $true }
    } else {
        Log '  -> devnode gone - reconnect the AirPods in Settings > Bluetooth to republish it.'
    }
}

# ---- 5. bring the daemon back -----------------------------------------------
if ($ok) {
    Log 'recovered without a reboot.'
    $daemon = Join-Path $env:LOCALAPPDATA 'LibrePods\librepodsd.exe'
    if (Test-Path $daemon) {
        Log '  restarting the daemon'
        Start-Process $daemon
    }
    Done 0
} else {
    Log 'NOT recovered. Next things to try, in order:'
    Log '  1. disconnect + reconnect the AirPods in Settings > Bluetooth'
    Log '  2. toggle Bluetooth off/on in Windows'
    Log '  3. reboot (only if both of the above fail)'
    Done 1
}
