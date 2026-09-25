using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Input;
using H.NotifyIcon;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Popup;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml.Media.Imaging;

namespace LibrePods.WinUI.Tray;

/// The system-tray presence, built on H.NotifyIcon.WinUI. Double-click (or the
/// menu's "Open") shows the main window; right-click opens a custom themed menu
/// (TrayMenuWindow — the WinUI MenuFlyout clipped and the native Win32 menu can't
/// carry icons or follow the theme). The tooltip + icon badge are driven live from
/// the daemon's Snapshot; the icon shows the lower bud's battery % as a number.
///
/// Because the TaskbarIcon is created outside the visual tree, we call ForceCreate().
public sealed class TrayController : IDisposable
{
    private readonly TaskbarIcon _icon;
    private readonly DaemonClient _client;
    private readonly Action _onOpen;
    private readonly Action _onQuit;

    // Latest snapshot, so the menu (built on right-click) reflects current state.
    private Snapshot? _lastSnapshot;
    private TrayMenuWindow? _menuWindow;

    // Icon lifetime: the plain LibrePods icon is persistent; each number badge is a
    // freshly generated GDI icon whose HICON we must destroy when it's replaced.
    private readonly Icon? _baseIcon;
    private Icon? _generatedIcon;
    private IntPtr _generatedHandle;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    public TrayController(Action onOpen, Action onQuit, DaemonClient client)
    {
        _client = client;
        _onOpen = onOpen;
        _onQuit = onQuit;

        _icon = new TaskbarIcon
        {
            ToolTipText = "LibrePods",
            // Right-click opens our custom themed menu window instead of a flyout.
            ContextFlyout = null,
            RightClickCommand = new RelayCommand(ShowMenu),
            IconSource = new BitmapImage(new Uri("ms-appx:///Assets/tray.ico")),
            // Double-click opens the app; single click is left to the OS so a
            // double-click isn't consumed early.
            DoubleClickCommand = new RelayCommand(onOpen),
        };

        // Load the plain icon from disk for the "no number" fallback.
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "Assets", "tray.ico");
            if (File.Exists(path)) _baseIcon = new Icon(path);
        }
        catch { _baseIcon = null; }
    }

    public void Show() => _icon.ForceCreate();

    /// Open the custom tray menu at the cursor, from the latest snapshot.
    private void ShowMenu()
    {
        try
        {
            try { _menuWindow?.Close(); } catch { }
            var menu = new TrayMenuWindow(_client, _onOpen, _onQuit);
            _menuWindow = menu;
            menu.Closed += (_, _) => { if (ReferenceEquals(_menuWindow, menu)) _menuWindow = null; };
            menu.ShowAt(_lastSnapshot ?? new Snapshot());
        }
        catch { }
    }

    /// Refresh the tooltip + icon badge from the latest snapshot, and cache it for
    /// the menu. MUST be called on the UI thread (the App marshals via DispatcherQueue).
    public void UpdateSnapshot(Snapshot s)
    {
        _lastSnapshot = s;

        var name = string.IsNullOrWhiteSpace(s.DevName) ? "LibrePods" : s.DevName;
        var left = Present(s.Battery.Left);
        var right = Present(s.Battery.Right);
        var @case = Present(s.Battery.Case);

        string tip;
        if (!s.Connected)
        {
            tip = $"{name} — {Localize.Get("Status_Disconnected")}";
        }
        else
        {
            var parts = new List<string>();
            if (left is byte l) parts.Add($"L {l}%");
            if (right is byte r) parts.Add($"R {r}%");
            if (@case is byte c) parts.Add($"{Localize.Get("Tray_CaseShort")} {c}%");
            tip = parts.Count > 0 ? $"{name} — {string.Join("  ", parts)}" : $"{name} — {Localize.Get("Status_Connected")}";
            if (AncName(s.Anc) is string mode) tip += $"  ·  {mode}";
        }
        // Win32 NOTIFYICONDATA tooltip caps at 127 chars.
        _icon.ToolTipText = tip.Length > 127 ? tip[..127] : tip;

        // Icon badge: lower present bud (min of L/R), else plain icon.
        UpdateIconBadge(LowerBud(left, right));
    }

    /// A battery reading is "present" only when it's a real 0..100 value; the
    /// daemon uses 0xFF (255) as an absent sentinel.
    private static byte? Present(byte? value) =>
        value is byte v and <= 100 ? v : null;

    /// The localized noise-control mode name for anc 1..4, or null if unknown.
    private static string? AncName(byte anc) => anc switch
    {
        1 => Localize.Get("Anc_Off"),
        2 => Localize.Get("Anc_NoiseCancellation"),
        3 => Localize.Get("Anc_Transparency"),
        4 => Localize.Get("Anc_Adaptive"),
        _ => null,
    };

    private static byte? LowerBud(byte? left, byte? right)
    {
        if (left is byte l && right is byte r) return Math.Min(l, r);
        return left ?? right;
    }

    private void UpdateIconBadge(byte? level)
    {
        if (level is not byte v)
        {
            if (_baseIcon is not null) SetIcon(_baseIcon, IntPtr.Zero);
            return;
        }

        try
        {
            var (icon, handle) = RenderNumberIcon(v.ToString(), LightTaskbar());
            SetIcon(icon, handle);
        }
        catch
        {
            if (_baseIcon is not null) SetIcon(_baseIcon, IntPtr.Zero);
        }
    }

    /// Assign a new tray icon and release the previously generated one (never the
    /// persistent base icon, which is passed with a zero handle).
    private void SetIcon(Icon icon, IntPtr generatedHandle)
    {
        try { _icon.Icon = icon; } catch { }

        _generatedIcon?.Dispose();
        if (_generatedHandle != IntPtr.Zero) DestroyIcon(_generatedHandle);

        _generatedIcon = generatedHandle == IntPtr.Zero ? null : icon;
        _generatedHandle = generatedHandle;
    }

    /// Draw the battery number as LARGE as it fits, centred on a 64px transparent
    /// bitmap for a crisp tray downscale. Returns the Icon + its backing HICON.
    private static (Icon icon, IntPtr handle) RenderNumberIcon(string text, bool lightTaskbar)
    {
        const int size = 64;
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            g.Clear(Color.Transparent);

            using var brush = new SolidBrush(lightTaskbar ? Color.FromArgb(255, 32, 32, 32) : Color.White);
            using var fmt = new StringFormat
            {
                Alignment = StringAlignment.Center,
                LineAlignment = StringAlignment.Center,
            };

            // Shrink from a large face until the number nearly fills the box, so a
            // big "9" and a big "100" both read large in the tray.
            float emSize = 64f;
            while (emSize > 12f)
            {
                using var probe = new Font("Segoe UI", emSize, FontStyle.Bold, GraphicsUnit.Pixel);
                var m = g.MeasureString(text, probe);
                if (m.Width <= size * 1.0f && m.Height <= size) break;
                emSize -= 2f;
            }
            using var font = new Font("Segoe UI", emSize, FontStyle.Bold, GraphicsUnit.Pixel);
            g.DrawString(text, font, brush, new RectangleF(0, 0, size, size), fmt);
        }

        var handle = bmp.GetHicon();
        return (Icon.FromHandle(handle), handle);
    }

    /// True when the taskbar uses the light theme (→ draw dark text). Defaults to
    /// false (dark taskbar, white text) — the Windows 11 default — if unreadable.
    private static bool LightTaskbar()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("SystemUsesLightTheme") is int i && i != 0;
        }
        catch
        {
            return false;
        }
    }

    /// Show a tray balloon for a daemon overlay event.
    public void ShowBalloon(string title, string body)
    {
        try { _icon.ShowNotification(title, body); }
        catch { }
    }

    public void Dispose()
    {
        _icon.Dispose();
        _generatedIcon?.Dispose();
        if (_generatedHandle != IntPtr.Zero) DestroyIcon(_generatedHandle);
        _baseIcon?.Dispose();
    }

    /// Minimal ICommand so the tray's clicks can invoke an Action.
    private sealed class RelayCommand(Action execute) : ICommand
    {
        public event EventHandler? CanExecuteChanged { add { } remove { } }
        public bool CanExecute(object? parameter) => true;
        public void Execute(object? parameter) => execute();
    }
}
