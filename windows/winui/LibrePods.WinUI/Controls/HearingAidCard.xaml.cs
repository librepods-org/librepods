using System;
using System.Linq;
using LibrePods.WinUI.Ipc;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace LibrePods.WinUI.Controls;

/// Hearing assistance (AirPods Pro 3, experimental). A per-person audiogram (8-band
/// hearing loss in dB HL, left + right) plus amplification / balance / tone sliders
/// and conversation boost. The daemon enables hearing-assist over AAP, switches to
/// Transparency, and writes it all to the ATT/GATT. Changes are debounced (each
/// apply is a ~1.3 s enable + ATT round-trip).
public sealed partial class HearingAidCard : UserControl
{
    public DaemonClient? Client { get; set; }

    private static readonly string[] Freqs =
        { "250 Hz", "500 Hz", "1 kHz", "2 kHz", "3 kHz", "4 kHz", "6 kHz", "8 kHz" };

    private readonly NumberBox[] _leftEq = new NumberBox[8];
    private readonly NumberBox[] _rightEq = new NumberBox[8];
    // Long-ish: filling in the audiogram touches many boxes in a row and each apply
    // is a heavy ATT round-trip on the daemon, so coalesce a burst of edits into one
    // apply once the user pauses (the daemon also drops superseded applies).
    private readonly DispatcherTimer _debounce = new() { Interval = TimeSpan.FromMilliseconds(1500) };

    public HearingAidCard()
    {
        InitializeComponent();
        BuildAudiogramGrid();
        _debounce.Tick += (_, _) => { _debounce.Stop(); Apply(); };
    }

    private void BuildAudiogramGrid()
    {
        AudiogramGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        AudiogramGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        AudiogramGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        AudiogramGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        AddCell(Header("Hz"), 0, 0);
        AddCell(Header("L"), 0, 1);
        AddCell(Header("R"), 0, 2);

        for (int i = 0; i < 8; i++)
        {
            int row = i + 1;
            AudiogramGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            AddCell(new TextBlock { Text = Freqs[i], VerticalAlignment = VerticalAlignment.Center }, row, 0);
            _leftEq[i] = MakeEqBox();
            _rightEq[i] = MakeEqBox();
            AddCell(_leftEq[i], row, 1);
            AddCell(_rightEq[i], row, 2);
        }
    }

    private static TextBlock Header(string t) => new() { Text = t, Opacity = 0.6 };

    private NumberBox MakeEqBox()
    {
        var nb = new NumberBox
        {
            Minimum = 0,
            Maximum = 90,
            Value = 0,
            SmallChange = 5,
            LargeChange = 10,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Hidden,
            IsEnabled = false,
        };
        nb.ValueChanged += (_, _) => { if (EnableSwitch.IsOn) { _debounce.Stop(); _debounce.Start(); } };
        return nb;
    }

    private void AddCell(FrameworkElement el, int row, int col)
    {
        Grid.SetRow(el, row);
        Grid.SetColumn(el, col);
        AudiogramGrid.Children.Add(el);
    }

    private void Enable_Toggled(object sender, RoutedEventArgs e)
    {
        bool on = EnableSwitch.IsOn;
        AmpSlider.IsEnabled = on;
        BalanceSlider.IsEnabled = on;
        ToneSlider.IsEnabled = on;
        ConvBoostSwitch.IsEnabled = on;
        foreach (var b in _leftEq) b.IsEnabled = on;
        foreach (var b in _rightEq) b.IsEnabled = on;
        _debounce.Stop();
        Apply(); // enabling/disabling applies immediately
    }

    private void Settings_Changed(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (EnableSwitch.IsOn) { _debounce.Stop(); _debounce.Start(); }
    }

    private void ConvBoost_Toggled(object sender, RoutedEventArgs e)
    {
        if (EnableSwitch.IsOn) { _debounce.Stop(); _debounce.Start(); }
    }

    private static float EqVal(NumberBox nb) => double.IsNaN(nb.Value) ? 0f : (float)nb.Value;

    private void Apply()
    {
        Client?.SetHearingAid(new SetHearingAidCmd
        {
            On = EnableSwitch.IsOn,
            LeftEq = _leftEq.Select(EqVal).ToArray(),
            RightEq = _rightEq.Select(EqVal).ToArray(),
            Amplification = (float)(AmpSlider.Value / 100.0),  // 0..1
            Balance = (float)(BalanceSlider.Value / 100.0),    // -1..1
            Tone = (float)(ToneSlider.Value / 100.0),          // -1..1
            ConversationBoost = ConvBoostSwitch.IsOn,
            AmbientNoiseReduction = 0f,
            OwnVoice = 0f,
        });
    }
}
