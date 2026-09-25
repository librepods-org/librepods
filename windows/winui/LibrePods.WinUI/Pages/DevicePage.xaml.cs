using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LibrePods.WinUI.Pages;

/// The device view: header + transient InfoBars + the responsive 2-column card
/// grid. It owns no daemon logic beyond fanning a Snapshot out to each card and
/// rendering the overlay / connect-prompt InfoBars.
public sealed partial class DevicePage : UserControl
{
    private DaemonClient? _client;

    /// The daemon client, propagated to the header + every command-sending card.
    public DaemonClient? Client
    {
        get => _client;
        set
        {
            _client = value;
            Header.Client = value;
            VolumeCard.Client = value;
            AdaptiveNoiseCard.Client = value;
            NoiseControlCard.Client = value;
            FeaturesCard.Client = value;
            MicCard.Client = value;
            HeartRateCard.Client = value;
            HearingAidCard.Client = value;
        }
    }

    public DevicePage()
    {
        InitializeComponent();
        // Experimental cards (heart-rate + hearing-aid) are hidden by default. Show
        // them only when the user opts in via Settings ▸ Experimental.
        ApplyExperimentalVisibility();

        // Daemon overlays are one-shot strings resolved when they arrive, so one
        // shown before a language change stays in the old language. Dismiss it on a
        // culture switch (the next overlay renders in the new language).
        Services.Loc.Instance.PropertyChanged += (_, _) =>
            DispatcherQueue.TryEnqueue(() => OverlayBar.IsOpen = false);
    }

    /// Re-read the experimental opt-in (call after the Settings toggle changes so the
    /// cards appear/disappear without an app restart).
    public void RefreshExperimentalVisibility() => ApplyExperimentalVisibility();

    /// Show/hide every experimental card from the single experimental opt-in.
    private void ApplyExperimentalVisibility()
    {
        var vis = AppSettings.EnableExperimental ? Visibility.Visible : Visibility.Collapsed;
        HeartRateCard.Visibility = vis;
        HearingAidCard.Visibility = vis;
    }

    /// Fan a fresh Snapshot out to the header and every card that renders state.
    /// (AdaptiveNoiseCard is stateless — send-only — so it is skipped.)
    public void Update(Snapshot s)
    {
        Header.Update(s);
        BatteryCard.Update(s);
        VolumeCard.Update(s);
        NoiseControlCard.Update(s);
        FeaturesCard.Update(s);
        MicCard.Update(s);
        MultipointCard.Update(s);
        HeartRateCard.Update(s);

        // Disable every interactive control while there's no proper connection — a
        // disconnected/desynced link can't apply commands, so the controls shouldn't
        // look actionable (Battery stays as an informational read-out). Use "Reparar
        // ligação" in the header to force a clean reconnect.
        bool on = s.Connected;
        VolumeCard.IsEnabled = on;
        AdaptiveNoiseCard.IsEnabled = on;
        NoiseControlCard.IsEnabled = on;
        FeaturesCard.IsEnabled = on;
        MicCard.IsEnabled = on;
        HeartRateCard.IsEnabled = on;
        HearingAidCard.IsEnabled = on;
    }

    /// Show a transient daemon overlay in the InfoBar.
    public void ShowOverlay(string title, string body)
    {
        OverlayBar.Title = title;
        OverlayBar.Message = body;
        OverlayBar.Severity = InfoBarSeverity.Informational;
        OverlayBar.IsOpen = true;
    }

    /// Show the "nearby — connect?" prompt with a Connect action.
    public void ShowConnectPrompt(string name)
    {
        ConnectPromptBar.Title = Localize.Get("ConnectPrompt_Title", name);
        ConnectPromptBar.Message = Localize.Get("ConnectPrompt_Message");
        var btn = new Button { Content = Localize.Get("Action_Connect") };
        btn.Click += (_, _) =>
        {
            _client?.Connect();
            ConnectPromptBar.IsOpen = false;
        };
        ConnectPromptBar.ActionButton = btn;
        ConnectPromptBar.IsOpen = true;
    }

    /// Hide the "nearby — connect?" prompt (e.g. once the device is connected).
    public void DismissConnectPrompt() => ConnectPromptBar.IsOpen = false;

    /// The events pipe dropped — surface it in the header.
    public void ShowWaitingForDaemon() => Header.ShowWaitingForDaemon();

    /// Responsive layout: two side-by-side columns only when the page is wide
    /// enough (measured on the page's own content width, since the nav pane is
    /// separate); otherwise the right column stacks below the left (one column).
    private void OnSizeChanged(object sender, SizeChangedEventArgs e)
    {
        bool wide = e.NewSize.Width >= 720;
        Grid.SetRow(RightColumn, wide ? 0 : 1);
        Grid.SetColumn(RightColumn, wide ? 1 : 0);
        Col1.Width = wide ? new GridLength(1, GridUnitType.Star) : new GridLength(0);
        // In one-column mode the collapsed Col1 still reserves ColumnSpacing (16px),
        // leaving a phantom gap on the right — drop the spacing when single-column.
        CardsGrid.ColumnSpacing = wide ? 16 : 0;
    }
}
