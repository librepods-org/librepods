namespace LibrePods.WinUI.Services;

/// Convenience facade over the runtime <see cref="Loc"/> service for code-picked
/// strings (connection status, mute/unmute, mic state, tray, toasts). Delegates to
/// the current culture, so these follow a live language switch the next time they're
/// evaluated. Static XAML labels bind to {StaticResource Loc} directly.
internal static class Localize
{
    /// Look up a localized string by resw key (falls back to the key itself).
    public static string Get(string key) => Loc.Instance is { } loc ? loc.Get(key) : key;

    /// Look up a composite string and fill its <c>{0}</c>… placeholders.
    public static string Get(string key, params object[] args) =>
        Loc.Instance is { } loc ? loc.Get(key, args) : key;
}
