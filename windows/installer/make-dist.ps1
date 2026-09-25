<#
    Assemble a ready-to-install LibrePods-dist folder from THIS repository.

    Why this exists: the dist folder had drifted into a second, older copy of the
    installer (its install.ps1 still deployed the retired iced app and tray, and
    knew nothing about the daemon or the WinUI app), and every fix had to be made
    twice. The repository is the single source of truth; this script is the only
    supported way to produce a dist from it — CI (.github/workflows/ci-windows.yml)
    calls it too, so the release zip and a local dist can't drift apart.

    The end user receives ONLY this folder, so it must be complete on its own. The
    script ends by checking every file install.ps1 needs, and fails otherwise.

    Everything except the two built artifacts comes straight out of the repo:
    the installer, the recovery + mic-rename scripts, devcon, and both prebuilt
    driver packages (catalogs included). The two that must be built first:

        windows\daemon                 cargo build --release --target x86_64-pc-windows-gnu
                                       (or the msvc target WITH -C target-feature=+crt-static)
        windows\winui\LibrePods.WinUI  MSBuild -t:Publish -p:Configuration=Release -p:Platform=x64

    Note the WinUI app needs VISUAL STUDIO's MSBuild, not `dotnet publish`: the
    WindowsAppSDK PRI step fails on the plain .NET SDK with
    "Microsoft.Build.Packaging.Pri.Tasks.dll ... could not be loaded". And its
    publish output DROPS librepods-winui.pri, the app's own resource index, which
    this script copies back in - without it the app starts with no strings and no
    icons.

    Usage:  .\make-dist.ps1 [-Out <path>] [-DaemonExe <exe>] [-WinUIDir <dir>]
#>
[CmdletBinding()]
param(
    # Where to write the dist. Defaults to <repo parent>\LibrePods-dist, which is
    # where the project keeps it when the sub-projects are grouped under one root.
    [string]$Out,
    # The built daemon. Defaults to the local GNU cross-build.
    [string]$DaemonExe,
    # The folder holding librepods-winui.exe. Defaults to the local publish output;
    # CI passes its plain Build output instead.
    [string]$WinUIDir
)

$ErrorActionPreference = 'Stop'
$installer = $PSScriptRoot
$win       = Split-Path -Parent $installer          # ...\windows
$repo      = Split-Path -Parent $win                # the repo root
$winuiOut  = Join-Path $win 'winui\LibrePods.WinUI\bin\x64\Release\net10.0-windows10.0.19041.0\win-x64'
if (-not $Out)       { $Out       = Join-Path (Split-Path -Parent $repo) 'LibrePods-dist' }
if (-not $DaemonExe) { $DaemonExe = Join-Path $win 'daemon\target\x86_64-pc-windows-gnu\release\librepodsd.exe' }
if (-not $WinUIDir)  { $WinUIDir  = Join-Path $winuiOut 'publish' }
$resolve = { param($p) $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($p) }
$Out = & $resolve $Out; $DaemonExe = & $resolve $DaemonExe; $WinUIDir = & $resolve $WinUIDir

foreach ($p in @($DaemonExe, (Join-Path $WinUIDir 'librepods-winui.exe'))) {
    if (-not (Test-Path $p)) { throw "Not built yet: $p`nSee the header of this script for the build commands." }
}

