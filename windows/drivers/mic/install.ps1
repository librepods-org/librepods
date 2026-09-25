<#
    install.ps1 - test-sign + install the LibrePodsMic virtual audio driver, and
    create its ROOT-enumerated device so a virtual microphone appears.

    RUN AS ADMINISTRATOR, in Test Mode (bcdedit /set testsigning on + reboot,
    Secure Boot off). Consider a system restore point first - this adds a virtual
    audio device.

    Pass the build output dir that holds AudioCodec.sys + AudioCodec.inf.
    Default: the Release build under this driver tree.
#>
param(
    [string]$Dir = (Join-Path $PSScriptRoot 'AudioCodec\Driver\x64\Release')
)
$ErrorActionPreference = 'Stop'

$sys = Join-Path $Dir 'AudioCodec.sys'
$inf = Join-Path $Dir 'AudioCodec.inf'
foreach ($f in @($sys, $inf)) { if (-not (Test-Path $f)) { throw "Missing $f - build first." } }

$kit = 'C:\Program Files (x86)\Windows Kits\10'
$inf2cat = Join-Path $kit 'bin\10.0.28000.0\x86\Inf2Cat.exe'
$devcon = Join-Path $kit 'Tools\10.0.28000.0\x64\devcon.exe'
$signtool = (Get-ChildItem "$kit\bin" -Recurse -Filter signtool.exe |
    Where-Object { $_.FullName -match 'x64' } | Select-Object -First 1).FullName

# 1. A package folder with the .sys + .inf together, then a catalog.
$pkg = Join-Path $env:TEMP 'LibrePodsMicPkg'
Remove-Item $pkg -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $pkg | Out-Null
Copy-Item $sys, $inf $pkg -Force
Write-Host '==> Generating catalog...'
& $inf2cat /driver:$pkg /os:10_X64
$cat = Join-Path $pkg 'audiocodec.cat'

# 2. Test code-signing cert, trusted for driver loading.
Write-Host '==> Creating + trusting a test certificate...'
$cert = New-SelfSignedCertificate -Type CodeSigningCert `
    -Subject 'CN=LibrePods Test Cert' `
    -CertStoreLocation Cert:\LocalMachine\My `
    -KeyUsage DigitalSignature -KeyExportPolicy Exportable
$store = Get-Item "Cert:\LocalMachine\My\$($cert.Thumbprint)"
foreach ($name in 'Root', 'TrustedPublisher') {
    $s = New-Object System.Security.Cryptography.X509Certificates.X509Store($name, 'LocalMachine')
    $s.Open('ReadWrite'); $s.Add($store); $s.Close()
}

Write-Host '==> Signing driver + catalog...'
$pkgSys = Join-Path $pkg 'AudioCodec.sys'
& $signtool sign /v /fd SHA256 /sm /s My /sha1 $cert.Thumbprint $pkgSys
& $signtool sign /v /fd SHA256 /sm /s My /sha1 $cert.Thumbprint $cat

# 3. Install the driver + create the ROOT device (devcon install does both).
#    Remove any existing instance first so re-running updates cleanly instead of
#    stacking a second virtual device.
$pkgInf = Join-Path $pkg 'AudioCodec.inf'
Write-Host '==> Removing any existing ROOT\AudioCodec device...'
& $devcon remove 'ROOT\AudioCodec' 2>&1 | Out-Null
Start-Sleep -Seconds 1
Write-Host '==> Installing + creating the ROOT\AudioCodec device...'
& $devcon install $pkgInf 'ROOT\AudioCodec'

Write-Host ''
Write-Host '==> Done. Check Settings -> System -> Sound -> Input for a new mic,'
Write-Host '    and Device Manager -> Sound, video and game controllers.'
