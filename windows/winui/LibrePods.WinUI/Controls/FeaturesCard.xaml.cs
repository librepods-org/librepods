using LibrePods.WinUI.Ipc;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LibrePods.WinUI.Controls;

/// Feature toggles: Conversational Awareness, Adaptive Volume, and Allow "Off"
/// mode. Each maps to a set_feature command.
public sealed partial class FeaturesCard : UserControl
{
    public DaemonClient? Client { get; set; }

    private bool _applying;

    public FeaturesCard()
    {
        InitializeComponent();
    }

    /// Render the three toggles from a daemon Snapshot.
    public void Update(Snapshot s)
    {
        _applying = true;
        try
        {
            ConvAwarenessSwitch.IsOn = s.ConversationalAwareness;
            AdaptiveVolumeSwitch.IsOn = s.AdaptiveVolume;
            AllowOffSwitch.IsOn = s.AllowOff;
        }
        finally
        {
            _applying = false;
        }
    }

    private void ConvAwareness_Toggled(object sender, RoutedEventArgs e)
    {
        if (_applying) return;
        Client?.SetFeature(Feature.ConversationalAwareness, ConvAwarenessSwitch.IsOn);
    }

    private void AdaptiveVolume_Toggled(object sender, RoutedEventArgs e)
    {
        if (_applying) return;
        Client?.SetFeature(Feature.AdaptiveVolume, AdaptiveVolumeSwitch.IsOn);
    }

    private void AllowOff_Toggled(object sender, RoutedEventArgs e)
    {
        if (_applying) return;
        Client?.SetFeature(Feature.AllowOff, AllowOffSwitch.IsOn);
    }
}