Write-Host "==> Assembling $Out"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
foreach ($sub in 'driver', 'driver-mic', 'tools', 'winui') {
    $d = Join-Path $Out $sub
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
# Retired files an older dist may still hold (lp-mic-rename.exe → rename-mic.ps1).
foreach ($old in 'lp-mic-rename.exe') {
    $p = Join-Path $Out $old
    if (Test-Path $p) { Remove-Item $p -Force }
}

# ---- installer + helper scripts ---------------------------------------------
Copy-Item (Join-Path $installer 'install.ps1')        $Out -Force
Copy-Item (Join-Path $installer 'fix-driver.ps1')     $Out -Force
Copy-Item (Join-Path $win 'drivers\mic\rename-mic.ps1') $Out -Force
Copy-Item (Join-Path $installer 'tools\*')            (Join-Path $Out 'tools') -Recurse -Force

# ---- driver packages (prebuilt with catalogs, so no WDK is needed to install) -
Copy-Item (Join-Path $win 'drivers\aap\prebuilt\*') (Join-Path $Out 'driver') -Force
Copy-Item (Join-Path $win 'drivers\mic\prebuilt\*') (Join-Path $Out 'driver-mic') -Force
Get-ChildItem (Join-Path $Out 'driver'), (Join-Path $Out 'driver-mic') -Filter 'README.md' |
    Remove-Item -Force -ErrorAction SilentlyContinue

# ---- daemon + the FFmpeg runtime it links against ---------------------------
Copy-Item $DaemonExe $Out -Force
Copy-Item (Join-Path $win 'daemon\vendor\ffmpeg\bin\*.dll') $Out -Force

# ---- WinUI app (unpackaged + self-contained: a whole folder) ----------------
Copy-Item (Join-Path $WinUIDir '*') (Join-Path $Out 'winui') -Recurse -Force
$pri = Join-Path $Out 'winui\librepods-winui.pri'
if (-not (Test-Path $pri)) {
    # Publish output drops it; the Build output next to publish\ still has it.
    $src = Join-Path (Split-Path -Parent $WinUIDir) 'librepods-winui.pri'
    if (Test-Path $src) { Copy-Item $src (Join-Path $Out 'winui') -Force }
}

# ---- completeness check: the user gets nothing but this folder --------------
$required = @(
    'install.ps1', 'fix-driver.ps1', 'rename-mic.ps1', 'tools\devcon.exe',
    'driver\LibrePodsAAP.inf', 'driver\LibrePodsAAP.sys', 'driver\librepodsaap.cat',
    'driver-mic\AudioCodec.inf', 'driver-mic\AudioCodec.sys', 'driver-mic\audiocodec.cat',
    'librepodsd.exe', 'avcodec-61.dll', 'avutil-59.dll', 'swresample-5.dll',
    'winui\librepods-winui.exe', 'winui\librepods-winui.pri'
)
$missing = @($required | Where-Object { -not (Test-Path (Join-Path $Out $_)) })
if ($missing) { throw "Dist is incomplete, missing: $($missing -join ', ')" }

# Each driver catalog stores the flat SHA1/SHA256 of its INF. If the INF was
# edited (or line-ending-converted) after inf2cat ran, the package won't install.
foreach ($pkg in @(@('driver\LibrePodsAAP.inf', 'driver\librepodsaap.cat'),
                   @('driver-mic\AudioCodec.inf', 'driver-mic\audiocodec.cat'))) {
    $catHex = [BitConverter]::ToString([IO.File]::ReadAllBytes((Join-Path $Out $pkg[1]))).Replace('-', '')
    $inf = Join-Path $Out $pkg[0]
    $inCat = @('SHA1', 'SHA256') | Where-Object { $catHex.Contains((Get-FileHash $inf -Algorithm $_).Hash) }
    if (-not $inCat) {
        throw "$($pkg[0]) is not the INF $($pkg[1]) was generated for. Regenerate the catalog (inf2cat) or restore the matching INF."
    }
}

# A clean Windows has no VC++ redistributable: the daemon must not import it.
$bytes = [IO.File]::ReadAllBytes((Join-Path $Out 'librepodsd.exe'))
$text = [Text.Encoding]::ASCII.GetString($bytes)
foreach ($dll in 'VCRUNTIME140.dll', 'MSVCP140.dll') {
    if ($text.IndexOf($dll, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "librepodsd.exe imports $dll (not on a clean Windows). Build it with -C target-feature=+crt-static."
    }
}

$size = '{0:N0} MB' -f ((Get-ChildItem $Out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
$count = (Get-ChildItem $Out -Recurse -File).Count
Write-Host "==> Done: $count files, $size" -ForegroundColor Green
Write-Host "    Install with (elevated):  cd '$Out'; powershell -ExecutionPolicy Bypass -File .\install.ps1" -ForegroundColor DarkGray
