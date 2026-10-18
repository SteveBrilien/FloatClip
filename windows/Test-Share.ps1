param([Parameter(Mandatory=$true)][string]$AdbPath,[string]$Serial='192.168.144.222:5555',[string]$PhoneAddress='192.168.144.222',[Parameter(Mandatory=$true)][string]$ExePath,[Parameter(Mandatory=$true)][string]$OutputPath)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^[A-Za-z0-9_.:-]+$' -or $PhoneAddress -notmatch '^[0-9.]+$') { throw 'Invalid ADB target' }
$child=$null; $client=$null
try {
    $info=New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName=$AdbPath; $info.Arguments='-s '+$Serial+' shell am instrument -w -e address '+$PhoneAddress+' com.floatclip.probe/.ShareInstrumentation'
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $child=New-Object System.Diagnostics.Process; $child.StartInfo=$info; [void]$child.Start()
    $errors=$child.StandardError.ReadToEndAsync(); $ready=New-Object Text.StringBuilder
    $lineTask=$child.StandardOutput.ReadLineAsync(); $clock=[Diagnostics.Stopwatch]::StartNew(); $isReady=$false
    while($clock.Elapsed.TotalSeconds -lt 25) {
        if(-not $lineTask.Wait(200)) { continue }
        $line=$lineTask.Result; if($null -eq $line) { break }
        [void]$ready.AppendLine($line)
        if($line -eq 'INSTRUMENTATION_STATUS_CODE: 10') { $isReady=$true; break }
        $lineTask=$child.StandardOutput.ReadLineAsync()
    }
    if(-not $isReady) { throw 'Android readiness failed' }
    $invite=[regex]::Match($ready.ToString(),'INSTRUMENTATION_STATUS: invitation=(floatclip://[^\r\n]+)').Groups[1].Value
    if(-not $invite) { throw 'Missing invitation' }
    [void]$ready.Clear()
    $clientInfo=New-Object Diagnostics.ProcessStartInfo
    $clientInfo.FileName=$ExePath; $clientInfo.Arguments='--qa '+$invite+' "'+$OutputPath+'"'; $clientInfo.UseShellExecute=$false
    $client=New-Object Diagnostics.Process; $client.StartInfo=$clientInfo; [void]$client.Start(); $invite=$null
    if(-not $client.WaitForExit(60000)) { throw 'Windows QA timed out' }
    $tail=$child.StandardOutput.ReadToEndAsync(); if(-not $tail.Wait(15000)) { throw 'Android QA timed out' }
    $phone=$tail.Result
    if($client.ExitCode -ne 0 -or $phone -notmatch 'INSTRUMENTATION_RESULT: result=PASS') { throw ('Share integration failed; client='+$client.ExitCode+'; phone='+$phone) }
    $report=Get-Content -Raw -Encoding UTF8 -LiteralPath $OutputPath | ConvertFrom-Json
    if($report.result -ne 'PASS') { throw 'Windows report failed' }
    Write-Output 'PASS: encrypted two-way exact text; two-way 1048593-byte generated files; receiver rejection'
    Write-Output ('FILE_SHA256='+$report.sha256)
} finally {
    $invite=$null
    & $AdbPath -s $Serial shell am force-stop com.floatclip.probe 2>$null
    foreach($p in @($client,$child)) { if($null -ne $p) { if(-not $p.HasExited) { $p.Kill() }; $p.Dispose() } }
}
