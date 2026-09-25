# Prebuilt LibrePodsAAP driver package

The compiled driver (`LibrePodsAAP.sys` + `.inf` + `.cat`) so you can install
**without building it** — no Visual Studio / C++ / WDK required.

Install (admin PowerShell, Test Mode — see [the Windows README](../../../README.md)):
```powershell
& "..\install.ps1" -PackageDir ".\"
```
`install.ps1` creates a test certificate, signs these files, trusts the cert and
installs the driver. Then run `librepods-winui.exe`, which starts the daemon
(`librepodsd.exe`) itself.

Neither the daemon (Rust) nor the WinUI app (C#) needs a C++ toolchain either.
