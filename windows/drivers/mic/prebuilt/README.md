# Prebuilt LibrePodsMic driver package

The compiled virtual-microphone driver (`AudioCodec.sys` + `.inf` + `.cat`) so you
can install it **without building it** — no Visual Studio / C++ / WDK required.

`audiocodec.cat` was generated from exactly these two files with
`inf2cat /driver:. /os:10_X64`. **Rebuilding the driver or editing the INF means
regenerating the catalog** (WDK machine), otherwise the package won't install.

To install, run the release folder's `install.ps1` from an **admin** PowerShell —
it test-signs the `.sys` + `.cat` with a local cert and uses `devcon` to (re)create
the `ROOT\AudioCodec` device, so a virtual microphone appears in Sound > Input.
