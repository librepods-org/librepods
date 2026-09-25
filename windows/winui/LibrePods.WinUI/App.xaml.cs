using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Popup;
using LibrePods.WinUI.Services;
using LibrePods.WinUI.Tray;
using Microsoft.UI.Xaml;

namespace LibrePods.WinUI;

/// The app is primarily a tray client of `librepodsd`. It starts hidden to the
/// tray: the DaemonClient connects (spawning the daemon if needed), the main
/// window is created but not shown, and the tray icon drives Open/Quit.
public partial class App : Application
{
    public DaemonClient Daemon { get; } = new();

    private MainWindow? _window;
    private TrayController? _tray;

    // The connection "island" popup. A single instance is reused so reconnect
    // spam re-populates it rather than stacking multiple popups; it self-closes
    // after its animation and clears this reference. `_podsConnected` tracks the
    // AirPods connected state so we fire only on the false→true transition.
    private IslandWindow? _island;
    private bool _podsConnected;
    // Last-seen model number (from the 0x1D metadata), so message-mode island
    // popups can show the right device render even without a full Snapshot.
    private string _lastModel = "";

    public App()
    {
        // InitializeComponent loads App.xaml, which creates the single Loc instance
        // (<services:Loc x:Key="Loc"/>); its ctor publishes Loc.Instance for code.
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // The main window is created hidden; closing it hides back to the tray.
        _window = new MainWindow(Daemon);

        _tray = new TrayController(
            onOpen: () => _window.ShowFromTray(),
            onQuit: ExitApp,
            client: Daemon);
        _tray.Show();

        // Register native toast notifications (Windows App SDK AppNotificationManager)
        // before the daemon starts producing overlays. A toast click ("action=open")
        // reactivates the window — marshal to the UI thread, same as the tray "Open".
        Notifier.Register(() =>
            _window.DispatcherQueue.TryEnqueue(() => _window.ShowFromTray()));

        // Surface daemon overlays as the centred floating island (not a Windows
        // toast — the toast was the corner card the user didn't want). Marshalled
        // to the UI thread.
        Daemon.OverlayReceived += (title, body) =>
            _window.DispatcherQueue.TryEnqueue(() => ShowIslandMessage(title, body));

        // Drive the tray's tooltip / menu / icon badge from daemon state. Snapshots
        // arrive on a background thread; marshal to the UI thread (same as the window).
        Daemon.SnapshotReceived += s =>
            _window.DispatcherQueue.TryEnqueue(() => _tray?.UpdateSnapshot(s));

        // Show the connection island on the AirPods connect transition only
        // (Snapshot.Connected false→true). Tracking the snapshot's connected flag
        // — rather than the pipe-level ConnectionChanged — means a daemon pipe
        // reconnect (which re-pushes the same connected=true snapshot) does not
        // re-fire the popup. Marshalled to the UI thread.
        Daemon.SnapshotReceived += s =>
            _window.DispatcherQueue.TryEnqueue(() =>
            {
                if (!string.IsNullOrEmpty(s.Model))
                {
                    _lastModel = s.Model;
                    AppSettings.SetLastModel(s.Model); // survive restarts for the connect island
                }
                var now = s.Connected;
                if (now && !_podsConnected) ShowIsland(s);
                _podsConnected = now;
            });

        // If a previous daemon is still running (an orphan from a crashed or
        // force-killed session), shut it down GRACEFULLY first so THIS app owns a
        // single clean instance — the new daemon then opens the driver fresh,
        // instead of hitting "driver open FAILED" against a leaked handle.
        DaemonClient.ShutdownExistingDaemon();

        // Begin talking to the daemon (background reader + writer loops).
        Daemon.Start();

        // Show the window on launch — unless started hidden. Autostart (startup.ps1)
        // passes "--tray", so at login only the tray icon appears and the window
        // opens on demand (double-click the tray, or "Open"); closing it hides it
        // back to the tray.
        var argv = Environment.GetCommandLineArgs();
        bool startHidden = Array.IndexOf(argv, "--tray") >= 0
                        || Array.IndexOf(argv, "--minimized") >= 0;
        if (!startHidden) _window.Activate();
    }

    /// Create (or reuse) the single island window and play the connect popup.
    /// Must run on the UI thread. Fully guarded — a popup failure never crashes
    /// the app; at worst there is simply no island.
    private void ShowIsland(Snapshot snapshot)
    {
        try
        {
            if (_island is null)
            {
                _island = new IslandWindow();
                // The island hides + reuses itself after each slide-out (it never
                // calls Window.Close, which fail-fasts in WinUI's theme teardown).
                // Keep the Closed handler only as a shutdown safety net.
                _island.Closed += (_, _) => _island = null;
            }
            _island.ShowConnected(snapshot);
        }
        catch
        {
            _island = null;
        }
    }

    /// Show a daemon overlay as the centred floating island (message mode).
    private void ShowIslandMessage(string title, string body)
    {
        try
        {
            if (_island is null)
            {
                _island = new IslandWindow();
                _island.Closed += (_, _) => _island = null;
            }
            _island.ShowMessage(title, body, _lastModel);
        }
        catch
        {
            _island = null;
        }
    }

    /// The tray "Quit": stop the daemon too and exit. On Windows one front-end
    /// runs at a time, and this app spawned the daemon, so quitting it should not
    /// leave a headless librepodsd lingering. (Closing the *window* only hides to
    /// the tray — the daemon stays; Quit is the full teardown.)
    public void ExitApp()
    {
        try
        {
            Daemon.Shutdown();                       // tell librepodsd to exit
            System.Threading.Thread.Sleep(150);      // let the writer flush it
        }
        catch { }
        Notifier.Unregister();
        _tray?.Dispose();
        Daemon.Dispose();
        Exit();
    }
}
