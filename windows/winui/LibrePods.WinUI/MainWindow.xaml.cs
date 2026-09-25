using System.IO;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics;

namespace LibrePods.WinUI;

/// The Fluent shell: a NavigationView that content-swaps between the DevicePage
/// and SettingsPage UserControls. It owns no card logic — it wires the daemon
/// client into the pages, forwards daemon events (marshalled to the UI thread) to
/// the device page, and applies the settings page's theme choice to the window
/// root. Closing the window hides it to the tray rather than exiting.
public sealed partial class MainWindow : Window
{
    private readonly DaemonClient _client;

    public MainWindow(DaemonClient client)
    {
        _client = client;
        InitializeComponent();

        // Native Fluent look: Mica backdrop, theme-aware via system resources.
        SystemBackdrop = new MicaBackdrop();

        // Hand the client to the pages: DevicePage fans it out to its cards; the
        // SettingsPage uses it for "Refresh state".
        DevicePageView.Client = _client;
        SettingsPageView.Client = _client;

        // The settings theme picker owns the choice; the window root owns the theme.
        SettingsPageView.ThemeChanged += theme =>
        {
            if (RootGrid is not null) RootGrid.RequestedTheme = theme;
        };
        // Restore the theme saved on a previous run (it wasn't persisted before).
        if (RootGrid is not null) RootGrid.RequestedTheme = AppSettings.Theme;
        SettingsPageView.InitThemeSelector(AppSettings.ThemeIndex);

        // The experimental heart-rate opt-in lives in Settings; the DevicePage owns
        // the card. Refresh its visibility live when the toggle flips.
        SettingsPageView.ExperimentalVisibilityChanged += () => DevicePageView.RefreshExperimentalVisibility();
        SettingsPageView.InitExperimentalSetting();

        // Wider default so the responsive 2-column device layout shows at launch.
        AppWindow.Resize(new SizeInt32(1000, 800));
        // Enforce a minimum size — the layout breaks if the window is dragged
        // absurdly narrow (no app is usable at ~100px). Clamp on resize.
        AppWindow.Changed += (sender, e) =>
        {
            if (!e.DidSizeChange) return;
            const int minW = 420, minH = 540;
            var sz = sender.Size;
            if (sz.Width < minW || sz.Height < minH)
                sender.Resize(new SizeInt32(Math.Max(sz.Width, minW), Math.Max(sz.Height, minH)));
        };
        TrySetWindowIcon();

        // Start on the device page.
        NavView.SelectedItem = DeviceNavItem;

        // The built-in NavigationView Settings item is OS-localized; re-label it
        // from our Loc service so it follows the in-app language too (live). The
        // SettingsItem only exists once the control template is applied (Loaded).
        NavView.Loaded += (_, _) => LocalizeSettingsNavItem();
        Services.Loc.Instance.PropertyChanged += (_, _) =>
            DispatcherQueue.TryEnqueue(() =>
            {
                LocalizeSettingsNavItem();
                if (_lastSnapshot is { } s) RenderSnapshot(s);
            });

        // Close hides to tray (the app keeps running as an IPC client).
        AppWindow.Closing += OnClosing;

        // Daemon → UI. These fire on a background thread; marshal to the UI queue.
        _client.SnapshotReceived += OnSnapshot;
        _client.OverlayReceived += OnOverlay;
        _client.ConnectPromptReceived += OnConnectPrompt;
        _client.ConnectionChanged += OnConnectionChanged;
    }

    private void TrySetWindowIcon()
    {
        try
        {
            var ico = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
            if (File.Exists(ico)) AppWindow.SetIcon(ico);
        }
        catch { }
    }

    /// Show + focus the window (from the tray icon / "Open").
    public void ShowFromTray()
    {
        AppWindow.Show();
        Activate();
    }

