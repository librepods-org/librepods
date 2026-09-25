using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace LibrePods.WinUI.Controls;

/// Volume slider + mute. The slider is bidirectional, so pushing a snapshot value
/// into it must not echo back to the daemon as a SetVolume — two guards prevent the
/// feedback loop (assigning Slider.Value fires ValueChanged synchronously).
public sealed partial class VolumeCard : UserControl
{
    public DaemonClient? Client { get; set; }

    // Set while a snapshot is being applied to any control in this card.
    private bool _applying;

    // Extra guard specifically for the slider: assigning Slider.Value fires
    // ValueChanged synchronously and must never be echoed back as a SetVolume.
    private bool _suppressVolume;

    public VolumeCard()
    {
        InitializeComponent();
    }

    /// Render the slider / mute state from a daemon Snapshot.
    public void Update(Snapshot s)
    {
        _applying = true;
        try
        {
            _suppressVolume = true;
            VolumeSlider.Value = s.Volume;
            _suppressVolume = false;
            VolumeText.Text = s.Muted ? Localize.Get("Volume_Muted") : $"{s.Volume}%";
            MuteToggle.IsChecked = s.Muted;
        }
        finally
        {
            _applying = false;
        }
    }

    private void Volume_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (_applying || _suppressVolume) return;
        Client?.SetVolume((byte)e.NewValue);
    }

    private void Mute_Click(object sender, RoutedEventArgs e) => Client?.ToggleMute();
}
