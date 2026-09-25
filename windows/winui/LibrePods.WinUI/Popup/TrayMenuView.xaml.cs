using System;
using System.Collections.Generic;
using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace LibrePods.WinUI.Popup;

/// The content of the custom tray menu: a themed, icon-per-row list rendered as a
/// UserControl so it follows the app theme and carries per-item artwork (things the
/// WinUI MenuFlyout / native Win32 menu can't do in the tray). Pure view — the
/// window owns positioning + dismissal; actions are surfaced as callbacks.
public sealed partial class TrayMenuView : UserControl
{
    public Action<byte>? OnAnc;
    public Action? OnMute;
    public Action? OnOpen;
    public Action? OnQuit;
    public Action? OnDismiss;

    public TrayMenuView()
    {
        InitializeComponent();
    }

    /// Fill the menu from the latest snapshot.
    public void Apply(Snapshot s)
    {
        HeaderName.Text = string.IsNullOrWhiteSpace(s.DevName) ? "LibrePods" : s.DevName;

        var model = string.IsNullOrEmpty(s.Model) ? AppSettings.LastModel : s.Model;
        try { HeaderImage.Source = new BitmapImage(new Uri(DeviceArt.MainImage(model))); }
        catch { }

        var parts = new List<string>();
        if (Present(s.Battery.Left) is byte l) parts.Add($"L {l}%");
        if (Present(s.Battery.Right) is byte r) parts.Add($"R {r}%");
        if (Present(s.Battery.Case) is byte c) parts.Add($"{Localize.Get("Tray_CaseShort")} {c}%");
        BatteryText.Text = parts.Count > 0 ? string.Join("  ·  ", parts) : Localize.Get("Tray_NoBatteryData");

        AncLabel.Text = Localize.Get("Tray_NoiseControl");
        AncOffText.Text = Localize.Get("Anc_Off");
        AncNcText.Text = Localize.Get("Anc_NoiseCancellation");
        AncTransText.Text = Localize.Get("Anc_Transparency");
        AncAdaptiveText.Text = Localize.Get("Anc_Adaptive");

        AncOffRow.IsEnabled = s.AllowOff;
        AncOffCheck.Visibility = Check(s.Anc == 1);
        AncNcCheck.Visibility = Check(s.Anc == 2);
        AncTransCheck.Visibility = Check(s.Anc == 3);
        AncAdaptiveCheck.Visibility = Check(s.Anc == 4);

        MuteText.Text = Localize.Get(s.Muted ? "Action_Unmute" : "Action_Mute");
        OpenText.Text = Localize.Get("Action_Open");
        QuitText.Text = Localize.Get("Action_Quit");
    }

    private static Visibility Check(bool on) => on ? Visibility.Visible : Visibility.Collapsed;
    private static byte? Present(byte? v) => v is byte b and <= 100 ? b : null;

    private void Fire(Action? a)
    {
        try { a?.Invoke(); } finally { OnDismiss?.Invoke(); }
    }

    private void AncOff_Click(object s, RoutedEventArgs e) => Fire(() => OnAnc?.Invoke(1));
    private void AncNc_Click(object s, RoutedEventArgs e) => Fire(() => OnAnc?.Invoke(2));
    private void AncTrans_Click(object s, RoutedEventArgs e) => Fire(() => OnAnc?.Invoke(3));
    private void AncAdaptive_Click(object s, RoutedEventArgs e) => Fire(() => OnAnc?.Invoke(4));
    private void Mute_Click(object s, RoutedEventArgs e) => Fire(() => OnMute?.Invoke());
    private void Open_Click(object s, RoutedEventArgs e) => Fire(() => OnOpen?.Invoke());
    private void Quit_Click(object s, RoutedEventArgs e) => Fire(() => OnQuit?.Invoke());
}
