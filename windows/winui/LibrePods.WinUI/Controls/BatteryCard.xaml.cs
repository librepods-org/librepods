using System;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace LibrePods.WinUI.Controls;

/// Left/Right/Case battery readout with model-aware device artwork. Pure
/// presentation — renders a Snapshot, sends no commands.
public sealed partial class BatteryCard : UserControl
{
    // The artwork family currently shown, so the part images only reload when the
    // detected model changes (snapshots arrive several times a second).
    private string? _artFamily;

    public BatteryCard()
    {
        InitializeComponent();
    }

    /// Render the three battery gauges (+ artwork) from a daemon Snapshot.
    public void Update(Snapshot s)
    {
        var family = DeviceArt.Family(s.Model);
        if (family != _artFamily)
        {
            _artFamily = family;
            SetArt(LeftImg, DeviceArt.LeftImage(s.Model));
            SetArt(RightImg, DeviceArt.RightImage(s.Model));
            SetArt(CaseImg, DeviceArt.CaseImage(s.Model));
        }

        SetBattery(LeftBar, LeftText, s.Battery.Left, s.Battery.LeftCharging);
        SetBattery(RightBar, RightText, s.Battery.Right, s.Battery.RightCharging);
        SetBattery(CaseBar, CaseText, s.Battery.Case, s.Battery.CaseCharging);
    }

    // Show the part image when the model has one; hide it (falling back to just the
    // gauge) otherwise.
    private static void SetArt(Image img, string? uri)
    {
        if (uri is null)
        {
            img.Source = null;
            img.Visibility = Visibility.Collapsed;
        }
        else
        {
            img.Source = new BitmapImage(new Uri(uri));
            img.Visibility = Visibility.Visible;
        }
    }

    private static void SetBattery(ProgressBar bar, TextBlock text, byte? value, bool charging)
    {
        // Treat >100 (e.g. the 0xFF "absent" sentinel) as no reading.
        if (value is byte v and <= 100)
        {
            bar.Value = v;
            bar.IsIndeterminate = false;
            // Append a bolt when charging (status byte 0x01 / 0x05).
            text.Text = charging ? $"{v}%  ⚡" : $"{v}%";
        }
        else
        {
            bar.Value = 0;
            text.Text = "—";
        }
    }
}
