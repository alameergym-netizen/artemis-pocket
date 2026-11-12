# Pocket Nexus PC - everything the handheld needs on the gaming PC
#
#  1. USB bridge   Streams to the handheld over the USB-C cable on ROMs where Android's USB
#                  tethering is turned off (Retroid Pocket). Needs USB debugging on the handheld.
#  2. PC helper    Lets the handheld put this PC to sleep, restart it, shut it down, lock it,
#                  turn the display off, read its status, and use the handheld's microphone
#                  on this PC (through VB-Audio Virtual Cable, free: https://vb-audio.com/Cable/).
#                  Through the cable it just works; over Wi-Fi the handheld links once with the
#                  PIN (or by itself after one stream over the cable).
#  3. PC library   The games here for the handheld: Steam (installed, owned but not installed,
#                  tools, non-Steam games), Epic, GOG and PC apps picked on the handheld; it starts
#                  them and asks Steam to install games.
#
# How to run it
#   PocketNexus-PC.bat              sets it up, then it runs hidden in the background (an icon
#                                   next to the clock: PIN, status, Start with Windows, Quit)
#                                   and starts with Windows, hidden
#   PocketNexus-PC.bat -Console     runs in this window instead (to watch what it does)
#   PocketNexus-PC.bat -WifiPort N  the port the handheld reaches it on over Wi-Fi (47110 unless
#                                   changed; also from its icon: Wi-Fi port). Kept for next time.
#
# How the cable part works
#   adb reverse   handheld 127.0.0.1:47984/47989/48010 -> Apollo on this PC   (TCP)
#   adb reverse   handheld 127.0.0.1:47100             -> the bridge (stream packets)
#   adb reverse   handheld 127.0.0.1:47110             -> the PC helper (on its Wi-Fi port)

param([switch]$Autostart, [switch]$Background, [switch]$Console, [switch]$SelfTest, [int]$WifiPort = 0)

$ErrorActionPreference = "Continue"
if ($Autostart) { $Background = $true }
$ScriptPath = $MyInvocation.MyCommand.Path
$here = Split-Path -Parent $ScriptPath
$TunnelPort = 47100
$DefaultCompanionPort = 47110
$TcpPorts = @(47984, 47989, 48010, 47990)
$DataDir = "$env:LOCALAPPDATA\ArtemisPocket"
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

# The PC helper's port over Wi-Fi: 47110 unless it was changed (-WifiPort, or the icon's menu).
# Over the cable the handheld always uses 47110 (mapped to this port), so only Wi-Fi cares.
$PortFile = "$DataDir\wifi-port.txt"
function Read-WifiPort {
    try {
        if (Test-Path $PortFile) {
            $v = 0
            if ([int]::TryParse(((Get-Content $PortFile -Raw) + "").Trim(), [ref]$v) -and $v -ge 1024 -and $v -le 65535) { return $v }
        }
    } catch { }
    return $DefaultCompanionPort
}
if ($WifiPort -ne 0) {
    if ($WifiPort -ge 1024 -and $WifiPort -le 65535) {
        Set-Content -Path $PortFile -Value $WifiPort -NoNewline
    } else {
        Write-Host "  The Wi-Fi port must be between 1024 and 65535 - kept the old one" -ForegroundColor Red
    }
}
$CompanionPort = Read-WifiPort
$LogFile = "$DataDir\pocket-nexus-pc.log"
$StatusFile = "$DataDir\status.txt"
$Launcher = "$DataDir\PocketNexus-PC.vbs"
$StartupLink = (Join-Path ([Environment]::GetFolderPath("Startup")) "Pocket Nexus PC.lnk")
$Quiet = [bool]$Background

$script:tray = $null
$script:adb = $null
$script:pin = ""
$script:companionOk = $false
$script:bridgeOk = $false
$script:pcLibraryError = ""
$script:lastState = ""
$script:lastSessions = 0

if (-not $Quiet) { try { $Host.UI.RawUI.WindowTitle = "Pocket Nexus PC" } catch { } }

function Write-Log($text) {
    try {
        if ((Test-Path $LogFile) -and ((Get-Item $LogFile).Length -gt 1MB)) { Move-Item -Force $LogFile "$LogFile.old" }
        Add-Content -Path $LogFile -Value ("[" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "] " + $text) -Encoding UTF8
    } catch { }
}

# Notifications next to the clock for what matters (background mode)
function Show-Note($text) {
    if ($script:tray -eq $null) { return }
    if ($text -match "Connected|Allow|PIN|confirm|Starting|install") {
        try { $script:tray.ShowBalloonTip(4000, "Pocket Nexus PC", $text, [System.Windows.Forms.ToolTipIcon]::Info) } catch { }
    }
}

function Say($text, $color = "Gray") {
    Write-Log $text
    if (-not $Quiet) { Write-Host ("  " + $text) -ForegroundColor $color }
}

function Stamp($text, $color = "Gray") {
    Write-Log $text
    if ($Quiet) { Show-Note $text }
    else { Write-Host ("  [" + (Get-Date -Format "HH:mm:ss") + "] " + $text) -ForegroundColor $color }
}

