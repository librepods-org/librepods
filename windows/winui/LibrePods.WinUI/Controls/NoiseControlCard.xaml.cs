using System;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives; // ToggleButton lives here, not in Controls

namespace LibrePods.WinUI.Controls;

/// Noise-control mode as a segmented row of icon buttons (Off / Noise Cancellation
/// / Transparency / Adaptive). anc 1..4 selects a button; 0 (unknown) selects none.
/// "Off" is gated on the daemon's allow_off flag. Single-selection is enforced
/// here (ToggleButtons don't do it for us).
public sealed partial class NoiseControlCard : UserControl
{
    public DaemonClient? Client { get; set; }

    private bool _applying;
    private readonly ToggleButton[] _buttons;

    public NoiseControlCard()
    {
        InitializeComponent();

        // Index 0..3 == anc value 1..4.
        _buttons = new[] { AncOffBtn, AncNcBtn, AncTransBtn, AncAdaptiveBtn };

        // Tooltips reuse the existing localized mode names.
        ToolTipService.SetToolTip(AncOffBtn, Localize.Get("Anc_Off"));
        ToolTipService.SetToolTip(AncNcBtn, Localize.Get("Anc_NoiseCancellation"));
        ToolTipService.SetToolTip(AncTransBtn, Localize.Get("Anc_Transparency"));
        ToolTipService.SetToolTip(AncAdaptiveBtn, Localize.Get("Anc_Adaptive"));
    }

    /// Render the selected mode + "Off" availability from a daemon Snapshot.
    public void Update(Snapshot s)
    {
        _applying = true;
        try
        {
            AncOffBtn.IsEnabled = s.AllowOff;

            int selected = s.Anc is >= 1 and <= 4 ? s.Anc : 0; // 0 = none
            for (int i = 0; i < _buttons.Length; i++)
                _buttons[i].IsChecked = (i + 1) == selected;

            AncModeLabel.Text = selected switch
            {
                1 => Localize.Get("Anc_Off"),
                2 => Localize.Get("Anc_NoiseCancellation"),
                3 => Localize.Get("Anc_Transparency"),
                4 => Localize.Get("Anc_Adaptive"),
                _ => "—",
            };
        }
        finally
        {
            _applying = false;
        }
    }

    private void Anc_Click(object sender, RoutedEventArgs e)
    {
        if (_applying) return;
        if (sender is not ToggleButton btn) return;

        // Radio behaviour: this button wins, the rest clear. Re-clicking the active
        // button would otherwise uncheck it — force it back on (there is no "unset").
        byte anc = Convert.ToByte((string)btn.Tag);
        _applying = true;
        try
        {
            foreach (var b in _buttons) b.IsChecked = b == btn;
        }
        finally
        {
            _applying = false;
        }

        Client?.SetAnc(anc); // 1..4
    }
}
