# Artemis Pocket - USB-C streaming setup for the PC (run once)
#
# What it does:
#   1. Opens the Apollo / Sunshine ports in Windows Firewall for every network type,
#      so the USB cable network (which Windows calls "Unidentified network") is allowed.
#   2. When the handheld is plugged in with USB tethering on, marks that network as Private
#      and gives it a very high route metric so Windows never uses the handheld for internet.
#
# Run it by double-clicking ArtemisPocket-USB-Setup.bat (it asks for administrator rights).

$ErrorActionPreference = "Continue"

function Write-Step($text)  { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok($text)    { Write-Host "    [OK] $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "    [!]  $text" -ForegroundColor Yellow }

# --- Elevate ---------------------------------------------------------------------------
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Asking for administrator rights..."
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

Write-Host "Artemis Pocket - USB-C streaming setup" -ForegroundColor White

# --- 1. Firewall -----------------------------------------------------------------------
Write-Step "Opening streaming ports in Windows Firewall (all network types)"
$ruleTcp = "Artemis Pocket USB streaming (TCP)"
$ruleUdp = "Artemis Pocket USB streaming (UDP)"
Get-NetFirewallRule -DisplayName $ruleTcp -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName $ruleUdp -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleTcp -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort 47984,47989,47990,48010 -Profile Any | Out-Null
New-NetFirewallRule -DisplayName $ruleUdp -Direction Inbound -Action Allow -Protocol UDP `
    -LocalPort 47998,47999,48000,48002,48010 -Profile Any | Out-Null
Write-Ok "Ports 47984-48010 are open"

# --- 2. Host software ------------------------------------------------------------------
Write-Step "Checking Apollo / Sunshine"
$svc = Get-Service -ErrorAction SilentlyContinue | Where-Object { $_.Name -match "Apollo|Sunshine" -or $_.DisplayName -match "Apollo|Sunshine" }
if ($svc) {
    foreach ($s in $svc) {
        if ($s.Status -ne "Running") {
            try { Start-Service $s.Name; Write-Ok "$($s.DisplayName) started" } catch { Write-Warn2 "Could not start $($s.DisplayName)" }
        } else { Write-Ok "$($s.DisplayName) is running" }
    }
} else {
    Write-Warn2 "Apollo/Sunshine service not found. Install Apollo: https://github.com/ClassicOldSong/Apollo/releases"
}
$listening = Get-NetTCPConnection -LocalPort 47989 -State Listen -ErrorAction SilentlyContinue
if ($listening) { Write-Ok "Streaming server is listening on port 47989" } else { Write-Warn2 "Nothing is listening on port 47989 yet" }

# --- 3. USB network ---------------------------------------------------------------------
Write-Step "Looking for the handheld's USB network"
Write-Host "    Plug the Retroid into this PC with USB tethering on (Developer options -> Default USB configuration -> USB tethering)."

function Find-UsbAdapters {
    Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object {
        $_.Status -eq "Up" -and ($_.InterfaceDescription -match "Remote NDIS|RNDIS|NCM|UsbNcm|Android" -or $_.Name -match "RNDIS|NCM")
    }
}

$adapters = Find-UsbAdapters
$waited = 0
while (-not $adapters -and $waited -lt 90) {
    Start-Sleep -Seconds 3
    $waited += 3
    Write-Host "    ...waiting for the cable ($waited s)" -NoNewline
    Write-Host "`r" -NoNewline
    $adapters = Find-UsbAdapters
}
Write-Host ""

if (-not $adapters) {
    Write-Warn2 "No USB tethering network found. The firewall part is done; run this again with the handheld plugged in to finish."
} else {
    foreach ($a in $adapters) {
        Write-Ok "Found: $($a.Name) ($($a.InterfaceDescription))"
        try {
            Set-NetIPInterface -InterfaceIndex $a.ifIndex -InterfaceMetric 9000 -ErrorAction Stop
            Write-Ok "Windows will not use the handheld for internet (metric 9000)"
        } catch { Write-Warn2 "Could not change the route metric: $($_.Exception.Message)" }
        try {
            Set-NetConnectionProfile -InterfaceIndex $a.ifIndex -NetworkCategory Private -ErrorAction Stop
            Write-Ok "Network set to Private"
        } catch { Write-Warn2 "Network stays Public (that's fine, the firewall rules cover it)" }
        $ip = (Get-NetIPAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
        if ($ip) { Write-Ok "This PC's address on the cable: $ip" }
    }
}

Write-Step "Done"
Write-Host "    Open Artemis Pocket on the Retroid and plug the cable in - it finds this PC on its own."
Write-Host "    First time only: pair with the PIN in Apollo's web page (https://localhost:47990)."
Write-Host ""
Read-Host "Press Enter to close"
