using System.Text.Json;
using System.Text.Json.Serialization;

namespace LibrePods.WinUI.Ipc;

// The IPC contract mirrors windows/ipc/src/lib.rs (serde, snake_case, tagged by
// `event` / `cmd`). Every field is spelled with an explicit [JsonPropertyName] so
// the wire names never depend on a naming policy.

/// Battery levels (percent), each optional — a packet may carry only some.
public sealed class Battery
{
    [JsonPropertyName("left")] public byte? Left { get; set; }
    [JsonPropertyName("right")] public byte? Right { get; set; }
    [JsonPropertyName("case")] public byte? Case { get; set; }
    [JsonPropertyName("headphone")] public byte? Headphone { get; set; }

    // Per-component charging flag (mirrors the daemon's ipc::Battery).
    [JsonPropertyName("left_charging")] public bool LeftCharging { get; set; }
    [JsonPropertyName("right_charging")] public bool RightCharging { get; set; }
    [JsonPropertyName("case_charging")] public bool CaseCharging { get; set; }
    [JsonPropertyName("headphone_charging")] public bool HeadphoneCharging { get; set; }
}

/// The daemon's authoritative state, pushed on connect and on every change.
public sealed class Snapshot
{
    [JsonPropertyName("connected")] public bool Connected { get; set; }

    /// The session is nominally up but the AAP channel has gone silent: the AirPods
    /// are attached to Windows yet not talking to the daemon, so the hi-res mic and
    /// live notifications are dead. Surfaces "Repair connection" -- Connected alone
    /// stays true forever in that state and used to hide the button exactly then.
    [JsonPropertyName("link_stale")] public bool LinkStale { get; set; }
    [JsonPropertyName("dev_name")] public string DevName { get; set; } = "";
    [JsonPropertyName("battery")] public Battery Battery { get; set; } = new();

    /// 0 = unknown, 1 = Off, 2 = Noise Cancellation, 3 = Transparency, 4 = Adaptive.
    [JsonPropertyName("anc")] public byte Anc { get; set; }

    [JsonPropertyName("mic_recording")] public bool MicRecording { get; set; }
    [JsonPropertyName("auto_mode")] public bool AutoMode { get; set; }
    [JsonPropertyName("conversational_awareness")] public bool ConversationalAwareness { get; set; }
    [JsonPropertyName("adaptive_volume")] public bool AdaptiveVolume { get; set; }
    [JsonPropertyName("allow_off")] public bool AllowOff { get; set; }
    [JsonPropertyName("volume")] public byte Volume { get; set; }
    [JsonPropertyName("muted")] public bool Muted { get; set; }
    [JsonPropertyName("heart_rate")] public ushort? HeartRate { get; set; }

    // Device metadata from the 0x1D packet (empty until it arrives). Serial is
    // sensitive — the Settings page keeps these hidden behind a reveal toggle.
    [JsonPropertyName("model")] public string Model { get; set; } = "";
    [JsonPropertyName("firmware")] public string Firmware { get; set; } = "";
    [JsonPropertyName("serial")] public string Serial { get; set; } = "";

    /// Every host the AirPods report themselves connected to (AAP 0x2E) -- this PC
    /// plus whatever else is sharing them. Multipoint is invisible to Windows, so
    /// this is the only place the user can see that a phone is also holding the buds.
    [JsonPropertyName("multipoint")] public List<ConnectedDevice> Multipoint { get; set; } = new();
}

/// One host connected to the AirPods, as reported by the buds themselves.
public sealed class ConnectedDevice
{
    [JsonPropertyName("address")] public string Address { get; set; } = "";
    [JsonPropertyName("is_this_pc")] public bool IsThisPc { get; set; }
    [JsonPropertyName("name")] public string Name { get; set; } = "";

    /// "pc", "laptop", "phone", "watch", "tablet", "audio" or "unknown".
    [JsonPropertyName("kind")] public string Kind { get; set; } = "unknown";

    /// Segoe Fluent Icons glyph for this kind.
    public string Glyph => Kind switch
    {
        "pc" => "\uE977",      // Devices2 (desktop)
        "laptop" => "\uE7F8",  // Laptop
        "phone" => "\uE8EA",   // CellPhone
        "tablet" => "\uE70A",  // Tablet
        "watch" => "\uE916",   // Stopwatch-ish; closest stock glyph for a watch
        "audio" => "\uE7F6",   // Headphone
        _ => "\uE783",         // Error/unknown
    };

    /// "This PC" is shown plainly; anything else carries its address so an
    /// unidentified device is still recognisable.
    public string Detail => IsThisPc ? Address : Address;
}

/// AAP control-command feature ids (the `id` byte of a 0x09 control command).
public static class Feature
{
    public const byte AdaptiveVolume = 0x26;         // 38
    public const byte ConversationalAwareness = 0x28; // 40
    public const byte AllowOff = 0x34;                // 52
}

/// The raw control id for Adaptive-Audio noise strength (value 0..=100).
/// (Named ControlId to avoid clashing with Microsoft.UI.Xaml.Controls.Control.)
public static class ControlId
{
    public const byte AdaptiveNoiseStrength = 0x2E; // 46
}

