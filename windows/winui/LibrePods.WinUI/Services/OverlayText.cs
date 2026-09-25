using System.Collections.Generic;

namespace LibrePods.WinUI.Services;

/// The daemon (Rust) emits overlay/notification text in English. Map the fixed
/// phrases to the app's localized resources so toasts and the island match the UI
/// language. Interpolated bodies (battery %, device names) are passed through
/// unchanged for now — localizing those needs the daemon to send keys, not text.
public static class OverlayText
{
    // English daemon body → resource key. Reuses the ANC / status keys the rest of
    // the UI already uses.
    private static readonly Dictionary<string, string> Exact = new()
    {
        ["Off"] = "Anc_Off",
        ["Noise Cancellation"] = "Anc_NoiseCancellation",
        ["Transparency"] = "Anc_Transparency",
        ["Adaptive"] = "Anc_Adaptive",
        ["Disconnected"] = "Status_Disconnected",
        ["AirPod in case"] = "Overlay_InCase",
        ["Heart rate monitoring on"] = "Overlay_HrOn",
        ["Heart rate monitoring off"] = "Overlay_HrOff",
        ["Hi-res microphone on"] = "Overlay_MicOn",
        ["Microphone in use — hi-res on"] = "Overlay_MicInUse",
        ["Microphone released — restoring stereo…"] = "Overlay_MicReleasing",
        ["Stereo restored"] = "Overlay_StereoRestored",
    };

    /// Localize a daemon overlay body, falling back to the original text.
    public static string Resolve(string body)
    {
        if (Exact.TryGetValue(body, out var key))
        {
            var s = Localize.Get(key);
            if (!string.IsNullOrEmpty(s)) return s;
        }

        // Interpolated bodies (battery %, connect/case events): translate the known
        // English fragments the daemon emits, leaving the numbers. Order matters —
        // multi-word phrases before the bare "Case ".
        var t = body;
        t = t.Replace("Case battery low —", Localize.Get("Overlay_CaseBatteryLow"));
        t = t.Replace("Battery low —", Localize.Get("Overlay_BatteryLow"));
        t = t.Replace("Case opened", Localize.Get("Overlay_CaseOpened"));
        t = t.Replace("Case closed", Localize.Get("Overlay_CaseClosed"));
        t = t.Replace("Connected", Localize.Get("Status_Connected"));
        t = t.Replace("Renamed to", Localize.Get("Overlay_RenamedTo"));
        t = t.Replace("Left ", Localize.Get("Battery_LeftShort") + " ");
        t = t.Replace("Right ", Localize.Get("Battery_RightShort") + " ");
        t = t.Replace("Case ", Localize.Get("Battery_CaseShort") + " ");
        return t;
    }
}
