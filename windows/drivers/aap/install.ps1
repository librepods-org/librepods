<#
    install.ps1 - test-sign, trust and install the LibrePodsAAP driver.

    RUN AS ADMINISTRATOR, and only AFTER you have:
      1. Backed up your BitLocker recovery key.
      2. Created a system restore point.
      3. Disabled Secure Boot in your firmware/BIOS.
      4. Enabled test signing:  bcdedit /set testsigning on   (then rebooted).

    Pass the folder that holds LibrePodsAAP.sys + LibrePodsAAP.inf (+ .cat).
    Example:  .\install.ps1 -PackageDir "C:\Users\Pedro Lopes\LibrePodsAAP\package"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$PackageDir
)

$ErrorActionPreference = 'Stop'
$sys = Join-Path $PackageDir 'LibrePodsAAP.sys'
$cat = Join-Path $PackageDir 'librepodsaap.cat'
$inf = Join-Path $PackageDir 'LibrePodsAAP.inf'
foreach ($f in @($sys, $cat, $inf)) {
    if (-not (Test-Path $f)) { throw "Missing $f" }
}

Write-Host "==> Creating test code-signing certificate..."
$cert = New-SelfSignedCertificate -Type CodeSigningCert `
    -Subject "CN=LibrePods Test Cert" `
    -CertStoreLocation Cert:\LocalMachine\My `
    -KeyUsage DigitalSignature -KeyExportPolicy Exportable

Write-Host "==> Trusting the cert (Root + TrustedPublisher, LocalMachine)..."
$store = Get-Item "Cert:\LocalMachine\My\$($cert.Thumbprint)"
foreach ($name in 'Root', 'TrustedPublisher') {
    $s = New-Object System.Security.Cryptography.X509Certificates.X509Store($name, 'LocalMachine')
    $s.Open('ReadWrite'); $s.Add($store); $s.Close()
}

$signtool = (Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Recurse -Filter signtool.exe |
    Where-Object { $_.FullName -match 'x64' } | Select-Object -First 1).FullName
Write-Host "==> Signing with $signtool"
& $signtool sign /v /fd SHA256 /sm /s My /sha1 $cert.Thumbprint $sys

# Regenerate the catalog over the *signed* .sys so its hash matches (signing the
# .sys changes the file), then sign the catalog.
Write-Host "==> Regenerating catalog over the signed .sys..."
$inf2cat = (Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Recurse -Filter inf2cat.exe |
    Where-Object { $_.FullName -match '\\x86\\' } | Sort-Object FullName | Select-Object -Last 1).FullName
& $inf2cat /driver:$PackageDir /os:10_X64
if ($LASTEXITCODE -ne 0) { throw "inf2cat failed ($LASTEXITCODE)" }

& $signtool sign /v /fd SHA256 /sm /s My /sha1 $cert.Thumbprint $cat

Write-Host "==> Removing any previously installed LibrePodsAAP package..."
$oem = $null
pnputil /enum-drivers | ForEach-Object {
    if ($_ -match 'Published Name\s*:\s*(oem\d+\.inf)') { $oem = $matches[1] }
    if ($_ -match 'Original Name\s*:\s*LibrePodsAAP\.inf' -and $oem) {
        Write-Host "    deleting $oem"
        pnputil /delete-driver $oem /uninstall /force | Out-Null
    }
}

Write-Host "==> Installing driver package..."
pnputil /add-driver $inf /install

Write-Host "`n==> Done. Check binding with:"
Write-Host '    pnputil /enum-devices /class Bluetooth'
Write-Host '    (look for the {74ec2172-...} AAP service now driven by LibrePodsAAP)'