    private void OnClosing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        args.Cancel = true;   // don't destroy the window…
        AppWindow.Hide();     // …just hide it back to the tray.
    }

    // ---- Navigation --------------------------------------------------------

    private void Nav_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        var settings = args.IsSettingsSelected;
        DevicePageView.Visibility = settings ? Visibility.Collapsed : Visibility.Visible;
        SettingsPageView.Visibility = settings ? Visibility.Visible : Visibility.Collapsed;
    }

    /// Re-label the built-in NavigationView Settings item from the Loc service.
    private void LocalizeSettingsNavItem()
    {
        if (NavView.SettingsItem is NavigationViewItem item)
            item.Content = Localize.Get("SettingsTitle.Text");
    }

    // ---- Daemon events (marshalled to the UI thread) -----------------------

    private void OnSnapshot(Snapshot s) =>
        DispatcherQueue.TryEnqueue(() =>
        {
            _lastSnapshot = s;
            RenderSnapshot(s);
        });

    // Re-render the last snapshot on a language change so code-picked strings (the
    // header status, mic state, ANC mode name…) — which are only set when a snapshot
    // arrives — refresh into the new language instead of lingering in the old one.
    private void RenderSnapshot(Snapshot s)
    {
            _connected = s.Connected;

            // The device NavigationViewItem mirrors the header: name, a model-aware
            // icon, and a compact battery summary (visible in the expanded pane).
            NavDeviceName.Text = string.IsNullOrWhiteSpace(s.DevName) ? "LibrePods" : s.DevName;

            var family = DeviceArt.Family(s.Model);
            if (family != _navArtFamily)
            {
                _navArtFamily = family;
                try { NavDeviceIcon.Source = new BitmapImage(new Uri(DeviceArt.MainImage(s.Model))); }
                catch { }
            }

            // Lowest earbud reading (or the headphone band for Max) — one number is
            // enough at nav width; the full L/R/Case breakdown is on the card.
            byte? summary = LowestReading(s.Battery.Left, s.Battery.Right, s.Battery.Headphone);
            if (summary is byte v)
            {
                NavDeviceBattery.Text = $"{v}%";
                NavDeviceBattery.Visibility = Visibility.Visible;
            }
            else
            {
                NavDeviceBattery.Visibility = Visibility.Collapsed;
            }

            // A live connection dismisses any stale "Connect?" prompt (in-app InfoBar
            // + the centred popup window).
            if (s.Connected)
            {
                DevicePageView.DismissConnectPrompt();
                try { _connectPrompt?.Close(); } catch { }
            }

            DevicePageView.Update(s);
            SettingsPageView.UpdateDeviceInfo(s);
    }

    /// The lowest valid (<=100) battery reading among the given components, or null
    /// when none report. The 0xFF "absent" sentinel (>100) is ignored.
    private static byte? LowestReading(params byte?[] values)
    {
        byte? lowest = null;
        foreach (var value in values)
            if (value is byte v and <= 100 && (lowest is null || v < lowest))
                lowest = v;
        return lowest;
    }

    private void OnOverlay(string title, string body) =>
        DispatcherQueue.TryEnqueue(() => DevicePageView.ShowOverlay(title, body));

    private Popup.ConnectPromptWindow? _connectPrompt;
    private bool _connected;
    private string? _navArtFamily;
    private Snapshot? _lastSnapshot;

    private void OnConnectPrompt(string name) =>
        DispatcherQueue.TryEnqueue(() =>
        {
            // Already connected — a proximity "Connect?" prompt is stale (the daemon
            // can still emit it from a BLE advertisement while the AACP session is
            // live). Suppress both the popup and the in-app InfoBar.
            if (_connected) return;

            // The iOS-style centred "Connect?" popup — shows even when the app is
            // hidden to the tray. Also mirror it in the in-app InfoBar.
            try
            {
                _connectPrompt?.Close();
                _connectPrompt = new Popup.ConnectPromptWindow(name, () => _client.Connect());
                _connectPrompt.Closed += (_, _) => _connectPrompt = null;
                _connectPrompt.ShowPrompt();
            }
            catch { }
            DevicePageView.ShowConnectPrompt(name);
        });

    private void OnConnectionChanged(bool connected) =>
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!connected) DevicePageView.ShowWaitingForDaemon();
        });
}
