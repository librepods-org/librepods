using System.Collections.Generic;
using System.Linq;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LibrePods.WinUI.Controls;

/// Shows every host the AirPods report themselves connected to (AAP 0x2E).
///
/// This is not something Windows can tell us: it only knows about its own link,
/// and during a handoff the endpoint, the Bluetooth device and our AAP channel
/// all keep reporting healthy while the audio is actually playing on a phone.
/// The buds are the only party that knows, and they announce it -- in one capture
/// 3.2 seconds before Windows noticed anything.
public sealed partial class MultipointCard : UserControl
{
    public MultipointCard()
    {
        InitializeComponent();
    }

    /// Render the device list from a daemon Snapshot. The card hides itself until
    /// the first 0x2E arrives, so it never shows an empty shell.
    public void Update(Snapshot s)
    {
        List<ConnectedDevice> devices = s.Multipoint ?? new();
        if (devices.Count == 0)
        {
            Visibility = Visibility.Collapsed;
            return;
        }

        Visibility = Visibility.Visible;
        DeviceList.ItemsSource = devices;

        int others = devices.Count(d => !d.IsThisPc);
        SummaryText.Text = others == 0
            ? Localize.Get("Multipoint_OnlyThisPc")
            : string.Format(Localize.Get("Multipoint_Shared"), others);
    }
}
