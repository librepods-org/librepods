using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Threading.Channels;

namespace LibrePods.WinUI.Ipc;

/// IPC client to `librepodsd` over the two one-directional named pipes:
///   * read State/Overlay/ConnectPrompt events from "LibrePods-events" (In)
///   * write commands to "LibrePods-cmds" (Out)
///
/// A single duplex pipe deadlocks a Windows sync handle, so the daemon keeps the
/// directions on separate pipes; we mirror that. Both directions run on their own
/// background task and reconnect (spawning the daemon if it isn't running), so a
/// stalled pipe never freezes the UI thread. Events are raised on a background
/// thread — subscribers must marshal to the UI via DispatcherQueue.
public sealed class DaemonClient : IDisposable
{
    private const string EventsPipe = "LibrePods-events";
    private const string CommandsPipe = "LibrePods-cmds";
    private const string DaemonExe = "librepodsd.exe";

    // Raised on a background thread.
    public event Action<Snapshot>? SnapshotReceived;
    public event Action<string, string>? OverlayReceived;
    public event Action<string>? ConnectPromptReceived;
    public event Action<bool>? ConnectionChanged; // true = events pipe connected

    private readonly Channel<byte[]> _outgoing =
        Channel.CreateUnbounded<byte[]>(new UnboundedChannelOptions
        {
            SingleReader = true,
            SingleWriter = false,
        });

    private readonly CancellationTokenSource _cts = new();
    private bool _started;

    /// Start the two async pipe loops. They are launched directly (no Task.Run):
    /// each hits its first `await` at ConnectAsync almost immediately and then
    /// runs entirely on the thread pool via ConfigureAwait(false), so nothing
    /// blocks the caller or the UI thread. Both loops swallow their own
    /// exceptions, so the discarded tasks never fault unobserved.
    public void Start()
    {
        if (_started) return;
        _started = true;
        _ = ReadLoopAsync(_cts.Token);
        _ = WriteLoopAsync(_cts.Token);
    }

    // ---- Command helpers ---------------------------------------------------

    public void Send(object command)
    {
        var bytes = Encoding.UTF8.GetBytes(Wire.ToLine(command));
        _outgoing.Writer.TryWrite(bytes);
    }

    public void SendHello() => Send(new HelloCmd());
    public void RequestState() => Send(new GetStateCmd());
    public void SetAnc(byte mode) => Send(new SetAncCmd { Mode = mode });
    public void SetMicMode(bool auto, bool manual) => Send(new SetMicModeCmd { Auto = auto, Manual = manual });
    public void SetFeature(byte feature, bool on) => Send(new SetFeatureCmd { Feature = feature, On = on });
    public void SetControl(byte id, byte value) => Send(new SetControlCmd { Id = id, Value = value });
    public void SetHearingAid(SetHearingAidCmd cmd) => Send(cmd);
    public void StepVolume(int delta) => Send(new StepVolumeCmd { Delta = delta });
    public void SetVolume(byte percent) => Send(new SetVolumeCmd { Percent = percent });
    public void ToggleMute() => Send(new ToggleMuteCmd());
    public void SetHeartRate(bool on) => Send(new SetHeartRateCmd { On = on });
    public void Connect() => Send(new ConnectCmd());
    public void Disconnect() => Send(new DisconnectCmd());
    public void RepairConnection() => Send(new RepairConnectionCmd());
    public void RebuildAudio() => Send(new RebuildAudioCmd());
    public void SetName(string name) => Send(new SetNameCmd { Name = name });
    public void Shutdown() => Send(new ShutdownCmd());

    // ---- Reader ------------------------------------------------------------

