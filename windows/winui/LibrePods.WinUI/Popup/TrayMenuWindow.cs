using System;
using System.Runtime.InteropServices;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace LibrePods.WinUI.Popup;

/// A borderless, themed, self-dismissing window that hosts <see cref="TrayMenuView"/>
/// as the tray's context menu. Replaces the WinUI MenuFlyout (which clipped to ~1
/// char in H.NotifyIcon's SecondWindow host) and the native Win32 menu (no per-item
/// icons, no app theme). It appears at the cursor, growing up from the tray, and
/// closes when it loses focus or an item is chosen. Every windowing call is guarded.
public sealed class TrayMenuWindow : Window
{
    private const double MenuWidthDip = 260;
    private const double FallbackHeightDip = 420;

    private readonly TrayMenuView _view = new();
    private readonly DaemonClient _client;
    private readonly Action _onOpen;
    private readonly Action _onQuit;
    private bool _closed;
    private bool _positioned;

    public TrayMenuWindow(DaemonClient client, Action onOpen, Action onQuit)
    {
        _client = client;
        _onOpen = onOpen;
        _onQuit = onQuit;

        Content = _view;
        Title = "LibrePods menu";

        _view.OnAnc = a => _client.SetAnc(a);
        _view.OnMute = () => _client.ToggleMute();
        _view.OnOpen = () => _onOpen();
        _view.OnQuit = () => _onQuit();
        _view.OnDismiss = SafeClose;

        try { ConfigurePresenter(); } catch { }
        try { SystemBackdrop = new DesktopAcrylicBackdrop(); } catch { }

        _view.Loaded += OnViewLoaded;
        // Dismiss when focus leaves the menu (click elsewhere / Esc handled by focus).
        Activated += OnActivated;
        Closed += (_, _) => _closed = true;
    }

    /// Populate + show the menu at the current cursor position.
    public void ShowAt(Snapshot snapshot)
    {
        if (_closed) return;
        try
        {
            _view.RequestedTheme = AppSettings.Theme;
            _view.Apply(snapshot);
            try { ApplyExStyles(); } catch { }
            try { RoundCorners(); } catch { }
            // Realize the HWND + lay out the content; final placement happens in
            // OnViewLoaded once the rasterization scale is known.
            AppWindow.Show(true);
        }
        catch { SafeClose(); }
    }

    private void OnViewLoaded(object sender, RoutedEventArgs e)
    {
        if (_closed) return;
        try
        {
            double scale = _view.XamlRoot?.RasterizationScale ?? 1.0;
            if (scale <= 0) scale = 1.0;

            _view.Measure(new Windows.Foundation.Size(MenuWidthDip, double.PositiveInfinity));
            double heightDip = _view.DesiredSize.Height > 0 ? _view.DesiredSize.Height : FallbackHeightDip;
            double widthDip = _view.DesiredSize.Width > 0 ? _view.DesiredSize.Width : MenuWidthDip;

            int w = (int)Math.Ceiling(widthDip * scale);
            int h = (int)Math.Ceiling(heightDip * scale);
            try { AppWindow.Resize(new SizeInt32(w, h)); } catch { }

            var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest);
            var work = area.WorkArea;
            GetCursorPos(out var cur);

            // Grow up-left from the cursor (tray sits bottom-right); clamp on-screen.
            int x = cur.X;
            int y = cur.Y - h;
            if (x + w > work.X + work.Width) x = work.X + work.Width - w;
            if (x < work.X) x = work.X;
            if (y < work.Y) y = work.Y;
            if (y + h > work.Y + work.Height) y = work.Y + work.Height - h;

            try { AppWindow.Move(new PointInt32(x, y)); } catch { }
            _positioned = true;

            // Take focus so the first click-away deactivates (and dismisses) us.
            try { SetForegroundWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)); } catch { }
        }
        catch { SafeClose(); }
    }

    private void OnActivated(object sender, WindowActivatedEventArgs args)
    {
        // Only dismiss on genuine focus loss after we've placed + shown the menu.
        if (_positioned && args.WindowActivationState == WindowActivationState.Deactivated)
            SafeClose();
    }

    private void ConfigurePresenter()
    {
        AppWindow.IsShownInSwitchers = false;
        if (AppWindow.Presenter is OverlappedPresenter p)
        {
            p.SetBorderAndTitleBar(false, false);
            p.IsAlwaysOnTop = true;
            p.IsResizable = false;
            p.IsMaximizable = false;
            p.IsMinimizable = false;
        }
    }

    private void SafeClose()
    {
        if (_closed) return;
        try { Close(); } catch { _closed = true; }
    }

    // ---- Win32 ex-styles + rounded corners + cursor -----------------------

    private void ApplyExStyles()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        nint ex = GetWindowLongPtr(hwnd, GWL_EXSTYLE);
        ex |= WS_EX_TOOLWINDOW; // no taskbar / Alt-Tab (but stays activatable)
        SetWindowLongPtr(hwnd, GWL_EXSTYLE, ex);
    }

    private void RoundCorners()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        int pref = DWMWCP_ROUND;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref pref, sizeof(int));
    }

    private const int GWL_EXSTYLE = -20;
    private const nint WS_EX_TOOLWINDOW = 0x00000080;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWCP_ROUND = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT lpPoint);

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(nint hWnd);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern nint GetWindowLongPtr(nint hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern nint SetWindowLongPtr(nint hWnd, int nIndex, nint dwNewLong);

    [DllImport("dwmapi.dll", SetLastError = true)]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int pvAttribute, int cbAttribute);
}
