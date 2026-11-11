# Pocket Nexus PC - everything the handheld needs on the gaming PC, in one window
#
#  1. USB bridge   Streams to the handheld over the USB-C cable on ROMs where Android's USB
#                  tethering is turned off (Retroid Pocket). Needs USB debugging on the handheld.
#  2. PC helper    Lets the handheld put this PC to sleep, restart it, shut it down, lock it,
#                  turn the display off, read its status, and use the handheld's microphone
#                  on this PC (through VB-Audio Virtual Cable, free: https://vb-audio.com/Cable/).
#                  Through the cable it just works; over Wi-Fi the handheld links once with the
#                  PIN shown below (or by itself after one stream over the cable).
#  3. PC library   Lists the games installed here (Steam, Epic, GOG) for the handheld, starts
#                  them on request and asks Steam to install games (GAMES, LAUNCH, STEAM INSTALL).
#
# How the cable part works
#   adb reverse   handheld 127.0.0.1:47984/47989/48010 -> Apollo on this PC   (TCP)
#   adb reverse   handheld 127.0.0.1:47100             -> the bridge (stream packets)
#   adb reverse   handheld 127.0.0.1:47110             -> the PC helper
#
# Leave this window open (it can be minimised) while you play.

param([switch]$Autostart)

$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "Pocket Nexus PC"
$ScriptPath = $MyInvocation.MyCommand.Path
$here = Split-Path -Parent $ScriptPath
$TunnelPort = 47100
$CompanionPort = 47110
$TcpPorts = @(47984, 47989, 48010, 47990)
$DataDir = "$env:LOCALAPPDATA\ArtemisPocket"
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

function Say($text, $color = "Gray") { Write-Host ("  " + $text) -ForegroundColor $color }
function Stamp($text, $color = "Gray") { Write-Host ("  [" + (Get-Date -Format "HH:mm:ss") + "] " + $text) -ForegroundColor $color }

Write-Host ""
Write-Host "  POCKET NEXUS PC" -ForegroundColor White
Write-Host ""

