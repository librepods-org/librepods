using LibrePods.WinUI.Ipc;
using LibrePods.WinUI.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;

namespace LibrePods.WinUI.Popup;

/// The visual of the connection "island": AirPods case image on the left, device
/// name + L/R/Case battery on the right. Pure presentation — it has no timers and
/// no window logic (that lives in IslandWindow); it only renders a Snapshot.
public sealed partial class IslandView : UserControl
{
    public IslandView()
    {
        InitializeComponent();

        // Kick off the product-render animation once laid out: a one-shot scale-in
        // entrance, then a continuous gentle float. Wrapped so an animation failure
        // can never break the popup (worst case: a static image).
        Loaded += (_, _) =>
        {
            try { Entrance.Begin(); } catch { }
            try { FloatLoop.Begin(); } catch { }
        };
    }

    /// Populate the card from a daemon Snapshot (name + battery + model image).
    public void Apply(Snapshot s)
    {
        // Connection-card mode: render + battery, no message line / mode glyph.
        MessageBody.Visibility = Visibility.Collapsed;
        DeviceImage.Visibility = Visibility.Visible;
        ModeIcon.Visibility = Visibility.Collapsed;
        BatteryRow.Visibility = Visibility.Visible;

        DeviceName.Text = string.IsNullOrWhiteSpace(s.DevName) ? Localize.Get("Island_DefaultName") : s.DevName;

        SetImage(s.Model);

        SetBattery(LeftItem, LeftBar, LeftText, s.Battery.Left, s.Battery.LeftCharging);
        SetBattery(RightItem, RightBar, RightText, s.Battery.Right, s.Battery.RightCharging);
        SetBattery(CaseItem, CaseBar, CaseText, s.Battery.Case, s.Battery.CaseCharging);
    }

    /// Message mode: show a title + body (e.g. an ANC change), hiding the render
    /// and battery — so daemon overlays render as the centred island instead of a
    /// Windows toast.
    public void ApplyMessage(string title, string body, string? model = null)
    {
        DeviceName.Text = string.IsNullOrWhiteSpace(title) ? Localize.Get("Island_DefaultName") : title;
        MessageBody.Text = body ?? "";
        MessageBody.Visibility = Visibility.Visible;
        BatteryRow.Visibility = Visibility.Collapsed;

        // A noise-control change shows the mode's glyph — a vector FontIcon so it
        // follows the theme (the raster ANC art was fixed-colour and washed out on
        // one theme). Everything else shows the device render.
        var glyph = ModeGlyph(body);
        if (glyph is not null)
        {
            ModeIcon.Glyph = glyph;
            ModeIcon.Visibility = Visibility.Visible;
            DeviceImage.Visibility = Visibility.Collapsed;
        }
        else
        {
            SetImage(model);
            DeviceImage.Visibility = Visibility.Visible;
            ModeIcon.Visibility = Visibility.Collapsed;
        }
    }

    /// The Segoe Fluent glyph for a noise-control mode body (localized match), or
    /// null when the message isn't a mode change. Char codes (not literal PUA chars)
    /// keep the source clean. Matches the tray menu's mode glyphs.
    private static string? ModeGlyph(string body)
    {
        if (body == Localize.Get("Anc_Off")) return ((char)0xE7E8).ToString();            // power
        if (body == Localize.Get("Anc_NoiseCancellation")) return ((char)0xE7F6).ToString(); // headphone
        if (body == Localize.Get("Anc_Transparency")) return ((char)0xE890).ToString();   // view
        if (body == Localize.Get("Anc_Adaptive")) return ((char)0xE72C).ToString();       // refresh
        return null;
    }

    /// Set the product render from the model number. When the live model isn't known
    /// yet (the 0x1D metadata lags the connect popup), fall back to the last-seen
    /// model cached across runs, then to the generic airpods.png.
    private void SetImage(string? model)
    {
        var m = string.IsNullOrEmpty(model) ? AppSettings.LastModel : model;
        TrySetSource(DeviceArt.MainImage(m));
    }

    private void TrySetSource(string uri)
    {
        try { DeviceImage.Source = new BitmapImage(new Uri(uri)); }
        catch { /* leave the XAML default (airpods.png) */ }
    }

    /// Show a battery component only when it carries a real reading. Values >100
    /// (e.g. the 0xFF "absent" sentinel) collapse the whole item, matching the
    /// main window's handling but hiding empty bars on the compact island.
    private static void SetBattery(FrameworkElement item, ProgressBar bar, TextBlock text, byte? value, bool charging)
    {
        if (value is byte v and <= 100)
        {
            bar.Value = v;
            // Append a bolt when charging (status byte 0x01 / 0x05).
            text.Text = charging ? $"{v}% ⚡" : $"{v}%";
            item.Visibility = Visibility.Visible;
        }
        else
        {
            bar.Value = 0;
            text.Text = "—";
            item.Visibility = Visibility.Collapsed;
        }
    }
}
