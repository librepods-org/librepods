using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LibrePods.WinUI.Controls;

/// Hi-res microphone control: auto-enable-on-recording toggle + a manual
/// enable-now toggle. Both send a set_mic_mode carrying the pair; the manual
/// handlers need the current recording state, so the last snapshot's value is kept.
public sealed partial class MicCard : UserControl
{
    public DaemonClient? Client { get; set; }

    private bool _applying;

    // Last-known mic_recording, needed to compose set_mic_mode from the handlers.
    private bool _recording;

    public MicCard()
    {
        InitializeComponent();
    }

    /// Render the mic status + toggles from a daemon Snapshot.
    public void Update(Snapshot s)
    {
        _applying = true;
        try
        {
            _recording = s.MicRecording;
            MicStatusText.Text = Localize.Get(s.MicRecording ? "Mic_Recording" : "Mic_Idle");
            MicAutoSwitch.IsOn = s.AutoMode;
            MicManualToggle.IsChecked = s.MicRecording && !s.AutoMode;
        }
        finally
        {
            _applying = false;
        }
    }

    private void MicAuto_Toggled(object sender, RoutedEventArgs e)
    {
        if (_applying) return;
        // Keep the current manual/recording state while flipping auto.
        Client?.SetMicMode(auto: MicAutoSwitch.IsOn, manual: _recording);
    }

    private void MicManual_Click(object sender, RoutedEventArgs e) =>
        // Manual toggle: turn the hi-res stream on/off, auto off.
        Client?.SetMicMode(auto: false, manual: !_recording);
}
