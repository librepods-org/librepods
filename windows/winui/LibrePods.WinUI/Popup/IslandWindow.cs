using System.Diagnostics;
using System.Runtime.InteropServices;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace LibrePods.WinUI.Popup;

/// A borderless, always-on-top, no-taskbar island that slides down from the
/// top-centre of the work area when the AirPods connect, holds ~4s, then slides
/// back up and closes itself.
///
/// It hosts an <see cref="IslandView"/> on a DesktopAcrylic backdrop with
/// DWM-rounded corners, so the whole window IS the rounded translucent card —
/// this avoids needing true per-pixel window transparency (which WinUI 3 does not
/// reliably support). The slide is a smooth eased animation of the window's
/// screen position driven by a DispatcherQueueTimer (a transform of the whole
/// card, not per-frame redrawing); the fade rides the same interpolation on the
/// content's Opacity.
///
/// Every Win32 / windowing call is wrapped so a popup failure can never crash the
/// app — the worst case is simply no island.
public sealed class IslandWindow : Window
{
    // Card geometry (device-independent pixels). Width matches IslandView's Width.
    // The Apple-style card is a narrow vertical layout (name / render / battery),
    // so it is taller than the old horizontal strip — the real height is measured
    // from the view's DesiredSize; this fallback is only used if that measure fails.
    private const double DesignWidthDip = 300;
    private const double FallbackHeightDip = 210;
    private const double TopMarginDip = 12;

    // Animation timing.
    private const double SlideMs = 300;
    private const double HoldMs = 4000;

    private enum Phase { In, Hold, Out }

    private readonly IslandView _view = new();
    private readonly DispatcherQueueTimer _timer;
    private readonly Stopwatch _clock = new();

    private Phase _phase = Phase.In;
    private bool _shown;
    private bool _prepared;
    private bool _loaded;
    private bool _closed;

    // Physical-pixel geometry, computed once the XamlRoot scale is known.
    private int _width;
    private int _height;
    private int _centerX;
    private int _targetY;
    private int _startY;

    public IslandWindow()
    {
        Content = _view;
        Title = "LibrePods";

        try { ConfigurePresenter(); } catch { }
        try { SystemBackdrop = new DesktopAcrylicBackdrop(); } catch { }

        _timer = DispatcherQueue.CreateTimer();
        _timer.Interval = TimeSpan.FromMilliseconds(16); // ~60 fps
        _timer.Tick += OnTick;

        // Geometry + the slide-in start once the content is laid out (its
        // XamlRoot — and therefore the rasterization scale — is available then).
        _view.Loaded += OnViewLoaded;

        Closed += (_, _) => { _closed = true; try { _timer.Stop(); } catch { } };
    }

    /// Match the island to the app's chosen theme — the DesktopAcrylic tint and the
    /// ThemeResource text brushes follow the content's RequestedTheme. Default just
    /// follows the system. Applied on each show in case the theme changed meanwhile.
    private void ApplyTheme()
    {
        try { _view.RequestedTheme = AppSettings.Theme; } catch { }
    }

    /// Show the island for a fresh connection, or — if one is already on screen —
    /// re-populate it and reset the hold so reconnect spam never stacks popups.
    public void ShowConnected(Snapshot s)
    {
        if (_closed) return;
        try
        {
            ApplyTheme();
            _view.Apply(s);

            if (!_shown)
            {
                _shown = true;
                // Show without activating so it never steals focus. The no-activate
                // / tool-window ex-styles and rounded corners are applied here (the
                // HWND exists once AppWindow does).
                try { ApplyExStyles(); } catch { }
                try { RoundCorners(); } catch { }
                AppWindow.Show(false);
                // First-ever show: the slide-in is kicked off from OnViewLoaded (the
                // XamlRoot isn't ready yet). Reuse after a hide: the view is already
                // loaded, so start it now.
                if (_loaded) StartSlideIn();
            }
            else
            {
                // Already visible: snap to the resting spot and restart the hold.
                _view.Opacity = 1;
                _phase = Phase.Hold;
                _clock.Restart();
                if (_prepared)
                {
                    try { AppWindow.Move(new PointInt32(_centerX, _targetY)); } catch { }
                }
                try { AppWindow.Show(false); } catch { }
                if (!_timer.IsRunning) _timer.Start();
            }
        }
        catch
        {
            // Never let a popup refresh crash the app.
        }
    }

    /// Show a daemon overlay as the centred island (message mode) instead of a
    /// Windows toast — same slide/hold as the connection card.
    public void ShowMessage(string title, string body, string? model = null)
    {
        if (_closed) return;
        try
        {
            ApplyTheme();
            _view.ApplyMessage(title, body, model);

            if (!_shown)
            {
                _shown = true;
                try { ApplyExStyles(); } catch { }
                try { RoundCorners(); } catch { }
                AppWindow.Show(false);
                if (_loaded) StartSlideIn(); // reuse after a hide (see ShowConnected)
            }
            else
            {
                _view.Opacity = 1;
                _phase = Phase.Hold;
                _clock.Restart();
                if (_prepared)
                {
                    try { AppWindow.Move(new PointInt32(_centerX, _targetY)); } catch { }
                }
                try { AppWindow.Show(false); } catch { }
                if (!_timer.IsRunning) _timer.Start();
            }
        }
        catch
        {
            // Never let a popup refresh crash the app.
        }
    }

    // ---- Presenter / interop ----------------------------------------------

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

    private void OnViewLoaded(object sender, RoutedEventArgs e)
    {
        _loaded = true;
        if (_closed) return;
        // A show requested before the view had loaded (the first show) starts here,
        // once the XamlRoot/scale is available.
        if (_shown) StartSlideIn();
    }