    private async Task ReadLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeClientStream? pipe = null;
            try
            {
                pipe = new NamedPipeClientStream(".", EventsPipe, PipeDirection.In,
                    PipeOptions.Asynchronous);
                try
                {
                    await pipe.ConnectAsync(500, ct).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    // Daemon not up yet — launch it (it's a sibling exe) and retry.
                    TrySpawnDaemon();
                    await Task.Delay(500, ct).ConfigureAwait(false);
                    continue;
                }

                ConnectionChanged?.Invoke(true);

                // On (re)connect, announce ourselves and pull a fresh snapshot.
                SendHello();
                RequestState();

                using var reader = new StreamReader(pipe, Encoding.UTF8,
                    detectEncodingFromByteOrderMarks: false);
                string? line;
                while ((line = await reader.ReadLineAsync(ct).ConfigureAwait(false)) is not null)
                {
                    Dispatch(Wire.ParseEvent(line));
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (IOException)
            {
                // Pipe closed / daemon exited — fall through to reconnect.
            }
            catch (Exception)
            {
                // Never let the loop die; back off and retry.
            }
            finally
            {
                pipe?.Dispose();
                ConnectionChanged?.Invoke(false);
            }

            try { await Task.Delay(500, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    private void Dispatch(DaemonEvent? ev)
    {
        switch (ev)
        {
            case DaemonEvent.State s:
                SnapshotReceived?.Invoke(s.Snapshot);
                break;
            case DaemonEvent.Overlay o:
                OverlayReceived?.Invoke(o.Title, LibrePods.WinUI.Services.OverlayText.Resolve(o.Body));
                break;
            case DaemonEvent.ConnectPrompt p:
                ConnectPromptReceived?.Invoke(p.Name);
                break;
        }
    }

    // ---- Writer ------------------------------------------------------------

    private async Task WriteLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeClientStream? pipe = null;
            try
            {
                pipe = new NamedPipeClientStream(".", CommandsPipe, PipeDirection.Out,
                    PipeOptions.Asynchronous);
                try
                {
                    await pipe.ConnectAsync(500, ct).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    // The reader loop is responsible for spawning the daemon.
                    await Task.Delay(500, ct).ConfigureAwait(false);
                    continue;
                }

                // Drain the outgoing queue until the pipe breaks.
                while (!ct.IsCancellationRequested)
                {
                    var msg = await _outgoing.Reader.ReadAsync(ct).ConfigureAwait(false);
                    await pipe.WriteAsync(msg, ct).ConfigureAwait(false);
                    await pipe.FlushAsync(ct).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (IOException)
            {
                // Pipe broke — reconnect. (The in-flight message is lost, which
                // matches the tray's best-effort behaviour.)
            }
            catch (Exception)
            {
            }
            finally
            {
                pipe?.Dispose();
            }

            try { await Task.Delay(500, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    // ---- Daemon launch -----------------------------------------------------

    /// If a `librepodsd` is already running when this app starts (e.g. an orphan
    /// from a crashed or force-killed session), ask it to exit GRACEFULLY over the
    /// commands pipe and wait for it to go — so this app owns a single clean
    /// instance and the freshly-spawned daemon can open the driver. The daemon
    /// releases the exclusive AAP driver handle on Shutdown; we deliberately do
    /// NOT hard-kill a stuck one — a kill leaks that handle and sticks the devnode
    /// in Code 38 (CM_PROB_DRIVER_FAILED_PRIOR_UNLOAD), i.e. worse than leaving it
    /// for the single-instance mutex + a reboot to resolve. Best-effort and fully
    /// guarded: any failure just falls through to the normal connect/spawn path.
    public static void ShutdownExistingDaemon()
    {
        Process[] existing;
        try { existing = Process.GetProcessesByName("librepodsd"); }
        catch { return; }
        if (existing.Length == 0) return;

        try
        {
            using var pipe = new NamedPipeClientStream(".", CommandsPipe,
                PipeDirection.Out, PipeOptions.None);
            pipe.Connect(500);
            // Byte-identical to a normal command (Wire.ToLine carries the newline).
            var bytes = Encoding.UTF8.GetBytes(Wire.ToLine(new ShutdownCmd()));
            pipe.Write(bytes, 0, bytes.Length);
            pipe.Flush();
        }
        catch
        {
            // Couldn't reach it (stuck daemon) — leave it be; never hard-kill.
            foreach (var p in existing) p.Dispose();
            return;
        }

        // Let it actually exit (releasing the driver) before we spawn a fresh one.
        foreach (var p in existing)
        {
            try { p.WaitForExit(2000); } catch { }
            p.Dispose();
        }
    }

    private static void TrySpawnDaemon()
    {
        try
        {
            var path = FindDaemon();
            if (path is null) return;
            Process.Start(new ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = Path.GetDirectoryName(path)!,
            });
        }
        catch
        {
            // Best effort — if we can't spawn it, keep retrying the connect.
        }
    }

    /// Locate librepodsd.exe: next to us (the deployed layout has every exe in the
    /// same folder), else the standard install dir %LOCALAPPDATA%\LibrePods — so a
    /// VS debug run (from bin\...) still finds and launches the installed daemon
    /// without needing the Rust tray to be up first.
    private static string? FindDaemon()
    {
        string[] candidates =
        {
            Path.Combine(AppContext.BaseDirectory, DaemonExe),
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "LibrePods", DaemonExe),
        };
        foreach (var c in candidates)
            if (File.Exists(c)) return c;
        return null;
    }

    public void Dispose()
    {
        try { _cts.Cancel(); } catch { }
        _outgoing.Writer.TryComplete();
        _cts.Dispose();
    }
}
