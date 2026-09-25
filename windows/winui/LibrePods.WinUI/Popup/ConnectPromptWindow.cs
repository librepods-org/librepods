using System;
using System.IO;
using System.Runtime.InteropServices;
using LibrePods.WinUI.Services;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics;

namespace LibrePods.WinUI.Popup;

/// A centred, borderless, always-on-top card shown when the AirPods are nearby
/// (the case is opened) but not connected — the iOS-style "Connect?" prompt with
/// the device image, name and a Connect button. Unlike the passive connection
/// island, this one is INTERACTIVE, so it is activatable (a no-activate window
/// wouldn't route the button click). It self-closes after a timeout, on Connect,
/// or on dismiss. Every windowing call is guarded so a popup failure never crashes
/// the app — at worst there is simply no prompt.
public sealed class ConnectPromptWindow : Window
{
    private readonly DispatcherQueueTimer _autoClose;
    private bool _closed;

    public ConnectPromptWindow(string name, Action onConnect)
    {
        Title = "LibrePods";

        var display = string.IsNullOrWhiteSpace(name) ? Localize.Get("Island_DefaultName") : name;

        // ---- Content: a rounded acrylic card built in code (no XAML needed) ----
        var image = new Image
        {
            Width = 96,
            Height = 96,
            Stretch = Stretch.Uniform,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        try { image.Source = new BitmapImage(new Uri(ImageForName(display))); } catch { }

        var title = new TextBlock
        {
            Text = display,
            FontSize = 17,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            HorizontalAlignment = HorizontalAlignment.Center,
            TextAlignment = TextAlignment.Center,
        };
        var subtitle = new TextBlock
        {
            Text = Localize.Get("ConnectPrompt_Nearby"),
            FontSize = 12,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        if (Application.Current.Resources.TryGetValue("TextFillColorSecondaryBrush", out var sb) && sb is Brush secondary)
            subtitle.Foreground = secondary;

        var connect = new Button
        {
            Content = Localize.Get("ConnectPrompt_Connect"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        if (Application.Current.Resources.TryGetValue("AccentButtonStyle", out var st) && st is Style accent)
            connect.Style = accent;
        connect.Click += (_, _) => { try { onConnect(); } catch { } SafeClose(); };

        var dismiss = new Button
        {
            Content = Localize.Get("ConnectPrompt_Dismiss"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        dismiss.Click += (_, _) => SafeClose();

        var panel = new StackPanel { Spacing = 10, Padding = new Thickness(20, 18, 20, 20) };
        panel.Children.Add(title);
        panel.Children.Add(subtitle);
        panel.Children.Add(image);
        panel.Children.Add(connect);
        panel.Children.Add(dismiss);

        // Solid, opaque, theme-matched background — an acrylic backdrop on this
        // small tool window left a white window edge showing around the card.
        var root = new Grid { RequestedTheme = AppSettings.Theme };
        if (Application.Current.Resources.TryGetValue("SolidBackgroundFillColorBaseBrush", out var bg) && bg is Brush bgBrush)
            root.Background = bgBrush;
        else
            root.Background = new SolidColorBrush(Microsoft.UI.Colors.Black);
        root.Children.Add(panel);
        Content = root;

        try { ConfigurePresenter(); } catch { }

        _autoClose = DispatcherQueue.CreateTimer();
        _autoClose.Interval = TimeSpan.FromSeconds(20);
        _autoClose.IsRepeating = false;
        _autoClose.Tick += (_, _) => SafeClose();

        Closed += (_, _) => { _closed = true; try { _autoClose.Stop(); } catch { } };

        root.Loaded += (_, _) => { try { Prepare(); } catch { } };
    }

    /// Show the prompt (activating it so the button works).
    public void ShowPrompt()
    {
        if (_closed) return;
        try
        {
            AppWindow.Show();
            Activate();
            _autoClose.Start();
        }
        catch { SafeClose(); }
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

    /// Size to content and centre on the display's work area, then round the corners.
    private void Prepare()
    {
        double scale = (Content as FrameworkElement)?.XamlRoot?.RasterizationScale ?? 1.0;
        if (scale <= 0) scale = 1.0;

        var fe = (FrameworkElement)Content;
        fe.Measure(new Windows.Foundation.Size(300, double.PositiveInfinity));
        double wDip = 300, hDip = fe.DesiredSize.Height > 0 ? fe.DesiredSize.Height : 260;

        int w = (int)Math.Ceiling(wDip * scale);
        int h = (int)Math.Ceiling(hDip * scale);
        try { AppWindow.Resize(new SizeInt32(w, h)); } catch { }

        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest);
        var work = area.WorkArea;
        int x = work.X + (work.Width - w) / 2;
        int y = work.Y + work.Height - h - (int)Math.Round(56 * scale); // bottom-centre
        try { AppWindow.Move(new PointInt32(x, y)); } catch { }

        try { RoundCorners(); } catch { }
    }

    private void SafeClose()
    {
        if (_closed) return;
        try { Close(); } catch { _closed = true; }
    }

    // Best-effort map from device name to a bundled AirPods image (else generic).
    private static string ImageForName(string devName)
    {
        const string root = "ms-appx:///Assets/";
        var n = (devName ?? string.Empty).ToLowerInvariant();
        if (n.Contains("pro"))
        {
            if (n.Contains("3")) return root + "airpods_pro_3_case.png";
            if (n.Contains("1")) return root + "airpods_pro_1_case.png";
            return root + "airpods_pro_2_case.png";
        }
        if (n.Contains("4")) return root + "airpods_4_case.png";
        if (n.Contains("3")) return root + "airpods_3_case.png";
        if (n.Contains("2")) return root + "airpods_2_case.png";
        if (n.Contains("1")) return root + "airpods_1_case.png";
        return root + "airpods.png";
    }

    private void RoundCorners()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        int pref = DWMWCP_ROUND;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref pref, sizeof(int));
        // Windows 11 paints a border colour around every window — that's the white
        // edge around the card. DWMWA_COLOR_NONE removes it.
        int none = unchecked((int)0xFFFFFFFE); // DWMWA_COLOR_NONE
        DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, ref none, sizeof(int));
    }

    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWA_BORDER_COLOR = 34;
    private const int DWMWCP_ROUND = 2;

    [DllImport("dwmapi.dll", SetLastError = true)]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int pvAttribute, int cbAttribute);
}
