using LibrePods.WinUI.Ipc;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LibrePods.WinUI.Controls;

/// Adaptive-Audio noise strength presets (Low/Medium/High → 25/50/75 on the raw
/// 0x2E control). Stateless: it only sends, so it has no Update.
public sealed partial class AdaptiveNoiseCard : UserControl
{
    public DaemonClient? Client { get; set; }

    public AdaptiveNoiseCard()
    {
        InitializeComponent();
    }

    private void NoiseLow_Click(object sender, RoutedEventArgs e) =>
        Client?.SetControl(ControlId.AdaptiveNoiseStrength, 25);
    private void NoiseMid_Click(object sender, RoutedEventArgs e) =>
        Client?.SetControl(ControlId.AdaptiveNoiseStrength, 50);
    private void NoiseHigh_Click(object sender, RoutedEventArgs e) =>
        Client?.SetControl(ControlId.AdaptiveNoiseStrength, 75);
}
