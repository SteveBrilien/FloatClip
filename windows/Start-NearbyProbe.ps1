param(
    [switch]$List,
    [switch]$SelfTest,
    [string]$PeerAddress,
    [ValidateRange(1, 50)][int]$Count = 10
)
$ErrorActionPreference = 'Stop'
if ($List) {
    Get-PnpDevice -Class Bluetooth | ForEach-Object {
        if ($_.InstanceId -match '^BTHENUM\\DEV_([0-9A-Fa-f]{12})\\') {
            [PSCustomObject]@{ Name = $_.FriendlyName; Address = $Matches[1]; Status = $_.Status }
        }
    }
    return
}
if (-not ('FloatClip.NearbyProbe.Probe' -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot 'FloatClip.NearbyProbe\Probe.cs')
}
if ($SelfTest) { [FloatClip.NearbyProbe.Probe]::SelfTest($null); return }
if (-not $PeerAddress) { throw 'Use -List, -SelfTest, or -PeerAddress AA:BB:CC:DD:EE:FF' }
$code = [FloatClip.NearbyProbe.Probe]::Run($PeerAddress, $Count)
if ($code -ne 0) { throw "Probe failed: $code" }
