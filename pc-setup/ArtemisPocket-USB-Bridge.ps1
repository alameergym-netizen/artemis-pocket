# Artemis Pocket - USB bridge for the PC
#
# Streams to the handheld over the USB-C cable on ROMs where Android's USB tethering is turned
# off (Retroid Pocket). It needs USB debugging on the handheld and nothing else: no Wi-Fi,
# no internet, no admin rights.
#
# How it works
#   adb reverse   handheld 127.0.0.1:47984/47989/48010 -> Apollo on this PC   (TCP)
#   adb reverse   handheld 127.0.0.1:47100             -> this bridge          (tunnel)
#   the bridge    unwraps the stream's UDP packets (video, control, audio) from the tunnel,
#                 sends them to Apollo on 127.0.0.1 and wraps Apollo's replies back.
#
# Leave this window open (it can be minimised) while you play.

param([switch]$Autostart)

$ErrorActionPreference = "Continue"
$Host.UI.RawUI.WindowTitle = "Artemis Pocket USB bridge"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$TunnelPort = 47100
$TcpPorts = @(47984, 47989, 48010, 47990)

function Say($text, $color = "Gray") { Write-Host ("  " + $text) -ForegroundColor $color }
function Stamp($text, $color = "Gray") { Write-Host ("  [" + (Get-Date -Format "HH:mm:ss") + "] " + $text) -ForegroundColor $color }

Write-Host ""
Write-Host "  ARTEMIS POCKET  -  USB bridge" -ForegroundColor White
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
    Say "Could not open port $TunnelPort - is the bridge already running in another window?" "Red"
    Read-Host "  Press Enter to close"
    exit 1
}

# ---- Apollo check ---------------------------------------------------------------------------
if (Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue) {
    Say "Apollo/Sunshine is running" "Green"
} else {
    Say "Apollo/Sunshine is not running yet - start it before streaming" "Yellow"
}

# ---- Start with Windows (asked once) --------------------------------------------------------
$flag = "$env:LOCALAPPDATA\ArtemisPocket\autostart-asked"
if (-not $Autostart -and -not (Test-Path $flag)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $flag) | Out-Null
    New-Item -ItemType File -Force -Path $flag | Out-Null
    $answer = Read-Host "  Start the USB bridge automatically when Windows starts? (Y/N)"
    if ($answer -match "^[Yy]") {
        $startup = [Environment]::GetFolderPath("Startup")
        $shell = New-Object -ComObject WScript.Shell
        $lnk = $shell.CreateShortcut("$startup\Artemis Pocket USB bridge.lnk")
        $lnk.TargetPath = "powershell.exe"
        $lnk.Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Minimized -File `"$($MyInvocation.MyCommand.Path)`" -Autostart"
        $lnk.WindowStyle = 7
        $lnk.Save()
        Say "Added to Windows startup (delete it from shell:startup to undo)" "Green"
    }
}

# ---- Device loop ----------------------------------------------------------------------------
function Set-Reverse {
    & $adb reverse --remove-all 2>$null | Out-Null
    foreach ($p in $TcpPorts) { & $adb reverse "tcp:$p" "tcp:$p" 2>$null | Out-Null }
    & $adb reverse "tcp:$TunnelPort" "tcp:$TunnelPort" 2>$null | Out-Null
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
Say "Plug the Retroid in (USB debugging on). Leave this window open while you play." "White"
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
            Stamp "Connected: $model - open Artemis Pocket" "Green"
        } elseif ($state -match "unauthorized") {
            Stamp "Press 'Allow' on the handheld (tick 'Always allow from this computer')" "Yellow"
        } else {
            if ($lastState -eq "device") { Stamp "Handheld unplugged - waiting..." "DarkGray" }
            else { Stamp "Waiting for the handheld..." "DarkGray" }
        }
        $lastState = $state
    } elseif ($state -eq "device") {
        # Keep the port rules alive (they vanish if adb restarts)
        $rules = ((& $adb reverse --list 2>$null) -join " ")
        if ($rules -notmatch "tcp:$TunnelPort") { Set-Reverse }
    }

    $sessions = [PocketBridge]::Sessions
    if ($sessions -ne $lastSessions) {
        Stamp "Artemis Pocket linked through the cable" "Cyan"
        $lastSessions = $sessions
    }
    Start-Sleep -Seconds 2
}
