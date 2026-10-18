param(
    [switch]$List,
    [switch]$SelfTest,
    [switch]$Lan,
    [string]$HostAddress,
    [ValidateRange(1024, 65535)][int]$Port = 46237,
    [string]$CertificatePin,
    [string]$Invitation,
    [string]$PeerAddress,
    [ValidateRange(1, 50)][int]$Count = 10
)
$ErrorActionPreference = 'Stop'
if ($List) {
    $bluetoothDevices = @(Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue)
    if ($bluetoothDevices.Count -eq 0) {
        Write-Warning 'Windows did not detect any Bluetooth devices. Check the adapter, driver, and Bluetooth settings; Wi-Fi adapters do not necessarily include Bluetooth.'
        return
    }
    $peers = @($bluetoothDevices | ForEach-Object {
        if ($_.InstanceId -match '^BTHENUM\\DEV_([0-9A-Fa-f]{12})\\') {
            [PSCustomObject]@{ Name = $_.FriendlyName; Address = $Matches[1]; Status = $_.Status }
        }
    })
    if ($peers.Count -eq 0) {
        Write-Warning 'No paired classic Bluetooth device addresses were found. Pair the phone in Windows Bluetooth settings first.'
    }
    $peers
    return
}
if (-not ('FloatClip.NearbyProbe.Probe' -as [type])) {
    Add-Type -Path @((Join-Path $PSScriptRoot 'FloatClip.NearbyProbe\Probe.cs'),
        (Join-Path $PSScriptRoot 'FloatClip.NearbyProbe\LanProbe.cs'))
}
if ($SelfTest) { [FloatClip.NearbyProbe.Probe]::SelfTest($null); return }
if ($Lan) { [FloatClip.NearbyProbe.LanProbe]::Run($HostAddress, $Port, $CertificatePin, $Invitation); return }
if (-not $PeerAddress) { throw 'Use -SelfTest, -Lan -HostAddress IP -CertificatePin SHA256 -Invitation TOKEN, or -List/-PeerAddress' }
$code = [FloatClip.NearbyProbe.Probe]::Run($PeerAddress, $Count)
if ($code -ne 0) { throw "Probe failed: $code" }