    /// Begin the slide-in → hold → slide-out cycle from the top. Callable on every
    /// show (the first waits for OnViewLoaded; reuse-after-hide calls it directly).
    private void StartSlideIn()
    {
        if (_closed) return;
        try
        {
            Prepare();
            _phase = Phase.In;
            _view.Opacity = 0;
            try { AppWindow.Move(new PointInt32(_centerX, _startY)); } catch { }
            _clock.Restart();
            if (!_timer.IsRunning) _timer.Start();
        }
        catch
        {
            // If we can't set up the slide, just hide quietly.
            SafeClose();
        }
    }

    /// Compute physical-pixel size/position from the current display work area and
    /// the content's rasterization scale.
    private void Prepare()
    {
        if (_prepared) return;

        double scale = _view.XamlRoot?.RasterizationScale ?? 1.0;
        if (scale <= 0) scale = 1.0;

        // Measure the card's NATURAL height at the fixed width. ActualHeight here
        // is the (screen-tall) window it currently fills — circular — which made
        // the popup gigantic; DesiredSize is the content's real ~92px.
        _view.Measure(new Windows.Foundation.Size(DesignWidthDip, double.PositiveInfinity));
        double heightDip = _view.DesiredSize.Height > 0 ? _view.DesiredSize.Height : FallbackHeightDip;

        _width = (int)Math.Ceiling(DesignWidthDip * scale);
        _height = (int)Math.Ceiling(heightDip * scale);
        try { AppWindow.Resize(new SizeInt32(_width, _height)); } catch { }

        // Centre horizontally on the display containing this window; rest a small
        // margin above the bottom of the work area, and slide in from off the
        // bottom edge (rising up into view, then sliding back down to close).
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest);
        var work = area.WorkArea;
        int margin = (int)Math.Round(TopMarginDip * scale);
        _centerX = work.X + (work.Width - _width) / 2;
        _targetY = work.Y + work.Height - _height - margin;
        _startY = work.Y + work.Height; // fully off the bottom

        _prepared = true;
    }

    // ---- Slide / hold / slide-out driver ----------------------------------

    private void OnTick(DispatcherQueueTimer sender, object args)
    {
        if (_closed) { try { _timer.Stop(); } catch { } return; }

        try
        {
            double elapsed = _clock.Elapsed.TotalMilliseconds;

            switch (_phase)
            {
                case Phase.In:
                {
                    double t = Math.Clamp(elapsed / SlideMs, 0, 1);
                    double k = EaseOut(t);
                    Move(Lerp(_startY, _targetY, k));
                    _view.Opacity = t;
                    if (t >= 1)
                    {
                        _view.Opacity = 1;
                        _phase = Phase.Hold;
                        _clock.Restart();
                    }
                    break;
                }
                case Phase.Hold:
                {
                    if (elapsed >= HoldMs)
                    {
                        _phase = Phase.Out;
                        _clock.Restart();
                    }
                    break;
                }
                case Phase.Out:
                {
                    double t = Math.Clamp(elapsed / SlideMs, 0, 1);
                    double k = EaseOut(t);
                    Move(Lerp(_targetY, _startY, k));
                    _view.Opacity = 1 - t;
                    if (t >= 1)
                    {
                        try { _timer.Stop(); } catch { }
                        SafeClose();
                    }
                    break;
                }
            }
        }
        catch
        {
            try { _timer.Stop(); } catch { }
            SafeClose();
        }
    }

    private void Move(double y)
    {
        try { AppWindow.Move(new PointInt32(_centerX, (int)Math.Round(y))); } catch { }
    }

    /// End the current popup. Deliberately does NOT call Window.Close(): closing a
    /// WinUI 3 Window that carries a DesktopAcrylicBackdrop + themed content, from a
    /// DispatcherQueueTimer tick, fail-fasts inside the XAML theme-resource teardown
    /// (Microsoft_UI_Xaml!OverrideXamlResourcePropertyBag, 0xC000027B) — a native
    /// crash a try/catch can't stop. Instead we hide the single window and reset the
    /// animation state so the next show slides it back in (the reuse the design
    /// already intends). The window then lives for the app's lifetime.
    private void SafeClose()
    {
        if (_closed) return;
        try { _timer.Stop(); } catch { }
        try { AppWindow.Hide(); } catch { }
        _shown = false;
        _phase = Phase.In;
    }

    private static double Lerp(double a, double b, double t) => a + (b - a) * t;

    // Cubic ease-out: fast start, gentle settle — the iOS/Android island feel.
    private static double EaseOut(double t) => 1 - Math.Pow(1 - t, 3);

    // ---- Win32 ex-styles + DWM rounded corners ----------------------------

    private void ApplyExStyles()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        nint ex = GetWindowLongPtr(hwnd, GWL_EXSTYLE);
        // NOACTIVATE: never take focus (no focus-steal). TOOLWINDOW: keep it off
        // the taskbar and Alt-Tab (belt-and-braces with IsShownInSwitchers=false).
        ex |= WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
        SetWindowLongPtr(hwnd, GWL_EXSTYLE, ex);
    }

    private void RoundCorners()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        int pref = DWMWCP_ROUND;
        // No-op on Windows 10 (square corners); harmless there.
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref pref, sizeof(int));
    }

    private const int GWL_EXSTYLE = -20;
    private const nint WS_EX_NOACTIVATE = 0x08000000;
    private const nint WS_EX_TOOLWINDOW = 0x00000080;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWCP_ROUND = 2;

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
    private static extern nint GetWindowLongPtr(nint hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
    private static extern nint SetWindowLongPtr(nint hWnd, int nIndex, nint dwNewLong);

    [DllImport("dwmapi.dll", SetLastError = true)]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int pvAttribute, int cbAttribute);
}