# ---- ADB ---------------------------------------------------------------------------------
function Find-Adb {
    foreach ($p in @("$here\platform-tools\adb.exe", "$here\adb.exe", "$env:LOCALAPPDATA\ArtemisPocket\platform-tools\adb.exe")) {
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$adb = Find-Adb
if (-not $adb) {
    Say "ADB not found - downloading Google's platform-tools (about 7 MB)..." "Yellow"
    $dest = "$env:LOCALAPPDATA\ArtemisPocket"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $zip = "$dest\platform-tools.zip"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $dest -Force
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch {
        Say "Download failed: $($_.Exception.Message)" "Red"
        Say "Download 'SDK Platform-Tools for Windows' from developer.android.com and put the" "Red"
        Say "platform-tools folder next to this script, then run it again." "Red"
        Read-Host "  Press Enter to close"
        exit 1
    }
    $adb = Find-Adb
    if (-not $adb) { Say "ADB still not found." "Red"; Read-Host "  Press Enter to close"; exit 1 }
}
Say "ADB: $adb" "DarkGray"

# ---- The relay (compiled on the fly, runs at native speed) ---------------------------------
if (-not ("PocketBridge" -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;

public static class PocketBridge {
    static readonly int[] Ports = { 47998, 47999, 48000, 48002 };
    static readonly byte[] Hello = { (byte)'A', (byte)'P', (byte)'B', (byte)'1' };
    static TcpListener listener;
    static TcpClient current;
    static readonly object currentLock = new object();
    public static volatile int Sessions = 0;
    public static volatile bool Connected = false;
    public static long BytesDown = 0;

    public static void Start(int tunnelPort) {
        listener = new TcpListener(IPAddress.Loopback, tunnelPort);
        listener.Start();
        Thread t = new Thread(AcceptLoop);
        t.IsBackground = true;
        t.Start();
    }

    static void AcceptLoop() {
        while (true) {
            TcpClient client;
            try { client = listener.AcceptTcpClient(); } catch { return; }
            lock (currentLock) {
                if (current != null) { try { current.Close(); } catch { } }
                current = client;
            }
            TcpClient c = client;
            Thread t = new Thread(delegate () { Serve(c); });
            t.IsBackground = true;
            t.Priority = ThreadPriority.Highest;
            t.Start();
        }
    }

    static void Serve(TcpClient client) {
        UdpClient[] sockets = new UdpClient[Ports.Length];
        bool[] alive = { true };
        try {
            client.NoDelay = true;
            client.ReceiveBufferSize = 4 * 1024 * 1024;
            client.SendBufferSize = 4 * 1024 * 1024;
            NetworkStream stream = client.GetStream();
            stream.Write(Hello, 0, Hello.Length);
            stream.Flush();
            Connected = true;
            Sessions++;
            object writeLock = new object();

            for (int i = 0; i < Ports.Length; i++) {
                UdpClient u = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
                u.Client.ReceiveBufferSize = 8 * 1024 * 1024;
                sockets[i] = u;
                int ch = i;
                Thread rt = new Thread(delegate () {
                    IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                    while (alive[0]) {
                        byte[] data;
                        try { data = u.Receive(ref remote); } catch { break; }
                        byte[] frame = new byte[data.Length + 3];
                        frame[0] = (byte)ch;
                        frame[1] = (byte)(data.Length >> 8);
                        frame[2] = (byte)data.Length;
                        Buffer.BlockCopy(data, 0, frame, 3, data.Length);
                        try {
                            lock (writeLock) { stream.Write(frame, 0, frame.Length); }
                            Interlocked.Add(ref BytesDown, data.Length);
                        } catch { alive[0] = false; try { client.Close(); } catch { } break; }
                    }
                });
                rt.IsBackground = true;
                rt.Priority = ThreadPriority.Highest;
                rt.Start();
            }

            byte[] hdr = new byte[3];
            byte[] buf = new byte[65536];
            while (alive[0]) {
                ReadFull(stream, hdr, 3);
                int channel = hdr[0];
                int len = (hdr[1] << 8) | hdr[2];
                ReadFull(stream, buf, len);
                if (channel < Ports.Length) {
                    sockets[channel].Send(buf, len, new IPEndPoint(IPAddress.Loopback, Ports[channel]));
                }
            }
        } catch {
        } finally {
            alive[0] = false;
            foreach (UdpClient s in sockets) { if (s != null) { try { s.Close(); } catch { } } }
            try { client.Close(); } catch { }
            lock (currentLock) { if (current == client) { current = null; Connected = false; } }
        }
    }

    static void ReadFull(NetworkStream s, byte[] b, int n) {
        int o = 0;
        while (o < n) {
            int r = s.Read(b, o, n - o);
            if (r <= 0) throw new Exception("closed");
            o += r;
        }
    }
}
'@
}

try {
    [PocketBridge]::Start($TunnelPort)
} catch {
    Say "Could not open port $TunnelPort - close the old USB bridge window (or another copy of this one) and try again." "Red"
    Read-Host "  Press Enter to close"
    exit 1
}

# ---- The PC helper (power, status, microphone) ----------------------------------------------
if (-not ("PocketCompanion" -as [type])) {
Add-Type -IgnoreWarnings -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class PocketCompanion {
    public static string Token = "";
    public static string Pin = "";
    // Commands from add-ons compiled separately (so a problem there never stops this helper)
    public static Func<string, string[], NetworkStream, bool> Extra;
    public static readonly ConcurrentQueue<string> Events = new ConcurrentQueue<string>();
    static TcpListener listener;
    static readonly object pinLock = new object();
    static int badPins = 0;
    static DateTime badPinStart = DateTime.MinValue;

    [DllImport("powrprof.dll")] static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);
    [DllImport("user32.dll")] static extern bool LockWorkStation();
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref MemoryStatusEx m);
    [DllImport("kernel32.dll")] static extern bool GetSystemPowerStatus(out PowerStatus s);
    [DllImport("kernel32.dll")] static extern ulong GetTickCount64();
    [DllImport("kernel32.dll")] static extern bool GetSystemTimes(out long idle, out long kernel, out long user);

    [StructLayout(LayoutKind.Sequential)]
    public struct MemoryStatusEx {
        public uint dwLength; public uint dwMemoryLoad;
        public ulong ullTotalPhys; public ulong ullAvailPhys; public ulong ullTotalPageFile; public ulong ullAvailPageFile;
        public ulong ullTotalVirtual; public ulong ullAvailVirtual; public ulong ullAvailExtendedVirtual;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PowerStatus {
        public byte ACLineStatus; public byte BatteryFlag; public byte BatteryLifePercent; public byte SystemStatusFlag;
        public int BatteryLifeTime; public int BatteryFullLifeTime;
    }

    static volatile int cpuPercent = 0;

    public static void Start(int port, string token, string pin) {
        Token = token;
        Pin = pin;
        listener = new TcpListener(IPAddress.Any, port);
        listener.Start();
        Thread accept = new Thread(AcceptLoop);
        accept.IsBackground = true;
        accept.Start();
        Thread cpu = new Thread(CpuLoop);
        cpu.IsBackground = true;
        cpu.Start();
    }

    public static void Log(string text) { Events.Enqueue(text); }

    static void AcceptLoop() {
        while (true) {
            TcpClient c;
            try { c = listener.AcceptTcpClient(); } catch { return; }
            TcpClient client = c;
            Thread t = new Thread(delegate () { Serve(client); });
            t.IsBackground = true;
            t.Start();
        }
    }

    static void Serve(TcpClient client) {
        try {
            client.NoDelay = true;
            NetworkStream s = client.GetStream();
            s.ReadTimeout = 15000;
            IPEndPoint remote = (IPEndPoint)client.Client.RemoteEndPoint;
            bool trusted = IPAddress.IsLoopback(remote.Address);
            string hello = ReadLine(s);
            if (hello == null || !hello.StartsWith("APC1")) { return; }
            string[] hp = hello.Split(' ');
            bool authed = trusted || (hp.Length > 1 && SameSecret(hp[1], Token));
            WriteLine(s, "OK " + (authed ? "1" : "0") + " " + Environment.MachineName);
            while (true) {
                string line = ReadLine(s);
                if (line == null) { return; }
                string[] p = line.Trim().Split(' ');
                string cmd = p[0].ToUpperInvariant();
                if (cmd == "PING") {
                    WriteLine(s, "PONG");
                } else if (cmd == "QUIT") {
                    return;
                } else if (cmd == "TOKEN") {
                    WriteLine(s, trusted ? "TOKEN " + Token : "ERR auth");
                    if (trusted) { Log("Handheld linked through the cable (Wi-Fi control is ready too)"); }
                } else if (cmd == "PAIR") {
                    string result = CheckPin(p.Length > 1 ? p[1] : "");
                    if (result == null) {
                        authed = true;
                        WriteLine(s, "TOKEN " + Token);
                        Log("Handheld linked with the PIN");
                    } else {
                        WriteLine(s, result);
                    }
                } else if (!authed) {
                    WriteLine(s, "ERR auth");
                } else if (cmd == "STATUS") {
                    WriteLine(s, Status());
                } else if (cmd == "POWER") {
                    WriteLine(s, Power(p.Length > 1 ? p[1].ToLowerInvariant() : ""));
                } else if (cmd == "VERSION") {
                    WriteLine(s, "VERSION 2" + (Extra != null ? " games" : ""));
                } else if (cmd == "MIC") {
                    int rate = p.Length > 1 ? ParseInt(p[1], 48000) : 48000;
                    int channels = p.Length > 2 ? ParseInt(p[2], 1) : 1;
                    MicPlayer.Run(s, rate, channels);
                    return;
                } else {
                    // Add-ons (the PC library) get the commands this doesn't know
                    Func<string, string[], NetworkStream, bool> extra = Extra;
                    if (extra == null || !extra(cmd, p, s)) { WriteLine(s, "ERR unknown"); }
                }
            }
        } catch {
        } finally {
            try { client.Close(); } catch { }
        }
    }

    static bool SameSecret(string a, string b) {
        if (a == null || b == null || b.Length == 0 || a.Length != b.Length) { return false; }
        int diff = 0;
        for (int i = 0; i < a.Length; i++) { diff |= a[i] ^ b[i]; }
        return diff == 0;
    }

    static string CheckPin(string pin) {
        lock (pinLock) {
            if ((DateTime.UtcNow - badPinStart).TotalSeconds > 60) { badPins = 0; badPinStart = DateTime.UtcNow; }
            if (badPins >= 5) { return "ERR locked"; }
            if (!SameSecret(pin, Pin)) { badPins++; return "ERR pin"; }
            return null;
        }
    }

    static int ParseInt(string text, int fallback) {
        int v;
        return int.TryParse(text, out v) ? v : fallback;
    }

    static string Status() {
        MemoryStatusEx m = new MemoryStatusEx();
        m.dwLength = (uint)Marshal.SizeOf(typeof(MemoryStatusEx));
        int ram = GlobalMemoryStatusEx(ref m) ? (int)m.dwMemoryLoad : -1;
        long up = (long)(GetTickCount64() / 1000UL);
        PowerStatus ps;
        int bat = -1;
        int ac = 1;
        if (GetSystemPowerStatus(out ps)) {
            if (ps.BatteryLifePercent != 255) { bat = ps.BatteryLifePercent; }
            ac = ps.ACLineStatus == 0 ? 0 : 1;
        }
        return "STATUS cpu=" + cpuPercent + " ram=" + ram + " up=" + up + " bat=" + bat + " ac=" + ac;
    }

    static void CpuLoop() {
        long idle0, kernel0, user0;
        if (!GetSystemTimes(out idle0, out kernel0, out user0)) { return; }
        while (true) {
            Thread.Sleep(2000);
            long idle1, kernel1, user1;
            if (!GetSystemTimes(out idle1, out kernel1, out user1)) { continue; }
            long total = (kernel1 - kernel0) + (user1 - user0);
            long busy = total - (idle1 - idle0);
            if (total > 0) { cpuPercent = (int)(busy * 100 / total); }
            idle0 = idle1; kernel0 = kernel1; user0 = user1;
        }
    }

    static void RunHidden(string file, string args) {
        ProcessStartInfo info = new ProcessStartInfo(file, args);
        info.CreateNoWindow = true;
        info.UseShellExecute = false;
        Process.Start(info);
    }

    static string Power(string action) {
        Action act;
        if (action == "sleep") { act = delegate () { SetSuspendState(false, false, false); }; }
        else if (action == "hibernate") { act = delegate () { SetSuspendState(true, false, false); }; }
        else if (action == "restart") { act = delegate () { RunHidden("shutdown.exe", "/r /t 0"); }; }
        else if (action == "shutdown") { act = delegate () { RunHidden("shutdown.exe", "/s /t 0"); }; }
        else if (action == "signout") { act = delegate () { RunHidden("shutdown.exe", "/l"); }; }
        else if (action == "lock") { act = delegate () { LockWorkStation(); }; }
        else if (action == "displayoff") { act = delegate () { PostMessage(new IntPtr(0xFFFF), 0x0112, new IntPtr(0xF170), new IntPtr(2)); }; }
        else { return "ERR unknown"; }
        Log("Power: " + action);
        Action run = act;
        Thread t = new Thread(delegate () {
            // Give the handheld a moment to leave the stream cleanly first
            Thread.Sleep(1500);
            try { run(); } catch (Exception e) { Log("Power " + action + " failed: " + e.Message); }
        });
        t.IsBackground = true;
        t.Start();
        return "OK";
    }

    public static string ReadLine(NetworkStream s) {
        List<byte> bytes = new List<byte>();
        while (bytes.Count < 1024) {
            int b = s.ReadByte();
            if (b < 0) { return bytes.Count > 0 ? Encoding.UTF8.GetString(bytes.ToArray()) : null; }
            if (b == 10) { break; }
            if (b != 13) { bytes.Add((byte)b); }
        }
        return Encoding.UTF8.GetString(bytes.ToArray());
    }

    public static void WriteLine(NetworkStream s, string line) {
        byte[] b = Encoding.UTF8.GetBytes(line + "\n");
        s.Write(b, 0, b.Length);
        s.Flush();
    }

    public static void ReadFull(NetworkStream s, byte[] buffer, int count) {
        int offset = 0;
        while (offset < count) {
            int n = s.Read(buffer, offset, count - offset);
            if (n <= 0) { throw new Exception("closed"); }
            offset += n;
        }
    }
}

// Plays the handheld's microphone into VB-Audio's "CABLE Input", so games and chat apps can pick
// "CABLE Output" as their microphone. Without VB-Cable it plays on the default speakers.
public static class MicPlayer {
    const int Buffers = 8;
    const uint WHDR_DONE = 1;

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    public struct WaveFormat {
        public ushort wFormatTag; public ushort nChannels; public uint nSamplesPerSec; public uint nAvgBytesPerSec;
        public ushort nBlockAlign; public ushort wBitsPerSample; public ushort cbSize;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct WaveHeader {
        public IntPtr lpData; public uint dwBufferLength; public uint dwBytesRecorded; public IntPtr dwUser;
        public uint dwFlags; public uint dwLoops; public IntPtr lpNext; public IntPtr reserved;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct WaveOutCaps {
        public ushort wMid; public ushort wPid; public uint vDriverVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szPname;
        public uint dwFormats; public ushort wChannels; public ushort wReserved1; public uint dwSupport;
    }

    [DllImport("winmm.dll")] static extern int waveOutGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode, EntryPoint = "waveOutGetDevCapsW")] static extern int waveOutGetDevCaps(IntPtr deviceId, ref WaveOutCaps caps, int size);
    [DllImport("winmm.dll")] static extern int waveOutOpen(out IntPtr hwo, int deviceId, ref WaveFormat format, IntPtr callback, IntPtr instance, int flags);
    [DllImport("winmm.dll")] static extern int waveOutPrepareHeader(IntPtr hwo, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveOutUnprepareHeader(IntPtr hwo, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveOutWrite(IntPtr hwo, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveOutReset(IntPtr hwo);
    [DllImport("winmm.dll")] static extern int waveOutClose(IntPtr hwo);

    /** The VB-Cable playback device, or null. */
    public static string FindCable(out int deviceId) {
        deviceId = -1;
        int n = waveOutGetNumDevs();
        for (int i = 0; i < n; i++) {
            WaveOutCaps caps = new WaveOutCaps();
            if (waveOutGetDevCaps(new IntPtr(i), ref caps, Marshal.SizeOf(typeof(WaveOutCaps))) == 0
                    && caps.szPname != null
                    && caps.szPname.IndexOf("CABLE Input", StringComparison.OrdinalIgnoreCase) >= 0) {
                deviceId = i;
                return caps.szPname;
            }
        }
        return null;
    }

    public static void Run(NetworkStream s, int rate, int channels) {
        int deviceId;
        string name = FindCable(out deviceId);
        if (name == null) { name = "default"; deviceId = -1; }
        if (channels < 1 || channels > 2) { channels = 1; }
        if (rate < 8000 || rate > 96000) { rate = 48000; }

        WaveFormat fmt = new WaveFormat();
        fmt.wFormatTag = 1;
        fmt.nChannels = (ushort)channels;
        fmt.nSamplesPerSec = (uint)rate;
        fmt.wBitsPerSample = 16;
        fmt.nBlockAlign = (ushort)(channels * 2);
        fmt.nAvgBytesPerSec = (uint)(rate * channels * 2);
        fmt.cbSize = 0;

        IntPtr hwo;
        int r = waveOutOpen(out hwo, deviceId, ref fmt, IntPtr.Zero, IntPtr.Zero, 0);
        if (r != 0) {
            PocketCompanion.WriteLine(s, "ERR audio " + r);
            return;
        }
        int frame = rate / 50 * channels * 2;
        int headerSize = Marshal.SizeOf(typeof(WaveHeader));
        IntPtr[] headers = new IntPtr[Buffers];
        IntPtr[] data = new IntPtr[Buffers];
        bool[] queued = new bool[Buffers];
        try {
            for (int i = 0; i < Buffers; i++) {
                data[i] = Marshal.AllocHGlobal(frame);
                headers[i] = Marshal.AllocHGlobal(headerSize);
                WaveHeader h = new WaveHeader();
                h.lpData = data[i];
                h.dwBufferLength = (uint)frame;
                Marshal.StructureToPtr(h, headers[i], false);
                waveOutPrepareHeader(hwo, headers[i], headerSize);
            }
            PocketCompanion.WriteLine(s, "OK " + name);
            PocketCompanion.Log("Microphone on -> " + name);
            s.ReadTimeout = 10000;
            byte[] buffer = new byte[frame];
            while (true) {
                PocketCompanion.ReadFull(s, buffer, frame);
                int slot = -1;
                for (int i = 0; i < Buffers; i++) {
                    if (queued[i]) {
                        WaveHeader h = (WaveHeader)Marshal.PtrToStructure(headers[i], typeof(WaveHeader));
                        if ((h.dwFlags & WHDR_DONE) != 0) { queued[i] = false; }
                    }
                    if (!queued[i] && slot < 0) { slot = i; }
                }
                if (slot < 0) { continue; } // playback is behind: drop a packet to keep the delay short
                Marshal.Copy(buffer, 0, data[slot], frame);
                waveOutWrite(hwo, headers[slot], headerSize);
                queued[slot] = true;
            }
        } catch {
        } finally {
            waveOutReset(hwo);
            for (int i = 0; i < Buffers; i++) {
                if (headers[i] != IntPtr.Zero) {
                    waveOutUnprepareHeader(hwo, headers[i], headerSize);
                    Marshal.FreeHGlobal(headers[i]);
                }
                if (data[i] != IntPtr.Zero) { Marshal.FreeHGlobal(data[i]); }
            }
            waveOutClose(hwo);
            PocketCompanion.Log("Microphone off");
        }
    }
}
'@
}

# ---- PC library (an add-on: if it can't load, everything else still works) ------------------
$pcLibraryOk = $false
if (-not ("PcGames" -as [type])) {
    try {
Add-Type -IgnoreWarnings -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Win32;

// The PC's games for the handheld's PC library: Steam (every library folder), Epic and GOG.
// Only games found by a scan can be started or installed (never an arbitrary command).
public static class PcGames {
    public class Game {
        public string Source = "", Id = "", Name = "", State = "installed", Exe = "", Dir = "", Uri = "", ArtId = "";
        public int Progress = -1;
        public long SizeMb = 0, LastPlayed = 0;

        public string Line() {
            return "G\t" + Source + "\t" + Clean(Id) + "\t" + Clean(Name) + "\t" + State + "\t" + Progress + "\t"
                + SizeMb + "\t" + LastPlayed + "\t" + Clean(ArtId);
        }
    }

    public static Action<string> Logger;
    static readonly object scanLock = new object();

    static void Log(string text) {
        Action<string> l = Logger;
        if (l != null) { try { l(text); } catch { } }
    }

    static void Send(NetworkStream s, string line) {
        byte[] b = Encoding.UTF8.GetBytes(line + "\n");
        s.Write(b, 0, b.Length);
        s.Flush();
    }

    // The library's commands: GAMES, LAUNCH <source> <id>, STEAM INSTALL <appid>
    public static bool Handle(string cmd, string[] p, NetworkStream s) {
        if (cmd == "GAMES") {
            List<Game> games = Scan();
            foreach (Game g in games) { Send(s, g.Line()); }
            Send(s, "END " + games.Count);
            return true;
        }
        if (cmd == "LAUNCH" && p.Length > 2) {
            Send(s, Launch(p[1].ToLowerInvariant(), p[2]));
            return true;
        }
        if (cmd == "STEAM" && p.Length > 2 && p[1].ToUpperInvariant() == "INSTALL") {
            Send(s, SteamInstall(p[2]));
            return true;
        }
        return false;
    }
    static List<Game> last = new List<Game>();
    static readonly Regex KeyValue = new Regex(@"""([^""]+)""\s+""((?:[^""\\]|\\.)*)""");
    static readonly Regex LibPath = new Regex(@"""(?:path|\d+)""\s+""([A-Za-z]:(?:[^""\\]|\\.)*)""");

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc proc, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    public static string Clean(string text) {
        if (text == null) { return ""; }
        string t = text.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ').Trim();
        return t.Length > 200 ? t.Substring(0, 200) : t;
    }

    static string Unescape(string text) {
        try { return Regex.Unescape(text); } catch { return text.Replace(@"\\", @"\"); }
    }

    static long ParseLong(string text) {
        long v;
        return long.TryParse(text, out v) ? v : 0;
    }

    static bool IsDigits(string text) {
        if (string.IsNullOrEmpty(text) || text.Length > 12) { return false; }
        foreach (char c in text) { if (c < '0' || c > '9') { return false; } }
        return true;
    }

    static string RegValue(RegistryKey root, string path, string name) {
        try {
            using (RegistryKey k = root.OpenSubKey(path)) {
                if (k == null) { return null; }
                object v = k.GetValue(name);
                return v == null ? null : v.ToString();
            }
        } catch { return null; }
    }

    public static string SteamPath() {
        string p = RegValue(Registry.CurrentUser, @"Software\Valve\Steam", "SteamPath");
        if (string.IsNullOrEmpty(p)) { p = RegValue(Registry.LocalMachine, @"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath"); }
        if (string.IsNullOrEmpty(p)) { p = RegValue(Registry.LocalMachine, @"SOFTWARE\Valve\Steam", "InstallPath"); }
        if (string.IsNullOrEmpty(p)) { return null; }
        p = p.Replace('/', '\\');
        return Directory.Exists(p) ? p : null;
    }

    static Dictionary<string, string> Kv(string text) {
        Dictionary<string, string> d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (Match m in KeyValue.Matches(text)) {
            string k = m.Groups[1].Value;
            if (!d.ContainsKey(k)) { d[k] = Unescape(m.Groups[2].Value); }
        }
        return d;
    }

    static string Get(Dictionary<string, string> d, string key) {
        string v;
        return d.TryGetValue(key, out v) ? v : "";
    }

    static void ScanSteam(List<Game> into) {
        string steam = SteamPath();
        if (steam == null) { return; }
        List<string> libs = new List<string>();
        libs.Add(steam);
        string vdf = Path.Combine(steam, @"steamapps\libraryfolders.vdf");
        try {
            if (File.Exists(vdf)) {
                foreach (Match m in LibPath.Matches(File.ReadAllText(vdf))) {
                    string lib = Unescape(m.Groups[1].Value);
                    bool seen = false;
                    foreach (string l in libs) { if (string.Equals(l.TrimEnd('\\'), lib.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) { seen = true; } }
                    if (!seen) { libs.Add(lib); }
                }
            }
        } catch { }
        HashSet<string> ids = new HashSet<string>();
        foreach (string lib in libs) {
            string apps = Path.Combine(lib, "steamapps");
            string[] files;
            try { files = Directory.GetFiles(apps, "appmanifest_*.acf"); } catch { continue; }
            foreach (string f in files) {
                try {
                    Dictionary<string, string> kv = Kv(File.ReadAllText(f));
                    string id = Get(kv, "appid");
                    string name = Get(kv, "name");
                    if (!IsDigits(id) || name.Length == 0 || ids.Contains(id)) { continue; }
                    if (id == "228980" || name.StartsWith("Steamworks Common", StringComparison.OrdinalIgnoreCase)
                            || name.StartsWith("Proton", StringComparison.OrdinalIgnoreCase)
                            || name.StartsWith("Steam Linux Runtime", StringComparison.OrdinalIgnoreCase)) { continue; }
                    ids.Add(id);
                    Game g = new Game();
                    g.Source = "steam";
                    g.Id = id;
                    g.ArtId = id;
                    g.Name = name;
                    long flags = ParseLong(Get(kv, "StateFlags"));
                    long toGet = ParseLong(Get(kv, "BytesToDownload"));
                    long got = ParseLong(Get(kv, "BytesDownloaded"));
                    bool full = (flags & 4) != 0;
                    if (toGet > 0 && got < toGet && flags != 4) {
                        g.State = full ? "updating" : "installing";
                        g.Progress = (int)(got * 100 / toGet);
                    } else if (full) {
                        g.State = "installed";
                    } else {
                        g.State = "installing";
                        g.Progress = 0;
                    }
                    g.SizeMb = ParseLong(Get(kv, "SizeOnDisk")) / (1024 * 1024);
                    g.LastPlayed = ParseLong(Get(kv, "LastPlayed"));
                    g.Dir = Path.Combine(apps, @"common\" + Get(kv, "installdir"));
                    into.Add(g);
                } catch { }
            }
        }
    }

    static string JsonText(string text, string key) {
        Match m = Regex.Match(text, "\"" + Regex.Escape(key) + @"""\s*:\s*""((?:[^""\\]|\\.)*)""");
        return m.Success ? Unescape(m.Groups[1].Value) : "";
    }

    static long JsonNumber(string text, string key) {
        Match m = Regex.Match(text, "\"" + Regex.Escape(key) + @"""\s*:\s*""?(-?\d+)");
        return m.Success ? ParseLong(m.Groups[1].Value) : 0;
    }

    static void ScanEpic(List<Game> into) {
        string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            @"Epic\EpicGamesLauncher\Data\Manifests");
        string[] files;
        try { files = Directory.GetFiles(dir, "*.item"); } catch { return; }
        foreach (string f in files) {
            try {
                string t = File.ReadAllText(f);
                if (Regex.IsMatch(t, @"""AppCategories""\s*:\s*\[[^\]]*""addons""")) { continue; }
                string app = JsonText(t, "AppName");
                string name = JsonText(t, "DisplayName");
                if (app.Length == 0 || name.Length == 0 || app.IndexOf(' ') >= 0) { continue; }
                Game g = new Game();
                g.Source = "epic";
                g.Id = app;
                g.Name = name;
                g.State = Regex.IsMatch(t, @"""bIsIncompleteInstall""\s*:\s*true") ? "installing" : "installed";
                if (g.State == "installing") { g.Progress = 0; }
                g.SizeMb = JsonNumber(t, "InstallSize") / (1024 * 1024);
                g.Dir = JsonText(t, "InstallLocation");
                string ns = JsonText(t, "CatalogNamespace");
                string item = JsonText(t, "CatalogItemId");
                string target = ns.Length > 0 && item.Length > 0 ? ns + ":" + item + ":" + app : app;
                g.Uri = "com.epicgames.launcher://apps/" + System.Uri.EscapeDataString(target) + "?action=launch&silent=true";
                into.Add(g);
            } catch { }
        }
    }

    static void ScanGog(List<Game> into) {
        foreach (string path in new string[] { @"SOFTWARE\WOW6432Node\GOG.com\Games", @"SOFTWARE\GOG.com\Games" }) {
            try {
                using (RegistryKey games = Registry.LocalMachine.OpenSubKey(path)) {
                    if (games == null) { continue; }
                    foreach (string sub in games.GetSubKeyNames()) {
                        using (RegistryKey k = games.OpenSubKey(sub)) {
                            if (k == null) { continue; }
                            object depends = k.GetValue("dependsOn");
                            if (depends != null && depends.ToString().Trim().Length > 0) { continue; }
                            string exe = (k.GetValue("exe") ?? "").ToString();
                            string name = (k.GetValue("gameName") ?? "").ToString();
                            string id = (k.GetValue("gameID") ?? sub).ToString();
                            if (exe.Length == 0 || name.Length == 0 || !IsDigits(id) || !File.Exists(exe)) { continue; }
                            bool dup = false;
                            foreach (Game other in into) { if (other.Source == "gog" && other.Id == id) { dup = true; } }
                            if (dup) { continue; }
                            Game g = new Game();
                            g.Source = "gog";
                            g.Id = id;
                            g.Name = name;
                            g.Exe = exe;
                            g.Dir = (k.GetValue("workingDir") ?? k.GetValue("path") ?? "").ToString();
                            into.Add(g);
                        }
                    }
                }
            } catch { }
        }
    }

    public static List<Game> Scan() {
        List<Game> games = new List<Game>();
        ScanSteam(games);
        ScanEpic(games);
        ScanGog(games);
        lock (scanLock) { last = games; }
        return games;
    }

    static Game Find(string source, string id) {
        lock (scanLock) {
            foreach (Game g in last) { if (g.Source == source && g.Id == id) { return g; } }
        }
        return null;
    }

    static void Open(string uri) {
        ProcessStartInfo info = new ProcessStartInfo(uri);
        info.UseShellExecute = true;
        Process.Start(info);
    }

    public static string Launch(string source, string id) {
        Game g = Find(source, id);
        if (g == null) { Scan(); g = Find(source, id); }
        if (g == null) { return "ERR unknown"; }
        if (g.State != "installed") { return "ERR notinstalled"; }
        try {
            if (g.Source == "steam") {
                Open("steam://rungameid/" + g.Id);
            } else if (g.Source == "epic") {
                Open(g.Uri);
            } else {
                ProcessStartInfo info = new ProcessStartInfo(g.Exe);
                info.UseShellExecute = true;
                if (g.Dir.Length > 0 && Directory.Exists(g.Dir)) { info.WorkingDirectory = g.Dir; }
                Process.Start(info);
            }
        } catch (Exception e) {
            return "ERR " + Clean(e.Message);
        }
        Log("Starting " + g.Name + " for the handheld");
        return "OK";
    }

    public static string SteamInstall(string id) {
        if (!IsDigits(id)) { return "ERR id"; }
        if (SteamPath() == null) { return "ERR nosteam"; }
        try { Open("steam://install/" + id); } catch (Exception e) { return "ERR " + Clean(e.Message); }
        Log("Steam: install requested (app " + id + ")");
        Thread t = new Thread(ConfirmInstallDialog);
        t.IsBackground = true;
        t.Start();
        return "OK";
    }

    // Steam asks where to install: when its dialog comes up, press its default button (Install)
    // the way a person would. Only that dialog, and only when it really has the focus.
    static void ConfirmInstallDialog() {
        for (int i = 0; i < 30; i++) {
            Thread.Sleep(500);
            IntPtr w = FindSteamWindow("Install");
            if (w == IntPtr.Zero) { continue; }
            Thread.Sleep(900);
            keybd_event(0x12, 0, 0, UIntPtr.Zero);
            keybd_event(0x12, 0, 2, UIntPtr.Zero);
            ShowWindow(w, 9);
            SetForegroundWindow(w);
            Thread.Sleep(300);
            if (GetForegroundWindow() == w) {
                keybd_event(0x0D, 0, 0, UIntPtr.Zero);
                keybd_event(0x0D, 0, 2, UIntPtr.Zero);
                Log("Steam: install confirmed");
            } else {
                Log("Steam: confirm the install on the PC (the window didn't take the focus)");
            }
            return;
        }
    }

    static IntPtr FindSteamWindow(string titleStart) {
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate (IntPtr hWnd, IntPtr lParam) {
            if (!IsWindowVisible(hWnd)) { return true; }
            StringBuilder title = new StringBuilder(256);
            GetWindowText(hWnd, title, title.Capacity);
            if (!title.ToString().StartsWith(titleStart, StringComparison.OrdinalIgnoreCase)) { return true; }
            uint pid;
            GetWindowThreadProcessId(hWnd, out pid);
            try {
                string proc = Process.GetProcessById((int)pid).ProcessName;
                if (proc.Equals("steamwebhelper", StringComparison.OrdinalIgnoreCase) || proc.Equals("steam", StringComparison.OrdinalIgnoreCase)) {
                    found = hWnd;
                    return false;
                }
            } catch { }
            return true;
        }, IntPtr.Zero);
        return found;
    }
}
'@
    } catch {
        Say ("PC library unavailable: " + $_.Exception.Message) "Yellow"
    }
}
if ("PcGames" -as [type]) {
    try {
        [PcGames]::Logger = [Delegate]::CreateDelegate([Action[string]], [PocketCompanion].GetMethod("Log"))
        [PocketCompanion]::Extra = [Delegate]::CreateDelegate([Func[string, string[], System.Net.Sockets.NetworkStream, bool]], [PcGames].GetMethod("Handle"))
        $pcLibraryOk = $true
    } catch {
        Say ("PC library unavailable: " + $_.Exception.Message) "Yellow"
    }
}

# Secret for Wi-Fi control (kept between runs) and a fresh PIN for linking each run
$tokenFile = "$DataDir\companion-token.txt"
$token = ""
if (Test-Path $tokenFile) { $token = ((Get-Content $tokenFile -Raw) + "").Trim() }
if ($token.Length -lt 32) {
    $bytes = New-Object byte[] 16
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $token = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
    Set-Content -Path $tokenFile -Value $token -NoNewline
}
$pinBytes = New-Object byte[] 4
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($pinBytes)
$pin = ([BitConverter]::ToUInt32($pinBytes, 0) % 1000000).ToString().PadLeft(6, "0")

$companionOk = $true
try {
    [PocketCompanion]::Start($CompanionPort, $token, $pin)
} catch {
    $companionOk = $false
    Say "PC helper: port $CompanionPort is busy (another copy of this window, or the old Artemis Pocket PC, is open? Close it and start this again)" "Yellow"
}
if ($companionOk) {
    Say "PC helper ready  -  Wi-Fi link PIN: $pin" "Green"
    if ($pcLibraryOk) { Say "PC library ready: Steam, Epic and GOG games show on the handheld" "Green" }
    $cableId = 0
    $cable = [MicPlayer]::FindCable([ref]$cableId)
    if ($cable) { Say "Microphone: VB-Cable found ($cable)" "Green" }
    else { Say "Microphone: install VB-Audio Virtual Cable (free) from vb-audio.com/Cable to use the handheld's mic" "DarkGray" }
}

# Wi-Fi control needs the port open in Windows Firewall (asked once, needs admin once)
$fwFlag = "$DataDir\firewall-asked"
if ($companionOk -and -not $Autostart -and -not (Test-Path $fwFlag)) {
    New-Item -ItemType File -Force -Path $fwFlag | Out-Null
    $rule = Get-NetFirewallRule -DisplayName "Pocket Nexus PC" -ErrorAction SilentlyContinue
    if (-not $rule) { $rule = Get-NetFirewallRule -DisplayName "Artemis Pocket PC" -ErrorAction SilentlyContinue }
    if (-not $rule) {
        $answer = Read-Host "  Let the handheld use these PC controls over Wi-Fi too (adds a firewall rule, asks for admin)? (Y/N)"
        if ($answer -match "^[Yy]") {
            $cmd = "New-NetFirewallRule -DisplayName 'Pocket Nexus PC' -Direction Inbound -Protocol TCP -LocalPort $CompanionPort -Action Allow -Profile Private,Domain"
            try {
                Start-Process powershell.exe -Verb RunAs -Wait -ArgumentList "-NoProfile -Command $cmd"
                Say "Firewall rule added (private networks only)" "Green"
            } catch {
                Say "Firewall rule not added - Wi-Fi control will only work if Windows asks and you allow it" "Yellow"
            }
        }
    }
}

# ---- Apollo check ---------------------------------------------------------------------------
if (Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue) {
    Say "Apollo/Sunshine is running" "Green"
} else {
    Say "Apollo/Sunshine is not running yet - start it before streaming" "Yellow"
}

# ---- Start with Windows (asked once) --------------------------------------------------------
$startup = [Environment]::GetFolderPath("Startup")
$oldLinks = @("$startup\Artemis Pocket USB bridge.lnk", "$startup\Artemis Pocket PC.lnk")
$newLink = "$startup\Pocket Nexus PC.lnk"
function New-StartupLink {
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut($newLink)
    $lnk.TargetPath = "powershell.exe"
    $lnk.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Minimized -File `"$ScriptPath`" -Autostart"
    $lnk.WindowStyle = 7
    $lnk.Save()
}
$hadOldLink = $false
foreach ($oldLink in $oldLinks) {
    # The old helper (Artemis Pocket) started with Windows: this window replaces it
    if (Test-Path $oldLink) { Remove-Item $oldLink -ErrorAction SilentlyContinue; $hadOldLink = $true }
}
if ($hadOldLink) {
    New-StartupLink
    Say "Startup shortcut updated to Pocket Nexus PC" "Green"
}
$flag = "$DataDir\autostart-asked-v2"
if (-not $Autostart -and -not (Test-Path $flag) -and -not (Test-Path $newLink)) {
    New-Item -ItemType File -Force -Path $flag | Out-Null
    $answer = Read-Host "  Start Pocket Nexus PC automatically when Windows starts? (Y/N)"
    if ($answer -match "^[Yy]") {
        New-StartupLink
        Say "Added to Windows startup (delete it from shell:startup to undo)" "Green"
    }
}

# ---- Device loop ----------------------------------------------------------------------------
function Set-Reverse {
    & $adb reverse --remove-all 2>$null | Out-Null
    foreach ($p in $TcpPorts) { & $adb reverse "tcp:$p" "tcp:$p" 2>$null | Out-Null }
    & $adb reverse "tcp:$TunnelPort" "tcp:$TunnelPort" 2>$null | Out-Null
    & $adb reverse "tcp:$CompanionPort" "tcp:$CompanionPort" 2>$null | Out-Null
}

function Reset-UsbMode {
    # Undo the RNDIS/NCM attempt from the tethering script, if it was run
    $cfg = (& $adb shell getprop sys.usb.config 2>$null) -join ""
    if ($cfg -match "rndis|ncm") {
        & $adb shell svc usb setFunctions 2>$null | Out-Null
        Start-Sleep -Seconds 3
        & $adb wait-for-device 2>$null | Out-Null
    }
}

Write-Host ""
Say "Plug the Retroid in (USB debugging on), or just play over Wi-Fi. Leave this window open." "White"
Write-Host ""

$lastState = ""
$lastSessions = 0
while ($true) {
    $state = ((& $adb get-state 2>&1) -join "").Trim()
    if ($state -ne $lastState) {
        if ($state -eq "device") {
            Reset-UsbMode
            Set-Reverse
            $model = ((& $adb shell getprop ro.product.model 2>$null) -join "").Trim()
            Stamp "Connected: $model - open Pocket Nexus" "Green"
        } elseif ($state -match "unauthorized") {
            Stamp "Press 'Allow' on the handheld (tick 'Always allow from this computer')" "Yellow"
        } else {
            if ($lastState -eq "device") { Stamp "Handheld unplugged - waiting..." "DarkGray" }
            elseif ($lastState -eq "") { Stamp "Waiting for the handheld..." "DarkGray" }
        }
        $lastState = $state
    } elseif ($state -eq "device") {
        # Keep the port rules alive (they vanish if adb restarts)
        $rules = ((& $adb reverse --list 2>$null) -join " ")
        if ($rules -notmatch "tcp:$CompanionPort") { Set-Reverse }
    }

    $sessions = [PocketBridge]::Sessions
    if ($sessions -ne $lastSessions) {
        Stamp "Pocket Nexus linked through the cable" "Cyan"
        $lastSessions = $sessions
    }
    if ($companionOk) {
        $evt = $null
        while ([PocketCompanion]::Events.TryDequeue([ref]$evt)) { Stamp $evt "Cyan" }
    }
    Start-Sleep -Seconds 2
}
