using System;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;

namespace LibrePods.WinUI.Controls;

/// Device header card: product image + name (editable via a pencil), connection
/// dot/status, an inline Connect link while disconnected, and a Disconnect button
/// while connected. Renders a Snapshot; actions forward to the daemon.
public sealed partial class DeviceHeader : UserControl
{
    public DaemonClient? Client { get; set; }

    // The artwork family currently shown, so we only reload the image when the
    // detected model actually changes (snapshots arrive several times a second).
    private string? _artFamily;

    public DeviceHeader()
    {
        InitializeComponent();
    }

    /// Render the header from a daemon Snapshot (name + connection state).
    public void Update(Snapshot s)
    {
        // Don't clobber the name while the user is editing it.
        if (NameEdit.Visibility != Visibility.Visible)
            DeviceName.Text = string.IsNullOrWhiteSpace(s.DevName) ? "LibrePods" : s.DevName;

        // Model-aware product image (falls back to the generic airpods.png until
        // the 0x1D metadata arrives / for unknown models).
        var family = DeviceArt.Family(s.Model);
        if (family != _artFamily)
        {
            _artFamily = family;
            DeviceImage.Source = new BitmapImage(new Uri(DeviceArt.MainImage(s.Model)));
        }

        StatusText.Text = Localize.Get(s.Connected ? "Status_Connected" : "Status_Disconnected");
        // Green = talking to us; Orange = attached but the channel went silent
        // (Repair appears); Gray = not connected.
        StatusDot.Fill = new SolidColorBrush(
            !s.Connected ? Microsoft.UI.Colors.Gray
            : s.LinkStale ? Microsoft.UI.Colors.Orange
            : Microsoft.UI.Colors.LimeGreen);
        ConnectButton.Visibility = s.Connected ? Visibility.Collapsed : Visibility.Visible;
        // Rename + Disconnect only make sense while connected. Repair is offered
        // while disconnected AND while the link is stale -- the AirPods attached to
        // Windows but no longer talking to the daemon. In that state Connected stays
        // true forever, so keying Repair off it alone hid the button exactly when it
        // was needed, and the only way to surface it was to turn Bluetooth off.
        RenameBtn.Visibility = s.Connected ? Visibility.Visible : Visibility.Collapsed;
        DisconnectButton.Visibility = s.Connected ? Visibility.Visible : Visibility.Collapsed;
        RepairButton.Visibility = (!s.Connected || s.LinkStale) ? Visibility.Visible : Visibility.Collapsed;
        // "Restore audio" is the opposite case to Repair: the session is fine, the
        // sound is not. Only useful while connected.
        RebuildAudioButton.Visibility = s.Connected ? Visibility.Visible : Visibility.Collapsed;
        if (!s.Connected) ExitEdit();
    }

    /// The events pipe dropped — reflect that we're no longer hearing from the daemon.
    public void ShowWaitingForDaemon() => StatusText.Text = Localize.Get("Status_WaitingForDaemon");

    private void Connect_Click(object sender, RoutedEventArgs e) => Client?.Connect();
    private void Disconnect_Click(object sender, RoutedEventArgs e) => Client?.Disconnect();
    private void Repair_Click(object sender, RoutedEventArgs e) => Client?.RepairConnection();

    private void RebuildAudio_Click(object sender, RoutedEventArgs e) => Client?.RebuildAudio();

    // ---- Rename ------------------------------------------------------------

    private void Rename_Click(object sender, RoutedEventArgs e)
    {
        NameBox.Text = DeviceName.Text;
        NameView.Visibility = Visibility.Collapsed;
        NameEdit.Visibility = Visibility.Visible;
        NameBox.Focus(FocusState.Programmatic);
        NameBox.SelectAll();
    }

    private void Submit_Click(object sender, RoutedEventArgs e)
    {
        var name = NameBox.Text?.Trim() ?? "";
        if (name.Length > 0)
        {
            Client?.SetName(name);
            DeviceName.Text = name; // optimistic
            ExitEdit();
            // The daemon renames the device, but Windows only reflects the new
            // name after a disconnect/reconnect — tell the user so the unchanged
            // OS name doesn't read as a failure.
            RenameHint.Target = DeviceName;
            RenameHint.Title = Localize.Get("Rename_WindowsNoticeTitle");
            RenameHint.Subtitle = Localize.Get("Rename_WindowsNoticeBody");
            RenameHint.IsOpen = true;
            return;
        }
        ExitEdit();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => ExitEdit();

    private void NameBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == Windows.System.VirtualKey.Enter) Submit_Click(sender, e);
        else if (e.Key == Windows.System.VirtualKey.Escape) ExitEdit();
    }

    private void ExitEdit()
    {
        NameEdit.Visibility = Visibility.Collapsed;
        NameView.Visibility = Visibility.Visible;
    }
}
