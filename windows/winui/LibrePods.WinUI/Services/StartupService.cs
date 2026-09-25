using System;
using System.IO;
using Microsoft.Win32;

namespace LibrePods.WinUI.Services;

/// Manages "run LibrePods at Windows login" via the per-user Run registry key
/// (HKCU\...\Run — no admin needed). Registers both the headless daemon and the
/// WinUI app (started minimised to the tray), mirroring what the installer sets up,
/// so the user can turn login-startup on/off from Settings.
public static class StartupService
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string AppValue = "LibrePods";        // the WinUI tray app
    private const string DaemonValue = "LibrePods Daemon"; // the headless daemon

    /// True when the login-startup entries are present.
    public static bool IsEnabled()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue(AppValue) is not null || k?.GetValue(DaemonValue) is not null;
        }
        catch { return false; }
    }

    /// Add or remove the login-startup entries. Best-effort and fully guarded.
    public static void SetEnabled(bool on)
    {
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(RunKey);
            if (k is null) return;
            if (on)
            {
                var app = Environment.ProcessPath; // this exe (librepods-winui.exe)
                if (!string.IsNullOrEmpty(app))
                    k.SetValue(AppValue, $"\"{app}\" --tray");
                var daemon = FindDaemon();
                if (daemon is not null)
                    k.SetValue(DaemonValue, $"\"{daemon}\"");
            }
            else
            {
                k.DeleteValue(AppValue, throwOnMissingValue: false);
                k.DeleteValue(DaemonValue, throwOnMissingValue: false);
            }
        }
        catch { }
    }

    /// Locate librepodsd.exe — next to us, else the standard install dir. Mirrors
    /// DaemonClient.FindDaemon so the startup entry points at the same binary.
    private static string? FindDaemon()
    {
        string[] candidates =
        {
            Path.Combine(AppContext.BaseDirectory, "librepodsd.exe"),
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "LibrePods", "librepodsd.exe"),
        };
        foreach (var c in candidates)
            if (File.Exists(c)) return c;
        return null;
    }
}