# ---- One helper at a time --------------------------------------------------------------------
# The old Artemis Pocket PC (or another copy of this one) holds the same ports: it's closed first.
# serversOnly: leave the setup window that started this one alone.
function Stop-OtherHelpers([bool]$serversOnly) {
    $stopped = 0
    $procs = @()
    try { $procs = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'" -ErrorAction Stop) }
    catch { try { $procs = @(Get-WmiObject Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'") } catch { } }
    foreach ($p in $procs) {
        if ($p.ProcessId -eq $PID) { continue }
        $cl = "" + $p.CommandLine
        $old = $cl -match "ArtemisPocket-PC\.ps1|ArtemisPocket-USB-Bridge"
        $ours = $cl -match "PocketNexus-PC\.ps1"
        if ($ours -and $serversOnly -and ($cl -notmatch "-Background|-Autostart|-Console")) { continue }
        if ($old -or $ours) {
            try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop; $stopped++ }
            catch { Say "Couldn't close an older Pocket helper (process $($p.ProcessId)): close its window by hand" "Yellow" }
        }
    }
    if ($stopped -gt 0) { Start-Sleep -Milliseconds 1500 }
    return $stopped
}

# ---- ADB ---------------------------------------------------------------------------------
function Find-Adb {
    foreach ($p in @("$here\platform-tools\adb.exe", "$here\adb.exe", "$DataDir\platform-tools\adb.exe")) {
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Get-Adb {
    $found = Find-Adb
    if ($found) { return $found }
    Say "ADB not found - downloading Google's platform-tools (about 7 MB)..." "Yellow"
    $zip = "$DataDir\platform-tools.zip"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $DataDir -Force
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch {
        Say "ADB download failed: $($_.Exception.Message). USB-C streaming needs it (Wi-Fi works without)." "Yellow"
        return $null
    }
    return Find-Adb
}

# ---- The code (compiled on the fly, runs at native speed) ----------------------------------
function Load-Types {
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
    // Why the PC library add-on isn't there (shown on the handheld)
    public static string ExtraError = "";
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

    public static int WifiPort = 47110;

    public static void Start(int port, string token, string pin) {
        Token = token;
        Pin = pin;
        WifiPort = port;
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
                } else if (cmd == "WIFIPORT") {
                    WriteLine(s, "WIFIPORT " + WifiPort);
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
                    WriteLine(s, "VERSION 3" + (Extra != null ? " games" : ""));
                } else if (cmd == "MIC") {
                    int rate = p.Length > 1 ? ParseInt(p[1], 48000) : 48000;
                    int channels = p.Length > 2 ? ParseInt(p[2], 1) : 1;
                    MicPlayer.Run(s, rate, channels);
                    return;
                } else {
                    // Add-ons (the PC library) get the commands this doesn't know
                    Func<string, string[], NetworkStream, bool> extra = Extra;
                    if (extra == null && (cmd == "GAMES" || cmd == "LAUNCH" || cmd == "STEAM" || cmd == "PROGRAMS"
                            || cmd == "ADDAPP" || cmd == "REMOVEAPP" || cmd == "PCLIB")) {
                        WriteLine(s, "ERR pclib " + (ExtraError.Length > 0 ? ExtraError.Replace('\n', ' ').Replace('\r', ' ') : "not loaded"));
                    } else if (extra == null || !extra(cmd, p, s)) {
                        WriteLine(s, "ERR unknown");
                    }
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

    # The PC library: an add-on compiled on its own (if it can't load, everything else works)
    $script:pcLibraryError = ""
    $script:pcLibraryErrors = @()
    if (-not ("PcGames" -as [type])) {
        try {
Add-Type -IgnoreWarnings -ErrorAction Stop -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Win32;

// The PC's games for the handheld's PC library:
//   Steam      installed games (every library folder), games owned but not installed, tools,
//              and non-Steam games added to Steam
//   Epic, GOG  installed games
//   PC apps    programs picked on the handheld from this PC's Start menu and desktop
// Only things found by a scan can be started or installed (never an arbitrary command).
// C# 5 on purpose: Windows PowerShell compiles this with the .NET Framework compiler.
public static class PcGames {
    public class Game {
        public string Source = "", Id = "", Name = "", State = "installed", Exe = "", Dir = "", Uri = "", ArtId = "", Kind = "game";
        public int Progress = -1;
        public long SizeMb = 0, LastPlayed = 0, Playtime = 0;

        public string Line() {
            return "G\t" + Source + "\t" + Clean(Id) + "\t" + Clean(Name) + "\t" + State + "\t" + Progress + "\t"
                + SizeMb + "\t" + LastPlayed + "\t" + Clean(ArtId) + "\t" + Playtime + "\t" + Kind;
        }
    }

    public class Program {
        public string Id = "", Name = "", Path = "", Where = "";
    }

    public static Action<string> Logger;
    /** Where custom apps are kept (set by the script). */
    public static string DataDir = "";
    /** For the self-test: a Steam folder to read instead of the real one. */
    public static string SteamRootOverride;
    /** What the last scan found (for the PCLIB command and the log). */
    public static string LastNote = "";

    static readonly object scanLock = new object();
    static List<Game> last = new List<Game>();
    static List<Program> lastPrograms = new List<Program>();
    static readonly Regex KeyValue = new Regex(@"""([^""]+)""\s+""((?:[^""\\]|\\.)*)""");
    static readonly Regex LibPath = new Regex(@"""(?:path|\d+)""\s+""([A-Za-z]:(?:[^""\\]|\\.)*)""");
    const long SteamId64Base = 76561197960265728L;

    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc proc, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    static void Log(string text) {
        Action<string> l = Logger;
        if (l != null) { try { l(text); } catch { } }
    }

    static void Send(NetworkStream s, string line) {
        byte[] b = Encoding.UTF8.GetBytes(line + "\n");
        s.Write(b, 0, b.Length);
        s.Flush();
    }

    // The library's commands
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
            // "PICK": the handheld streams the PC's screen and the drive is chosen there
            bool pick = p.Length > 3 && p[3].ToUpperInvariant() == "PICK";
            Send(s, SteamInstall(p[2], !pick));
            return true;
        }
        if (cmd == "PROGRAMS") {
            List<Program> programs = ScanPrograms();
            foreach (Program pr in programs) { Send(s, "P\t" + pr.Id + "\t" + Clean(pr.Name) + "\t" + Clean(pr.Where)); }
            Send(s, "END " + programs.Count);
            return true;
        }
        if (cmd == "ADDAPP" && p.Length > 1) {
            Send(s, AddApp(p[1]));
            return true;
        }
        if (cmd == "REMOVEAPP" && p.Length > 1) {
            Send(s, RemoveApp(p[1]));
            return true;
        }
        if (cmd == "PCLIB") {
            Send(s, "PCLIB " + Clean(LastNote));
            return true;
        }
        if (cmd == "APOLLOLOG") {
            List<string> lines = ApolloLog();
            foreach (string l in lines) { Send(s, "L\t" + Clean(l)); }
            Send(s, "END " + lines.Count);
            return true;
        }
        return false;
    }

    // ---- Apollo's own log: why it couldn't start something -----------------------------------

    /** The last problems Apollo (or Vibepollo / Sunshine) wrote in its log, newest last. */
    public static List<string> ApolloLog() {
        List<string> found = new List<string>();
        string log = null;
        DateTime newest = DateTime.MinValue;
        List<string> dirs = new List<string>();
        foreach (string root in new string[] {
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                Environment.GetEnvironmentVariable("ProgramW6432") ?? "",
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86) }) {
            if (root.Length == 0 || !Directory.Exists(root)) { continue; }
            try { dirs.AddRange(Directory.GetDirectories(root)); } catch { }
        }
        foreach (string dir in dirs) {
            foreach (string name in new string[] { @"config\sunshine.log", @"config\apollo.log", "sunshine.log" }) {
                string f = Path.Combine(dir, name);
                try {
                    if (File.Exists(f) && File.GetLastWriteTimeUtc(f) > newest) {
                        newest = File.GetLastWriteTimeUtc(f);
                        log = f;
                    }
                } catch { }
            }
        }
        if (log == null) { return found; }
        try {
            string[] all = ReadShared(log).Split('\n');
            for (int i = Math.Max(0, all.Length - 400); i < all.Length; i++) {
                string l = all[i].Trim();
                if (l.Length == 0) { continue; }
                if (l.IndexOf("Error", StringComparison.OrdinalIgnoreCase) >= 0
                        || l.IndexOf("Couldn't", StringComparison.OrdinalIgnoreCase) >= 0
                        || l.IndexOf("Failed", StringComparison.OrdinalIgnoreCase) >= 0
                        || l.IndexOf("Fatal", StringComparison.OrdinalIgnoreCase) >= 0) {
                    found.Add(l);
                }
            }
        } catch { }
        if (found.Count > 6) { found = found.GetRange(found.Count - 6, 6); }
        return found;
    }

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
        return long.TryParse(text, NumberStyles.Integer, CultureInfo.InvariantCulture, out v) ? v : 0;
    }

    static bool IsDigits(string text) {
        if (string.IsNullOrEmpty(text) || text.Length > 20) { return false; }
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
        if (!string.IsNullOrEmpty(SteamRootOverride)) { return SteamRootOverride; }
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

    // ---- KeyValues: Valve's text (.vdf, .acf) and binary (shortcuts.vdf, appinfo.vdf) files ----

    public class KvNode {
        public Dictionary<string, object> Values = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);

        public KvNode Child(string key) {
            object v;
            return Values.TryGetValue(key, out v) ? v as KvNode : null;
        }

        public string Text(string key) {
            object v;
            if (!Values.TryGetValue(key, out v) || v == null || v is KvNode) { return ""; }
            return Convert.ToString(v, CultureInfo.InvariantCulture);
        }

        public long Number(string key) {
            object v;
            if (!Values.TryGetValue(key, out v) || v == null) { return 0; }
            if (v is int) { return (int)v; }
            if (v is long) { return (long)v; }
            if (v is ulong) { return unchecked((long)(ulong)v); }
            return ParseLong(Convert.ToString(v, CultureInfo.InvariantCulture));
        }

        public bool Has(string key) {
            return Values.ContainsKey(key);
        }
    }

    public static KvNode ParseText(string text) {
        int i = 0;
        return TextObject(text, ref i, 0);
    }

    static KvNode TextObject(string s, ref int i, int depth) {
        KvNode node = new KvNode();
        string key = null;
        while (true) {
            string token;
            int kind = NextToken(s, ref i, out token);
            if (kind == 0 || kind == 3) { return node; }
            if (kind == 2) {
                KvNode child = depth > 64 ? new KvNode() : TextObject(s, ref i, depth + 1);
                if (key != null) { node.Values[key] = child; }
                key = null;
                continue;
            }
            if (key == null) { key = token; }
            else { node.Values[key] = token; key = null; }
        }
    }

    // 0: end, 1: a string, 2: {, 3: }
    static int NextToken(string s, ref int i, out string token) {
        token = null;
        while (i < s.Length) {
            char c = s[i];
            if (char.IsWhiteSpace(c)) { i++; continue; }
            if (c == '/' && i + 1 < s.Length && s[i + 1] == '/') {
                while (i < s.Length && s[i] != '\n') { i++; }
                continue;
            }
            if (c == '{') { i++; return 2; }
            if (c == '}') { i++; return 3; }
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                i++;
                while (i < s.Length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.Length) {
                        i++;
                        char e = s[i];
                        b.Append(e == 'n' ? '\n' : (e == 't' ? '\t' : e));
                    } else {
                        b.Append(s[i]);
                    }
                    i++;
                }
                i++;
                token = b.ToString();
                return 1;
            }
            int start = i;
            while (i < s.Length && !char.IsWhiteSpace(s[i]) && s[i] != '{' && s[i] != '}' && s[i] != '"') { i++; }
            token = s.Substring(start, i - start);
            return 1;
        }
        return 0;
    }

    public class BinReader {
        public byte[] Data;
        public int Pos;
        /** appinfo.vdf v29: keys are indexes into this table. */
        public string[] Strings;

        public byte U8() { return Data[Pos++]; }
        public int I32() { int v = BitConverter.ToInt32(Data, Pos); Pos += 4; return v; }
        public uint U32() { uint v = BitConverter.ToUInt32(Data, Pos); Pos += 4; return v; }
        public long I64() { long v = BitConverter.ToInt64(Data, Pos); Pos += 8; return v; }
        public ulong U64() { ulong v = BitConverter.ToUInt64(Data, Pos); Pos += 8; return v; }

        public string CStr() {
            int start = Pos;
            while (Pos < Data.Length && Data[Pos] != 0) { Pos++; }
            string s = Encoding.UTF8.GetString(Data, start, Pos - start);
            Pos++;
            return s;
        }

        public string Key() {
            if (Strings == null) { return CStr(); }
            int i = I32();
            return i >= 0 && i < Strings.Length ? Strings[i] : "";
        }

        public KvNode Object(int depth) {
            KvNode node = new KvNode();
            while (Pos < Data.Length) {
                byte t = U8();
                if (t == 0x08 || t == 0x0B) { return node; }
                string key = Key();
                switch (t) {
                    case 0x00:
                        if (depth > 48) { throw new Exception("too deep"); }
                        node.Values[key] = Object(depth + 1);
                        break;
                    case 0x01:
                        node.Values[key] = CStr();
                        break;
                    case 0x02:
                    case 0x04:
                    case 0x06:
                        node.Values[key] = I32();
                        break;
                    case 0x03:
                        node.Values[key] = (double)BitConverter.ToSingle(Data, Pos);
                        Pos += 4;
                        break;
                    case 0x05:
                        while (Pos + 1 < Data.Length && (Data[Pos] != 0 || Data[Pos + 1] != 0)) { Pos += 2; }
                        Pos += 2;
                        break;
                    case 0x07:
                        node.Values[key] = U64();
                        break;
                    case 0x0A:
                        node.Values[key] = I64();
                        break;
                    default:
                        throw new Exception("unknown value type " + t);
                }
            }
            return node;
        }
    }

    /** Non-Steam games added to Steam (userdata/<id>/config/shortcuts.vdf). */
    public static List<Game> ReadShortcuts(byte[] data) {
        List<Game> games = new List<Game>();
        BinReader r = new BinReader();
        r.Data = data;
        KvNode root = r.Object(0);
        KvNode list = root.Child("shortcuts");
        if (list == null) { return games; }
        foreach (KeyValuePair<string, object> e in list.Values) {
            KvNode sc = e.Value as KvNode;
            if (sc == null || !sc.Has("appid")) { continue; }
            if (sc.Number("IsHidden") != 0) { continue; }
            string name = sc.Text("AppName").Trim();
            string exe = sc.Text("Exe").Trim();
            if (name.Length == 0 || exe.Length == 0) { continue; }
            uint appid = unchecked((uint)sc.Number("appid"));
            ulong gameId = ((ulong)appid << 32) | 0x02000000UL;
            Game g = new Game();
            g.Source = "nonsteam";
            g.Id = appid.ToString(CultureInfo.InvariantCulture);
            g.Name = name;
            g.Exe = exe.Trim('"');
            g.Dir = sc.Text("StartDir").Trim('"');
            g.Uri = "steam://rungameid/" + gameId.ToString(CultureInfo.InvariantCulture);
            g.LastPlayed = sc.Number("LastPlayTime");
            games.Add(g);
        }
        return games;
    }

    /** appinfo.vdf (v27, v28, v29): name and type of the wanted apps (null: all). */
    public static Dictionary<uint, string[]> ReadAppInfo(byte[] data, Dictionary<uint, bool> wanted) {
        Dictionary<uint, string[]> found = new Dictionary<uint, string[]>();
        BinReader r = new BinReader();
        r.Data = data;
        uint magic = r.U32();
        r.U32(); // universe
        if ((magic >> 8) != 0x075644) { throw new Exception("not an appinfo file"); }
        int version = (int)(magic & 0xFF);
        if (version >= 0x29) {
            long tableAt = r.I64();
            BinReader t = new BinReader();
            t.Data = data;
            t.Pos = (int)tableAt;
            uint count = t.U32();
            if (count > 10000000) { throw new Exception("bad string table"); }
            string[] strings = new string[count];
            for (uint i = 0; i < count; i++) { strings[i] = t.CStr(); }
            r.Strings = strings;
        }
        int header = version >= 0x28 ? 60 : 40;
        while (r.Pos + 8 <= data.Length) {
            uint appid = r.U32();
            if (appid == 0) { break; }
            uint size = r.U32();
            int end = r.Pos + (int)size;
            if (end > data.Length || end < r.Pos) { break; }
            if (wanted == null || wanted.ContainsKey(appid)) {
                try {
                    r.Pos += header;
                    KvNode root = r.Object(0);
                    KvNode info = root.Child("appinfo");
                    KvNode common = info != null ? info.Child("common") : null;
                    if (common != null) {
                        found[appid] = new string[] { common.Text("name"), common.Text("type").ToLowerInvariant() };
                    }
                } catch { }
            }
            r.Pos = end;
        }
        return found;
    }

    // ---- Steam ----------------------------------------------------------------------------------

    static Dictionary<uint, string[]> appInfoCache = new Dictionary<uint, string[]>();
    static Dictionary<uint, bool> appInfoAsked = new Dictionary<uint, bool>();
    static long appInfoStamp = 0;

    static Dictionary<uint, string[]> AppInfoFor(string steam, Dictionary<uint, bool> wanted) {
        string file = Path.Combine(steam, @"appcache\appinfo.vdf");
        if (!File.Exists(file)) { return new Dictionary<uint, string[]>(); }
        long stamp = File.GetLastWriteTimeUtc(file).Ticks;
        bool fresh = stamp == appInfoStamp;
        if (fresh) {
            foreach (uint id in wanted.Keys) {
                if (!appInfoAsked.ContainsKey(id)) { fresh = false; break; }
            }
        }
        if (!fresh) {
            byte[] data;
            using (FileStream f = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                data = new byte[f.Length];
                int off = 0;
                while (off < data.Length) {
                    int n = f.Read(data, off, data.Length - off);
                    if (n <= 0) { break; }
                    off += n;
                }
            }
            Dictionary<uint, string[]> found = ReadAppInfo(data, wanted);
            appInfoCache = found;
            appInfoAsked = new Dictionary<uint, bool>(wanted);
            appInfoStamp = stamp;
        }
        return appInfoCache;
    }

    static string ReadShared(string path) {
        using (FileStream f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
            using (StreamReader r = new StreamReader(f, Encoding.UTF8)) { return r.ReadToEnd(); }
        }
    }

    /** The Steam accounts to read (userdata folder names): the one signed in last first. */
    static List<string> SteamAccounts(string steam) {
        List<string> ids = new List<string>();
        try {
            string users = Path.Combine(steam, @"config\loginusers.vdf");
            if (File.Exists(users)) {
                KvNode root = ParseText(ReadShared(users));
                KvNode list = root.Child("users");
                if (list != null) {
                    foreach (KeyValuePair<string, object> e in list.Values) {
                        KvNode u = e.Value as KvNode;
                        long id64;
                        if (u == null || !long.TryParse(e.Key, out id64) || id64 <= SteamId64Base) { continue; }
                        string account = (id64 - SteamId64Base).ToString(CultureInfo.InvariantCulture);
                        if (u.Text("MostRecent") == "1") { ids.Insert(0, account); } else { ids.Add(account); }
                    }
                }
            }
        } catch { }
        try {
            string userdata = Path.Combine(steam, "userdata");
            if (Directory.Exists(userdata)) {
                foreach (string d in Directory.GetDirectories(userdata)) {
                    string n = Path.GetFileName(d);
                    if (IsDigits(n) && n != "0" && !ids.Contains(n)) { ids.Add(n); }
                }
            }
        } catch { }
        return ids;
    }

    static bool Excluded(string id, string name) {
        return id == "228980" || id == "7" || id == "753"
            || name.StartsWith("Steamworks Common", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("Proton", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("Steam Linux Runtime", StringComparison.OrdinalIgnoreCase);
    }

    static string KindOf(string type) {
        if (type == "tool") { return "tool"; }
        if (type == "application") { return "app"; }
        if (type == "demo") { return "demo"; }
        return "game";
    }

    static void ScanSteam(List<Game> into, StringBuilder note) {
        string steam = SteamPath();
        if (steam == null) { note.Append("steam=none "); return; }
        List<string> libs = new List<string>();
        libs.Add(steam);
        string vdf = Path.Combine(steam, @"steamapps\libraryfolders.vdf");
        try {
            if (File.Exists(vdf)) {
                foreach (Match m in LibPath.Matches(ReadShared(vdf))) {
                    string lib = Unescape(m.Groups[1].Value);
                    bool seen = false;
                    foreach (string l in libs) { if (string.Equals(l.TrimEnd('\\'), lib.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) { seen = true; } }
                    if (!seen) { libs.Add(lib); }
                }
            }
        } catch { }

        // Installed (and installing) games: one manifest each
        Dictionary<string, Game> byId = new Dictionary<string, Game>();
        foreach (string lib in libs) {
            string apps = Path.Combine(lib, "steamapps");
            string[] files;
            try { files = Directory.GetFiles(apps, "appmanifest_*.acf"); } catch { continue; }
            foreach (string f in files) {
                try {
                    Dictionary<string, string> kv = Kv(ReadShared(f));
                    string id = Get(kv, "appid");
                    string name = Get(kv, "name");
                    if (!IsDigits(id) || name.Length == 0 || byId.ContainsKey(id) || Excluded(id, name)) { continue; }
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
                    byId[id] = g;
                    into.Add(g);
                } catch { }
            }
        }
        note.Append("installed=" + byId.Count + " ");

        // The account: playtime, and the games of its library that aren't installed
        List<string> accounts = SteamAccounts(steam);
        Dictionary<uint, long[]> played = new Dictionary<uint, long[]>();
        int shortcuts = 0;
        foreach (string account in accounts) {
            string config = Path.Combine(steam, @"userdata\" + account + @"\config");
            try {
                string local = Path.Combine(config, "localconfig.vdf");
                if (File.Exists(local)) {
                    KvNode root = ParseText(ReadShared(local));
                    KvNode store = root.Child("UserLocalConfigStore");
                    KvNode node = store != null ? store.Child("Software") : null;
                    node = node != null ? node.Child("Valve") : null;
                    node = node != null ? node.Child("Steam") : null;
                    KvNode apps = node != null ? node.Child("apps") : null;
                    if (apps != null) {
                        foreach (KeyValuePair<string, object> e in apps.Values) {
                            KvNode a = e.Value as KvNode;
                            uint id;
                            if (a == null || !uint.TryParse(e.Key, out id) || played.ContainsKey(id)) { continue; }
                            played[id] = new long[] { a.Number("Playtime"), a.Number("LastPlayed") };
                        }
                    }
                }
            } catch { }
            try {
                string sc = Path.Combine(config, "shortcuts.vdf");
                if (File.Exists(sc)) {
                    foreach (Game g in ReadShortcuts(File.ReadAllBytes(sc))) {
                        bool dup = false;
                        foreach (Game other in into) { if (other.Source == "nonsteam" && other.Id == g.Id) { dup = true; } }
                        if (!dup) { into.Add(g); shortcuts++; }
                    }
                }
            } catch (Exception e) { note.Append("shortcuts-error=" + Clean(e.Message) + " "); }
        }
        note.Append("nonsteam=" + shortcuts + " ");

        // In the library: what Steam keeps library art for, and what this account has played
        Dictionary<uint, bool> wanted = new Dictionary<uint, bool>();
        try {
            string cache = Path.Combine(steam, @"appcache\librarycache");
            if (Directory.Exists(cache)) {
                foreach (string entry in Directory.GetFileSystemEntries(cache)) {
                    string n = Path.GetFileName(entry);
                    int k = 0;
                    while (k < n.Length && n[k] >= '0' && n[k] <= '9') { k++; }
                    uint id;
                    if (k > 0 && uint.TryParse(n.Substring(0, k), out id) && id > 0) { wanted[id] = true; }
                }
            }
        } catch { }
        foreach (uint id in played.Keys) { wanted[id] = true; }
        foreach (Game g in into) {
            uint id;
            if (g.Source == "steam" && uint.TryParse(g.Id, out id)) { wanted[id] = true; }
        }

        Dictionary<uint, string[]> info;
        try {
            info = AppInfoFor(steam, wanted);
            note.Append("appinfo=" + info.Count + " ");
        } catch (Exception e) {
            info = new Dictionary<uint, string[]>();
            note.Append("appinfo-error=" + Clean(e.Message) + " ");
        }

        int owned = 0;
        foreach (uint id in wanted.Keys) {
            string key = id.ToString(CultureInfo.InvariantCulture);
            string[] about;
            info.TryGetValue(id, out about);
            long[] time;
            played.TryGetValue(id, out time);
            Game local;
            if (byId.TryGetValue(key, out local)) {
                if (about != null) { local.Kind = KindOf(about[1]); }
                if (time != null) {
                    local.Playtime = time[0];
                    local.LastPlayed = Math.Max(local.LastPlayed, time[1]);
                }
                continue;
            }
            // Not installed: only what is surely a game, an app or a tool of this library
            if (about == null || about[0].Length == 0) { continue; }
            string type = about[1];
            if (type != "game" && type != "application" && type != "tool" && type != "demo") { continue; }
            if (Excluded(key, about[0])) { continue; }
            Game g = new Game();
            g.Source = "steam";
            g.Id = key;
            g.ArtId = key;
            g.Name = about[0];
            g.State = "notinstalled";
            g.Kind = KindOf(type);
            if (time != null) {
                g.Playtime = time[0];
                g.LastPlayed = time[1];
            }
            into.Add(g);
            owned++;
        }
        note.Append("notinstalled=" + owned + " ");
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
                string t = ReadShared(f);
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

    // ---- PC apps: programs from the Start menu and the desktop, picked on the handheld ----------

    static string HashId(string text) {
        uint h = 2166136261;
        foreach (char c in text.ToLowerInvariant()) {
            h ^= c;
            h = unchecked(h * 16777619u);
        }
        return h.ToString("x8", CultureInfo.InvariantCulture);
    }

    static readonly Regex NotAProgram = new Regex(@"\b(uninstall|readme|read me|help|manual|website|license|documentation|release notes|changelog|support)\b|^about ",
        RegexOptions.IgnoreCase);

    public static List<Program> ScanPrograms() {
        List<Program> found = new List<Program>();
        Dictionary<string, bool> seen = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
        string[][] roots = new string[][] {
            new string[] { Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Start menu" },
            new string[] { Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Start menu" },
            new string[] { Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "Desktop" },
            new string[] { Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Desktop" }
        };
        foreach (string[] root in roots) {
            if (string.IsNullOrEmpty(root[0]) || !Directory.Exists(root[0])) { continue; }
            string[] files;
            try { files = Directory.GetFiles(root[0], "*.*", SearchOption.AllDirectories); } catch { continue; }
            foreach (string f in files) {
                string ext = Path.GetExtension(f).ToLowerInvariant();
                if (ext != ".lnk" && ext != ".url" && ext != ".exe" && ext != ".appref-ms") { continue; }
                string name = Path.GetFileNameWithoutExtension(f);
                if (NotAProgram.IsMatch(name) || seen.ContainsKey(name)) { continue; }
                seen[name] = true;
                Program p = new Program();
                p.Id = HashId(f);
                p.Name = name;
                p.Path = f;
                string folder = Path.GetFileName(Path.GetDirectoryName(f));
                p.Where = root[1] + (folder == "Programs" || folder == "Desktop" ? "" : " > " + folder);
                found.Add(p);
            }
        }
        found.Sort(delegate (Program a, Program b) { return string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase); });
        lock (scanLock) { lastPrograms = found; }
        return found;
    }

    static string AppsFile() {
        return Path.Combine(DataDir.Length > 0 ? DataDir : Path.GetTempPath(), "pc-apps.txt");
    }

    static List<Program> LoadApps() {
        List<Program> apps = new List<Program>();
        try {
            string file = AppsFile();
            if (!File.Exists(file)) { return apps; }
            foreach (string line in File.ReadAllLines(file, Encoding.UTF8)) {
                string[] p = line.Split('\t');
                if (p.Length < 3 || p[0].Length == 0) { continue; }
                Program a = new Program();
                a.Id = p[0];
                a.Name = p[1];
                a.Path = p[2];
                apps.Add(a);
            }
        } catch { }
        return apps;
    }

    static void SaveApps(List<Program> apps) {
        List<string> lines = new List<string>();
        foreach (Program a in apps) { lines.Add(a.Id + "\t" + Clean(a.Name) + "\t" + a.Path); }
        File.WriteAllLines(AppsFile(), lines.ToArray(), Encoding.UTF8);
    }

    public static string AddApp(string id) {
        Program pick = null;
        lock (scanLock) { foreach (Program p in lastPrograms) { if (p.Id == id) { pick = p; } } }
        if (pick == null) {
            foreach (Program p in ScanPrograms()) { if (p.Id == id) { pick = p; } }
        }
        if (pick == null) { return "ERR unknown"; }
        List<Program> apps = LoadApps();
        foreach (Program a in apps) { if (a.Id == id) { return "OK"; } }
        apps.Add(pick);
        try { SaveApps(apps); } catch (Exception e) { return "ERR " + Clean(e.Message); }
        Log("PC app added for the handheld: " + pick.Name);
        return "OK";
    }

    public static string RemoveApp(string id) {
        List<Program> apps = LoadApps();
        List<Program> keep = new List<Program>();
        foreach (Program a in apps) { if (a.Id != id) { keep.Add(a); } }
        if (keep.Count == apps.Count) { return "ERR unknown"; }
        try { SaveApps(keep); } catch (Exception e) { return "ERR " + Clean(e.Message); }
        return "OK";
    }

    static void ScanApps(List<Game> into) {
        foreach (Program a in LoadApps()) {
            if (!File.Exists(a.Path)) { continue; }
            Game g = new Game();
            g.Source = "pc";
            g.Id = a.Id;
            g.Name = a.Name;
            g.Exe = a.Path;
            g.Kind = "app";
            into.Add(g);
        }
    }

    // ---- All of it --------------------------------------------------------------------------

    public static List<Game> Scan() {
        List<Game> games = new List<Game>();
        StringBuilder note = new StringBuilder();
        try { ScanSteam(games, note); } catch (Exception e) { note.Append("steam-error=" + Clean(e.Message) + " "); }
        ScanEpic(games);
        ScanGog(games);
        ScanApps(games);
        note.Append("total=" + games.Count);
        LastNote = note.ToString();
        lock (scanLock) { last = games; }
        return games;
    }

    static Game Find(string source, string id) {
        lock (scanLock) {
            foreach (Game g in last) { if (g.Source == source && g.Id == id) { return g; } }
        }
        return null;
    }

    static void Open(string target) {
        ProcessStartInfo info = new ProcessStartInfo(target);
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
            } else if (g.Source == "epic" || g.Source == "nonsteam") {
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
        return SteamInstall(id, true);
    }

    public static string SteamInstall(string id, bool confirm) {
        if (!IsDigits(id)) { return "ERR id"; }
        if (SteamPath() == null) { return "ERR nosteam"; }
        try { Open("steam://install/" + id); } catch (Exception e) { return "ERR " + Clean(e.Message); }
        Log("Steam: install requested (app " + id + (confirm ? ")" : ", the drive is picked on the stream)"));
        Thread t = new Thread(confirm ? (ThreadStart)ConfirmInstallDialog : (ThreadStart)FrontInstallDialog);
        t.IsBackground = true;
        t.Start();
        return "OK";
    }

    // The drive is chosen on the handheld's stream: just bring Steam's window to the front
    static void FrontInstallDialog() {
        for (int i = 0; i < 40; i++) {
            Thread.Sleep(500);
            IntPtr w = FindSteamWindow("Install");
            if (w == IntPtr.Zero) { continue; }
            Thread.Sleep(600);
            keybd_event(0x12, 0, 0, UIntPtr.Zero);
            keybd_event(0x12, 0, 2, UIntPtr.Zero);
            ShowWindow(w, 9);
            SetForegroundWindow(w);
            Log("Steam: install window up, waiting for the drive to be picked");
            return;
        }
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

    /** Hooks this into the PC helper (found by name, so the two compile separately). */
    public static string Install() {
        try {
            Type companion = null;
            foreach (System.Reflection.Assembly a in AppDomain.CurrentDomain.GetAssemblies()) {
                Type t = a.GetType("PocketCompanion", false);
                if (t != null) { companion = t; }
            }
            if (companion == null) { return "the PC helper isn't loaded"; }
            System.Reflection.MethodInfo log = companion.GetMethod("Log", new Type[] { typeof(string) });
            if (log != null) { Logger = (Action<string>)Delegate.CreateDelegate(typeof(Action<string>), log); }
            System.Reflection.FieldInfo extra = companion.GetField("Extra");
            if (extra == null) { return "the PC helper is too old for the library"; }
            extra.SetValue(null, Delegate.CreateDelegate(extra.FieldType, typeof(PcGames).GetMethod("Handle")));
            return "";
        } catch (Exception e) {
            return e.Message;
        }
    }
}
'@
        } catch {
            $script:pcLibraryError = ("" + $_.Exception.Message) -replace "\s+", " "
            $script:pcLibraryErrors = @($Error | Select-Object -First 15 | ForEach-Object { "" + $_ })
            foreach ($er in $script:pcLibraryErrors) { Write-Log ("PC library: " + $er) }
        }
    }
    if ("PcGames" -as [type]) {
        [PcGames]::DataDir = $DataDir
        $err = [PcGames]::Install()
        if ($err) { $script:pcLibraryError = $err }
    } elseif (-not $script:pcLibraryError) {
        $script:pcLibraryError = "not compiled"
    }
    if ($script:pcLibraryError) { [PocketCompanion]::ExtraError = $script:pcLibraryError }
}

# ---- Servers ---------------------------------------------------------------------------------
function Start-Servers {
    $script:bridgeOk = $true
    try { [PocketBridge]::Start($TunnelPort) }
    catch { $script:bridgeOk = $false; Say "USB-C relay: port $TunnelPort is busy (another program uses it)" "Yellow" }

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
    $script:pin = ([BitConverter]::ToUInt32($pinBytes, 0) % 1000000).ToString().PadLeft(6, "0")

    $script:companionOk = $true
    try { [PocketCompanion]::Start($CompanionPort, $token, $script:pin) }
    catch { $script:companionOk = $false; Say "PC helper: port $CompanionPort is busy (another program uses it) - pick another Wi-Fi port from the icon next to the clock" "Yellow" }
}

function Write-Status {
    $lines = @(
        ("started=" + [DateTime]::UtcNow.Ticks),
        ("pin=" + $script:pin),
        ("companion=" + $(if ($script:companionOk) { "ok" } else { "busy" })),
        ("port=" + $CompanionPort),
        ("usb=" + $(if (-not $script:bridgeOk) { "port $TunnelPort busy" } elseif (-not $script:adb) { "ADB missing" } else { "ok" })),
        ("pclib=" + $(if ($script:pcLibraryError) { $script:pcLibraryError } else { "ok" }))
    )
    try { Set-Content -Path $StatusFile -Value $lines -Encoding UTF8 } catch { }
}

# ---- The cable ------------------------------------------------------------------------------
function Set-Reverse {
    & $script:adb reverse --remove-all 2>$null | Out-Null
    foreach ($p in $TcpPorts) { & $script:adb reverse "tcp:$p" "tcp:$p" 2>$null | Out-Null }
    & $script:adb reverse "tcp:$TunnelPort" "tcp:$TunnelPort" 2>$null | Out-Null
    # The handheld always asks 47110 over the cable: it lands on this PC's Wi-Fi port
    & $script:adb reverse "tcp:$DefaultCompanionPort" "tcp:$CompanionPort" 2>$null | Out-Null
}

function Reset-UsbMode {
    # Undo the RNDIS/NCM attempt from the tethering script, if it was run
    $cfg = (& $script:adb shell getprop sys.usb.config 2>$null) -join ""
    if ($cfg -match "rndis|ncm") {
        & $script:adb shell svc usb setFunctions 2>$null | Out-Null
        Start-Sleep -Seconds 3
        & $script:adb wait-for-device 2>$null | Out-Null
    }
}

# Every couple of seconds: the handheld on the cable, and what the helpers have to say
function Invoke-Tick {
    if ($script:adb) {
        $state = ((& $script:adb get-state 2>&1) -join "").Trim()
        if ($state -ne $script:lastState) {
            if ($state -eq "device") {
                Reset-UsbMode
                Set-Reverse
                $model = ((& $script:adb shell getprop ro.product.model 2>$null) -join "").Trim()
                Stamp "Connected: $model - open Pocket Nexus" "Green"
            } elseif ($state -match "unauthorized") {
                Stamp "Press 'Allow' on the handheld (tick 'Always allow from this computer')" "Yellow"
            } else {
                if ($script:lastState -eq "device") { Stamp "Handheld unplugged" "DarkGray" }
            }
            $script:lastState = $state
        } elseif ($state -eq "device") {
            # Keep the port rules alive (they vanish if adb restarts)
            $rules = ((& $script:adb reverse --list 2>$null) -join " ")
            if ($rules -notmatch "tcp:$DefaultCompanionPort") { Set-Reverse }
        }
    }
    $sessions = [PocketBridge]::Sessions
    if ($sessions -ne $script:lastSessions) {
        Write-Log "Pocket Nexus linked through the cable"
        $script:lastSessions = $sessions
    }
    if ($script:companionOk) {
        $evt = $null
        while ([PocketCompanion]::Events.TryDequeue([ref]$evt)) { Stamp $evt "Cyan" }
    }
}

# ---- Starting hidden, with Windows ------------------------------------------------------------
function Write-Launcher {
    # wscript starts PowerShell with no window at all (not even a flash)
    $cmd = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""' + $ScriptPath + '"" -Background'
    Set-Content -Path $Launcher -Value ('CreateObject("WScript.Shell").Run "' + $cmd + '", 0, False') -Encoding Unicode
}

function New-StartupLink {
    Write-Launcher
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut($StartupLink)
    $lnk.TargetPath = "$env:WINDIR\System32\wscript.exe"
    $lnk.Arguments = "`"$Launcher`""
    $lnk.WorkingDirectory = $here
    $lnk.Description = "Pocket Nexus PC (runs in the background)"
    $lnk.Save()
}

# ---- The icon next to the clock -------------------------------------------------------------
function New-TrayImage {
    $bmp = New-Object System.Drawing.Bitmap 32, 32
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $x = 1; $y = 8; $w = 30; $h = 17; $r = 7
    $path.AddArc($x, $y, $r * 2, $r * 2, 180, 90)
    $path.AddArc($x + $w - $r * 2, $y, $r * 2, $r * 2, 270, 90)
    $path.AddArc($x + $w - $r * 2, $y + $h - $r * 2, $r * 2, $r * 2, 0, 90)
    $path.AddArc($x, $y + $h - $r * 2, $r * 2, $r * 2, 90, 90)
    $path.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), $path)
    $g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 24, 24, 30))), 10, 11, 12, 11)
    $g.Dispose()
    return [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
}

function Get-StatusText {
    $lines = @()
    $lines += "Wi-Fi link PIN: " + $script:pin
    $lines += "PC helper: " + $(if ($script:companionOk) { "ready" } else { "port $CompanionPort busy" })
    $lines += "Wi-Fi port: " + $CompanionPort
    $lines += "PC library: " + $(if ($script:pcLibraryError) { "unavailable - " + $script:pcLibraryError } else { "ready" })
    $lines += "USB-C: " + $(if (-not $script:adb) { "ADB missing" } elseif ($script:lastState -eq "device") { "handheld connected" } else { "no handheld on the cable" })
    $apollo = Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue
    $lines += "Apollo: " + $(if ($apollo) { "running" } else { "not running" })
    return ($lines -join "`r`n")
}

# The firewall rule follows the port (only when there is one: it was allowed at setup)
function Update-FirewallPort([int]$port) {
    $rule = Get-NetFirewallRule -DisplayName "Pocket Nexus PC" -ErrorAction SilentlyContinue
    if (-not $rule) { return $false }
    $cmd = "Remove-NetFirewallRule -DisplayName 'Pocket Nexus PC' -ErrorAction SilentlyContinue; " +
        "New-NetFirewallRule -DisplayName 'Pocket Nexus PC' -Direction Inbound -Protocol TCP -LocalPort $port -Action Allow -Profile Private,Domain"
    try {
        Start-Process powershell.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList "-NoProfile -Command $cmd"
        return $true
    } catch {
        Write-Log ("Firewall rule not changed: " + $_.Exception.Message)
        return $false
    }
}

# Icon menu > Wi-Fi port: asks for another one, keeps it and starts again on it
function Edit-WifiPort {
    Add-Type -AssemblyName Microsoft.VisualBasic
    $text = "The port the handheld reaches Pocket Nexus PC on over Wi-Fi (1024 - 65535, usually 47110)." +
        "`r`n`r`nOn the handheld, use the same one: the PC's options > Pocket Nexus PC Wi-Fi port" +
        " (or stream once over the USB cable and it picks it up by itself)."
    $answer = [Microsoft.VisualBasic.Interaction]::InputBox($text, "Pocket Nexus PC - Wi-Fi port", "" + $CompanionPort)
    if (-not $answer) { return }
    $new = 0
    if (-not [int]::TryParse($answer.Trim(), [ref]$new) -or $new -lt 1024 -or $new -gt 65535) {
        [void][System.Windows.Forms.MessageBox]::Show("Pick a number between 1024 and 65535.", "Pocket Nexus PC")
        return
    }
    if ($new -eq $CompanionPort) { return }
    if (Get-NetTCPConnection -LocalPort $new -State Listen -ErrorAction SilentlyContinue) {
        [void][System.Windows.Forms.MessageBox]::Show("Port $new is already used by another program on this PC. Pick another one.", "Pocket Nexus PC")
        return
    }
    Set-Content -Path $PortFile -Value $new -NoNewline
    Write-Log "Wi-Fi port changed to $new"
    $fw = Update-FirewallPort $new
    $msg = "The Wi-Fi port is now $new" + $(if ($fw) { " (firewall rule updated)" } else { "" }) + "." +
        "`r`n`r`nOn the handheld: the PC's options > Pocket Nexus PC Wi-Fi port > $new." +
        "`r`n`r`nPocket Nexus PC starts again now."
    [void][System.Windows.Forms.MessageBox]::Show($msg, "Pocket Nexus PC")
    Write-Launcher
    Start-Process wscript.exe -ArgumentList "`"$Launcher`""
    Stop-Tray
}

function Stop-Tray {
    try { $script:timer.Stop() } catch { }
    if ($script:tray) { $script:tray.Visible = $false; $script:tray.Dispose() }
    [System.Windows.Forms.Application]::Exit()
}

function Start-Tray {
    $script:tray = New-Object System.Windows.Forms.NotifyIcon
    $script:tray.Icon = New-TrayImage
    $script:tray.Text = "Pocket Nexus PC"
    $menu = New-Object System.Windows.Forms.ContextMenuStrip
    $title = $menu.Items.Add("Pocket Nexus PC")
    $title.Enabled = $false
    $pinItem = $menu.Items.Add("Wi-Fi link PIN: " + $script:pin + "   (click to copy)")
    $pinItem.add_Click({ try { [System.Windows.Forms.Clipboard]::SetText($script:pin) } catch { } })
    $portItem = $menu.Items.Add("Wi-Fi port: " + $CompanionPort + "   (change)")
    $portItem.add_Click({ try { Edit-WifiPort } catch { Write-Log ("Wi-Fi port: " + $_.Exception.Message) } })
    [void]$menu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
    $statusItem = $menu.Items.Add("Status")
    $statusItem.add_Click({ [void][System.Windows.Forms.MessageBox]::Show((Get-StatusText), "Pocket Nexus PC") })
    $logItem = $menu.Items.Add("Open the log")
    $logItem.add_Click({ Start-Process notepad.exe -ArgumentList "`"$LogFile`"" })
    $startItem = New-Object System.Windows.Forms.ToolStripMenuItem "Start with Windows"
    $startItem.Checked = (Test-Path $StartupLink)
    $startItem.add_Click({
        if (Test-Path $StartupLink) {
            Remove-Item $StartupLink -Force -ErrorAction SilentlyContinue
            New-Item -ItemType File -Force -Path "$DataDir\no-startup" | Out-Null
        } else {
            Remove-Item "$DataDir\no-startup" -ErrorAction SilentlyContinue
            New-StartupLink
        }
        $this.Checked = (Test-Path $StartupLink)
    })
    [void]$menu.Items.Add($startItem)
    $restartItem = $menu.Items.Add("Restart")
    $restartItem.add_Click({ Write-Launcher; Start-Process wscript.exe -ArgumentList "`"$Launcher`""; Stop-Tray })
    $quitItem = $menu.Items.Add("Quit")
    $quitItem.add_Click({ Write-Log "Quit from the tray"; Stop-Tray })
    $script:tray.ContextMenuStrip = $menu
    $script:tray.add_DoubleClick({ [void][System.Windows.Forms.MessageBox]::Show((Get-StatusText), "Pocket Nexus PC") })
    $script:tray.Visible = $true

    $script:timer = New-Object System.Windows.Forms.Timer
    $script:timer.Interval = 2000
    $script:timer.add_Tick({ try { Invoke-Tick } catch { Write-Log ("Tick: " + $_.Exception.Message) } })
    $script:timer.Start()

    if (Test-Path "$DataDir\hello") {
        Remove-Item "$DataDir\hello" -ErrorAction SilentlyContinue
        $script:tray.ShowBalloonTip(6000, "Pocket Nexus PC", "Running in the background. Wi-Fi link PIN: " + $script:pin, [System.Windows.Forms.ToolTipIcon]::Info)
    }
    [System.Windows.Forms.Application]::Run()
}

# ---- Self-test (CI on a real Windows: everything compiles and answers) ------------------------
function Invoke-SelfTest {
    $failed = New-Object System.Collections.Generic.List[string]
    function Check($ok, $what) {
        if ($ok) { Write-Host ("  ok    " + $what) } else { Write-Host ("  FAIL  " + $what); $failed.Add($what) }
    }
    Check ($script:pcLibraryError -eq "") ("PC library compiled and hooked in " + $script:pcLibraryError)
    foreach ($er in $script:pcLibraryErrors) { Write-Host ("        " + $er) }

    # The protocol, over the loopback (trusted like the cable)
    [PocketCompanion]::Start($CompanionPort, ("ab" * 16), "123456")
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect("127.0.0.1", $CompanionPort)
    $stream = $client.GetStream()
    $stream.ReadTimeout = 20000
    $writer = New-Object System.IO.StreamWriter($stream)
    $writer.NewLine = "`n"
    $writer.AutoFlush = $true
    $reader = New-Object System.IO.StreamReader($stream)
    $writer.WriteLine("APC1 -")
    $hello = $reader.ReadLine()
    Check ($hello -like "OK 1 *") ("hello: " + $hello)
    $writer.WriteLine("PING"); Check ($reader.ReadLine() -eq "PONG") "PING"
    $writer.WriteLine("WIFIPORT"); $wp = $reader.ReadLine(); Check ($wp -eq ("WIFIPORT " + $CompanionPort)) ("WIFIPORT: " + $wp)
    $writer.WriteLine("VERSION"); $v = $reader.ReadLine(); Check ($v -like "VERSION * games") ("VERSION: " + $v)
    $writer.WriteLine("GAMES")
    $n = 0
    while ($true) { $line = $reader.ReadLine(); if ($line -eq $null -or $line.StartsWith("END") -or $line.StartsWith("ERR")) { break }; $n++ }
    Check ($line -like "END *") ("GAMES answered (" + $n + " games): " + $line)
    $writer.WriteLine("PROGRAMS")
    $n = 0
    while ($true) { $line = $reader.ReadLine(); if ($line -eq $null -or $line.StartsWith("END") -or $line.StartsWith("ERR")) { break }; $n++ }
    Check ($line -like "END *") ("PROGRAMS answered (" + $n + " programs): " + $line)
    $writer.WriteLine("ADDAPP 00000000"); Check ($reader.ReadLine() -eq "ERR unknown") "ADDAPP of something unknown"
    $writer.WriteLine("LAUNCH steam 1"); Check ($reader.ReadLine() -like "ERR *") "LAUNCH of something not found"
    $writer.WriteLine("STEAM INSTALL abc"); Check ($reader.ReadLine() -eq "ERR id") "STEAM INSTALL checks the id"
    $writer.WriteLine("STEAM INSTALL abc PICK"); Check ($reader.ReadLine() -eq "ERR id") "STEAM INSTALL PICK checks the id"
    $writer.WriteLine("PCLIB"); $pl = $reader.ReadLine(); Check ($pl -like "PCLIB*") ("PCLIB: " + $pl)
    $writer.WriteLine("APOLLOLOG")
    $n = 0
    while ($true) { $line = $reader.ReadLine(); if ($line -eq $null -or $line.StartsWith("END") -or $line.StartsWith("ERR")) { break }; $n++ }
    Check ($line -like "END *") ("APOLLOLOG answered (" + $n + " lines): " + $line)
    $writer.WriteLine("QUIT")
    $client.Close()

    # The Steam files, from a made-up Steam folder (tests\make_fixtures.py)
    $fx = Join-Path $here "tests\fixtures"
    if (Test-Path "$fx\steam") {
        [PcGames]::SteamRootOverride = "$fx\steam"
        $games = [PcGames]::Scan()
        Write-Host ("  scan: " + [PcGames]::LastNote)
        $by = @{}
        foreach ($g in $games) { $by[$g.Source + ":" + $g.Id] = $g }
        $a = $by["steam:100"]
        Check ($a -ne $null -and $a.State -eq "installed" -and $a.Name -eq "Test Installed Game" -and $a.Playtime -eq 125 -and $a.SizeMb -eq 2048) "installed game, its size and playtime"
        $b = $by["steam:101"]
        Check ($b -ne $null -and $b.State -eq "installing" -and $b.Progress -eq 40) "a game installing in a second library folder (40%)"
        Check (-not $by.ContainsKey("steam:228980")) "redistributables left out"
        $c = $by["steam:200"]
        Check ($c -ne $null -and $c.State -eq "notinstalled" -and $c.Name -eq "Owned Not Installed" -and $c.Kind -eq "game") "owned, not installed (library art)"
        $d = $by["steam:400"]
        Check ($d -ne $null -and $d.State -eq "notinstalled" -and $d.Kind -eq "tool" -and $d.Playtime -eq 30) "a tool played before (localconfig)"
        Check (-not $by.ContainsKey("steam:300")) "DLC left out"
        Check (-not $by.ContainsKey("steam:500")) "unknown apps left out"
        $e = $by["nonsteam:3000000001"]
        Check ($e -ne $null -and $e.Name -eq "My Emulator" -and $e.Uri -eq "steam://rungameid/12884901892328521728") ("non-Steam game and its launch link " + $(if ($e) { $e.Uri } else { "" }))
        Check (-not $by.ContainsKey("nonsteam:5")) "hidden non-Steam game left out"
        $info = [PcGames]::ReadAppInfo([IO.File]::ReadAllBytes("$fx\appinfo_v28.vdf"), $null)
        Check ($info.Count -eq 5 -and $info[[uint32]400][0] -eq "Some SDK" -and $info[[uint32]400][1] -eq "tool") "appinfo.vdf v28"
        $info27 = [PcGames]::ReadAppInfo([IO.File]::ReadAllBytes("$fx\appinfo_v27.vdf"), $null)
        Check ($info27.Count -eq 5 -and $info27[[uint32]200][0] -eq "Owned Not Installed") "appinfo.vdf v27"
        [PcGames]::SteamRootOverride = $null
    } else {
        Check $false "test fixtures missing (run tests\make_fixtures.py)"
    }

    # PC apps: add one from the Start menu list and find it in GAMES
    [PcGames]::DataDir = (Join-Path $env:TEMP ("pn-test-" + [Guid]::NewGuid().ToString("N")))
    New-Item -ItemType Directory -Force -Path ([PcGames]::DataDir) | Out-Null
    $programs = [PcGames]::ScanPrograms()
    if ($programs.Count -gt 0) {
        $first = $programs[0]
        Check ([PcGames]::AddApp($first.Id) -eq "OK") ("ADDAPP " + $first.Name)
        $found = $false
        foreach ($g in [PcGames]::Scan()) { if ($g.Source -eq "pc" -and $g.Id -eq $first.Id) { $found = $true } }
        Check $found "the PC app shows in GAMES"
        Check ([PcGames]::RemoveApp($first.Id) -eq "OK") "REMOVEAPP"
    } else {
        Write-Host "  (no Start menu programs on this machine to test PC apps with)"
    }

    if ($failed.Count -gt 0) { Write-Host ("SELFTEST FAILED: " + $failed.Count); exit 1 }
    Write-Host "SELFTEST PASSED"
    exit 0
}

# ==== Setup (double-clicked): close older helpers, set things up, start it in the background ====
if (-not $Background -and -not $Console -and -not $SelfTest) {
    Write-Host ""
    Write-Host "  POCKET NEXUS PC" -ForegroundColor White
    Write-Host ""
    $closed = Stop-OtherHelpers $false
    if ($closed -gt 0) { Say "Closed the helper that was already running (it's started again below)" "DarkGray" }
    $script:adb = Get-Adb
    if ($script:adb) { Say "ADB: $script:adb" "DarkGray" }

    # Wi-Fi control needs the port open in Windows Firewall (asked once, needs admin once)
    $fwFlag = "$DataDir\firewall-asked"
    if (-not (Test-Path $fwFlag)) {
        New-Item -ItemType File -Force -Path $fwFlag | Out-Null
        $rule = Get-NetFirewallRule -DisplayName "Pocket Nexus PC" -ErrorAction SilentlyContinue
        if (-not $rule) { $rule = Get-NetFirewallRule -DisplayName "Artemis Pocket PC" -ErrorAction SilentlyContinue }
        if (-not $rule) {
            $answer = Read-Host "  Let the handheld use the PC helper and PC library over Wi-Fi too (adds a firewall rule, asks for admin)? (Y/N)"
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

    # A new Wi-Fi port given here (-WifiPort): the firewall rule follows it
    if ($WifiPort -ne 0) {
        if (Update-FirewallPort $CompanionPort) { Say "Firewall rule moved to port $CompanionPort" "Green" }
    }

    # Starts with Windows, hidden (the old Artemis Pocket shortcuts are replaced)
    $startupDir = [Environment]::GetFolderPath("Startup")
    foreach ($oldLink in @("$startupDir\Artemis Pocket USB bridge.lnk", "$startupDir\Artemis Pocket PC.lnk")) {
        if (Test-Path $oldLink) { Remove-Item $oldLink -ErrorAction SilentlyContinue }
    }
    if (Test-Path "$DataDir\no-startup") {
        Write-Launcher
        Say "Not started with Windows (turn it on from its icon: Start with Windows)" "DarkGray"
    } else {
        New-StartupLink
        Say "Starts with Windows, hidden" "Green"
    }

    # Start it in the background and wait for it to report
    Remove-Item $StatusFile -ErrorAction SilentlyContinue
    New-Item -ItemType File -Force -Path "$DataDir\hello" | Out-Null
    Start-Process wscript.exe -ArgumentList "`"$Launcher`""
    Say "Starting it in the background..." "DarkGray"
    $status = $null
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        if (Test-Path $StatusFile) {
            $status = Get-Content $StatusFile -ErrorAction SilentlyContinue
            if ($status) { break }
        }
    }
    Write-Host ""
    if (-not $status) {
        Say "It didn't report back. Run 'PocketNexus-PC.bat -Console' to see what goes wrong." "Red"
    } else {
        $map = @{}
        foreach ($l in $status) { $kv = $l -split "=", 2; if ($kv.Count -eq 2) { $map[$kv[0]] = $kv[1] } }
        Say ("Wi-Fi link PIN: " + $map["pin"]) "Green"
        Say ("Wi-Fi port: " + $map["port"] + "   (to change it: the icon next to the clock > Wi-Fi port)") "Green"
        if ($map["companion"] -eq "ok") { Say "PC helper ready (power, status, microphone)" "Green" }
        else { Say "PC helper: port $CompanionPort is busy - another program uses it. Pick another one: the icon next to the clock > Wi-Fi port, or PocketNexus-PC.bat -WifiPort 47120" "Red" }
        if ($map["pclib"] -eq "ok") { Say "PC library ready (Steam, non-Steam, Epic, GOG, PC apps)" "Green" }
        else { Say ("PC library unavailable: " + $map["pclib"]) "Yellow" }
        if ($map["usb"] -ne "ok") { Say ("USB-C: " + $map["usb"]) "Yellow" }
    }
    if (Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue) { Say "Apollo is running" "Green" }
    else { Say "Apollo isn't running yet - start it before streaming" "Yellow" }
    Write-Host ""
    Say "Pocket Nexus PC now runs in the background: its icon sits next to the clock" "White"
    Say "(right-click it for the PIN, the status, Start with Windows and Quit). You can close this window." "White"
    Write-Host ""
    Read-Host "  Press Enter to close" | Out-Null
    exit 0
}

# ==== The helper itself (hidden in the background, or in this window with -Console) ============
if (-not $SelfTest) {
    [void](Stop-OtherHelpers $true)
    if ($Console) {
        Write-Host ""
        Write-Host "  POCKET NEXUS PC" -ForegroundColor White
        Write-Host ""
    }
}
Write-Log ("Starting (" + $(if ($SelfTest) { "self-test" } elseif ($Console) { "console" } else { "background" }) + ")")
try {
    Load-Types
} catch {
    Say ("Couldn't start: " + $_.Exception.Message) "Red"
    if ($SelfTest) { Write-Host "SELFTEST FAILED: compile"; exit 1 }
    if ($Console) { Read-Host "  Press Enter to close" | Out-Null }
    exit 1
}
if ($SelfTest) { Invoke-SelfTest }

$script:adb = Get-Adb
Start-Servers
Write-Status
if ($script:companionOk) {
    Say ("PC helper ready  -  Wi-Fi link PIN: " + $script:pin) "Green"
    if ($script:pcLibraryError) { Say ("PC library unavailable: " + $script:pcLibraryError) "Yellow" }
    else { Say "PC library ready" "Green" }
    $cableId = 0
    $cable = [MicPlayer]::FindCable([ref]$cableId)
    if ($cable) { Say "Microphone: VB-Cable found ($cable)" "Green" }
    else { Say "Microphone: install VB-Audio Virtual Cable (free) from vb-audio.com/Cable to use the handheld's mic" "DarkGray" }
}

if ($Console) {
    Write-Host ""
    Say "Plug the Retroid in (USB debugging on), or play over Wi-Fi. Leave this window open." "White"
    Write-Host ""
    while ($true) {
        try { Invoke-Tick } catch { Write-Log ("Tick: " + $_.Exception.Message) }
        Start-Sleep -Seconds 2
    }
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Start-Tray
Write-Log "Stopped"
