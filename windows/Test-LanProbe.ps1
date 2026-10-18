param(
    [Parameter(Mandatory=$true)][string]$AdbPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_.:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9]{1,3}(\.[0-9]{1,3}){3}$')][string]$PhoneAddress,
    [ValidateRange(1024,65535)][int]$Port = 46237,
    [string]$ExePath = (Join-Path $PSScriptRoot 'FloatClip.NearbyProbe.exe'),
    [string]$OutputPath = (Join-Path $PSScriptRoot 'lan-acceptance.json')
)
$ErrorActionPreference = 'Stop'
$reports = @()
foreach ($caseName in @('valid','wrong-pin','wrong-invitation','drop-handshake','valid-retry')) {
    $child = $null
    try {
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $AdbPath
        $info.Arguments = '-s '+$Serial+' shell am instrument -w -e address '+$PhoneAddress+' -e port '+$Port+' com.floatclip.probe/.LanProbeInstrumentation'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $child = New-Object System.Diagnostics.Process
        $child.StartInfo = $info
        if (-not $child.Start()) { throw 'Could not start ADB instrumentation' }
        $errorTask = $child.StandardError.ReadToEndAsync()
        $ready = New-Object System.Text.StringBuilder
        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        $lineTask = $child.StandardOutput.ReadLineAsync()
        $isReady = $false
        while ($clock.Elapsed.TotalSeconds -lt 25) {
            if (-not $lineTask.Wait(200)) { continue }
            $line = $lineTask.Result
            if ($null -eq $line) { break }
            [void]$ready.AppendLine($line)
            if ($line -eq 'INSTRUMENTATION_STATUS_CODE: 10') { $isReady = $true; break }
            $lineTask = $child.StandardOutput.ReadLineAsync()
        }
        if (-not $isReady) { throw ('Phone LAN readiness failed: '+$caseName) }
        $pin = [regex]::Match($ready.ToString(),'INSTRUMENTATION_STATUS: pin=([0-9a-f]{64})').Groups[1].Value
        $invite = [regex]::Match($ready.ToString(),'INSTRUMENTATION_STATUS: invitation=([0-9a-f]{64})').Groups[1].Value
        if ($pin.Length -ne 64 -or $invite.Length -ne 64) { throw 'Missing readiness credentials' }
        if ($caseName -eq 'wrong-pin') {
            $first = if ($pin[0] -eq '0') { '1' } else { '0' }
            $pin = $first + $pin.Substring(1)
        }
        if ($caseName -eq 'wrong-invitation') {
            $first = if ($invite[0] -eq '0') { '1' } else { '0' }
            $invite = $first + $invite.Substring(1)
        }
        $clientOutput = ''
        $clientCode = -1
        if ($caseName -eq 'drop-handshake') {
            $socket = New-Object System.Net.Sockets.TcpClient
            try { $socket.Connect($PhoneAddress,$Port) } finally { $socket.Close() }
        } else {
            $ErrorActionPreference = 'Continue'
            try {
                $clientOutput = (& $ExePath --lan-connect $PhoneAddress $Port $pin $invite 2>&1) | Out-String
                $clientCode = $LASTEXITCODE
            } finally { $ErrorActionPreference = 'Stop' }
        }
        $invite = $null
        [void]$ready.Clear()
        $tail = $child.StandardOutput.ReadToEndAsync()
        if (-not $tail.Wait(15000)) { throw ('Phone did not finish: '+$caseName) }
        [void]$child.WaitForExit(5000)
        $phoneOutput = $tail.Result
        $expectedPass = $caseName -eq 'valid' -or $caseName -eq 'valid-retry'
        $phonePass = $phoneOutput -match 'INSTRUMENTATION_RESULT: result=PASS'
        $phoneFail = $phoneOutput -match 'INSTRUMENTATION_RESULT: result=FAIL'
        if ($expectedPass) {
            if ($clientCode -ne 0 -or $clientOutput -notmatch 'PASS LAN:' -or -not $phonePass) {
                $detail = [regex]::Replace(($clientOutput + $phoneOutput),'[0-9a-fA-F]{64}','<redacted>')
                throw ('LAN roundtrip failed: '+$caseName+'; client='+$clientCode+'; phonePass='+$phonePass+'; details='+$detail)
            }
        } elseif (-not $phoneFail -or $clientOutput -match 'PASS LAN:' -or $clientCode -eq 0) {
            throw ('Negative gate did not reject: '+$caseName)
        }
        $rtts = @([regex]::Matches($clientOutput,'RTT ([0-9.]+) ms') | ForEach-Object {
            [double]::Parse($_.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture)
        })
        if ($expectedPass -and $rtts.Count -ne 10) { throw 'Expected ten confirmed outgoing probes' }
        $events = @([regex]::Matches($phoneOutput,'INSTRUMENTATION_STATUS: message=([^\r\n]+)') | ForEach-Object {$_.Groups[1].Value})
        $reports += [pscustomobject]@{case=$caseName;result='PASS';clientExit=$clientCode;phonePassed=$phonePass;outgoingRttMs=$rtts;phoneEvents=$events}
        Write-Output ('CASE '+$caseName+' PASS; client='+$clientCode+'; phonePass='+$phonePass)
    } finally {
        $invite = $null
        & $AdbPath -s $Serial shell am force-stop com.floatclip.probe 2>$null
        if ($null -ne $child) {
            if (-not $child.HasExited) { $child.Kill() }
            $child.Dispose()
        }
    }
}
[pscustomobject]@{utc=[DateTime]::UtcNow.ToString('O');phoneAddress=$PhoneAddress;port=$Port;tests=$reports} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Output ('LAN_ACCEPTANCE_PASS; report='+$OutputPath)
