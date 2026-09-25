using LibrePods.WinUI.Ipc;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LibrePods.WinUI.Controls;

/// Heart-rate monitoring toggle + BPM readout (AirPods Pro 3, experimental). The
/// toggle is user-driven — the snapshot has no "monitoring on" flag, only the BPM
/// value — so Update touches only the reading.
public sealed partial class HeartRateCard : UserControl
{
    public DaemonClient? Client { get; set; }

    private bool _applying;

    public HeartRateCard()
    {
        InitializeComponent();
    }

    /// Render the BPM value from a daemon Snapshot (no reading → em dash).
    public void Update(Snapshot s)
    {
        _applying = true;
        try
        {
            HeartRateBpm.Text = s.HeartRate is ushort bpm ? bpm.ToString() : "—";
        }
        finally
        {
            _applying = false;
        }
    }

    private void HeartRate_Toggled(object sender, RoutedEventArgs e)
    {
        if (_applying) return;
        Client?.SetHeartRate(HeartRateSwitch.IsOn);
        if (!HeartRateSwitch.IsOn) HeartRateBpm.Text = "—";
    }
}