// ---------------------------------------------------------------------------
// Commands (app → daemon). Each carries its snake_case `cmd` tag literally.
// ---------------------------------------------------------------------------

public sealed class HelloCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "hello";
    [JsonPropertyName("kind")] public string Kind => "app"; // "tray" | "app"
}

public sealed class GetStateCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "get_state";
}

public sealed class SetAncCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_anc";
    [JsonPropertyName("mode")] public byte Mode { get; init; } // 1..=4
}

public sealed class SetMicModeCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_mic_mode";
    [JsonPropertyName("auto")] public bool Auto { get; init; }
    [JsonPropertyName("manual")] public bool Manual { get; init; }
}

public sealed class SetFeatureCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_feature";
    [JsonPropertyName("feature")] public byte Feature { get; init; }
    [JsonPropertyName("on")] public bool On { get; init; }
}

public sealed class SetControlCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_control";
    [JsonPropertyName("id")] public byte Id { get; init; }
    [JsonPropertyName("value")] public byte Value { get; init; }
}

public sealed class StepVolumeCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "step_volume";
    [JsonPropertyName("delta")] public int Delta { get; init; }
}

public sealed class SetVolumeCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_volume";
    [JsonPropertyName("percent")] public byte Percent { get; init; }
}

public sealed class ToggleMuteCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "toggle_mute";
}

public sealed class SetHeartRateCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_heart_rate";
    [JsonPropertyName("on")] public bool On { get; init; }
}

public sealed class SetHearingAidCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_hearing_aid";
    [JsonPropertyName("on")] public bool On { get; init; }
    [JsonPropertyName("left_eq")] public float[] LeftEq { get; init; } = new float[8];
    [JsonPropertyName("right_eq")] public float[] RightEq { get; init; } = new float[8];
    [JsonPropertyName("amplification")] public float Amplification { get; init; }
    [JsonPropertyName("balance")] public float Balance { get; init; }
    [JsonPropertyName("tone")] public float Tone { get; init; }
    [JsonPropertyName("conversation_boost")] public bool ConversationBoost { get; init; }
    [JsonPropertyName("ambient_noise_reduction")] public float AmbientNoiseReduction { get; init; }
    [JsonPropertyName("own_voice")] public float OwnVoice { get; init; }
}

public sealed class ConnectCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "connect";
}

public sealed class DisconnectCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "disconnect";
}

/// Toggle the AirPods' audio services off and on -- what Disconnect + Connect does
/// by hand -- WITHOUT touching the AAP session. For the failure where the sound
/// stops while the session, the Bluetooth device and the endpoint all stay healthy,
/// which nothing can detect automatically.
public sealed class RebuildAudioCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "rebuild_audio";
}

public sealed class RepairConnectionCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "repair_connection";
}

public sealed class SetNameCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "set_name";
    [JsonPropertyName("name")] public string Name { get; init; } = "";
}

public sealed class ShutdownCmd
{
    [JsonPropertyName("cmd")] public string Cmd => "shutdown";
}

// ---------------------------------------------------------------------------
// Event parsing (daemon → app). Dispatched on the `event` tag.
// ---------------------------------------------------------------------------

public abstract record DaemonEvent
{
    /// Full state, pushed on connect and whenever it changes.
    public sealed record State(Snapshot Snapshot) : DaemonEvent;

    /// A transient notification to render (toast / InfoBar / balloon).
    public sealed record Overlay(string Title, string Body) : DaemonEvent;

    /// Device nearby (BLE) but not connected — show a clickable "Connect?" card.
    public sealed record ConnectPrompt(string Name) : DaemonEvent;
}

public static class Wire
{
    public static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    /// Serialize a command as one NDJSON line (trailing '\n').
    public static string ToLine(object command)
        => JsonSerializer.Serialize(command, command.GetType(), Json) + "\n";

    /// Parse one NDJSON line into a DaemonEvent, or null if unrecognized.
    public static DaemonEvent? ParseEvent(string line)
    {
        line = line.Trim();
        if (line.Length == 0) return null;

        try
        {
            using var doc = JsonDocument.Parse(line);
            var root = doc.RootElement;
            if (!root.TryGetProperty("event", out var tagProp)) return null;
            var tag = tagProp.GetString();

            switch (tag)
            {
                case "state":
                    // The snapshot is flattened alongside the tag (serde
                    // `Event::State(Snapshot)` with `#[serde(tag = "event")]`
                    // inlines the struct's fields), so deserialize the whole
                    // object into a Snapshot; the extra "event" key is ignored.
                    var snap = root.Deserialize<Snapshot>(Json) ?? new Snapshot();
                    return new DaemonEvent.State(snap);

                case "overlay":
                    return new DaemonEvent.Overlay(
                        GetStr(root, "title"),
                        GetStr(root, "body"));

                case "connect_prompt":
                    return new DaemonEvent.ConnectPrompt(GetStr(root, "name"));

                default:
                    return null;
            }
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string GetStr(JsonElement el, string name)
        => el.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String
            ? p.GetString() ?? ""
            : "";
}
