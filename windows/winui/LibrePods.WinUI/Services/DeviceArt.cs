namespace LibrePods.WinUI.Services;

/// Maps an AirPods model number (from the 0x1D metadata packet, e.g. "A3064") to
/// the right artwork family under Assets/. Each real family ships a base image
/// plus _left / _right / _case parts; unknown models fall back to the generic
/// airpods.png (which has no parts — the part URIs return null so callers can
/// degrade gracefully).
///
/// Model → family table follows Apple's identifiers (support.apple.com/109525),
/// matching the daemon / Android side.
public static class DeviceArt
{
    private const string Base = "ms-appx:///Assets/";

    /// The artwork family prefix for a model number, or "airpods" (generic) when
    /// unknown. The generic family only has the base image, no parts.
    public static string Family(string? model) => Normalize(model) switch
    {
        "A1523" or "A1722" => "airpods_1",
        "A2032" or "A2031" => "airpods_2",
        "A2564" or "A2565" => "airpods_3",
        "A3053" or "A3050" or "A3054" or "A3055" or "A3056" or "A3057" => "airpods_4",
        "A2083" or "A2084" => "airpods_pro_1",
        "A2698" or "A2699" or "A2931" or "A2968"
            or "A3047" or "A3048" or "A3049" => "airpods_pro_2",
        "A3063" or "A3064" => "airpods_pro_3",
        _ => "airpods", // includes AirPods Max (no dedicated asset) + unknown
    };

    /// Whether this family ships the _left / _right / _case part images.
    private static bool HasParts(string family) => family != "airpods";

    /// The main product image — always available (generic fallback).
    public static string MainImage(string? model) => $"{Base}{Family(model)}.png";

    /// The left-bud image, or null when the family has no parts.
    public static string? LeftImage(string? model) => Part(model, "left");

    /// The right-bud image, or null when the family has no parts.
    public static string? RightImage(string? model) => Part(model, "right");

    /// The case image, or null when the family has no parts.
    public static string? CaseImage(string? model) => Part(model, "case");

    private static string? Part(string? model, string part)
    {
        var family = Family(model);
        return HasParts(family) ? $"{Base}{family}_{part}.png" : null;
    }

    private static string Normalize(string? model) =>
        string.IsNullOrWhiteSpace(model) ? "" : model.Trim().ToUpperInvariant();
}
