param([Parameter(Mandatory=$true)][string]$Adb,[Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$Exe,[Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$phone=$Serial.Split(':')[0]
$model=(& $Adb -s $Serial shell getprop ro.product.model).Trim()
$product=(& $Adb -s $Serial shell getprop ro.product.name).Trim()
if($model -ne 'V2115A' -or $product -ne 'PD2115'){throw 'Device identity mismatch'}
$rows=@()
try {
 foreach($case in @(@{Name='first';Mode='fresh';Known=$false;Fail=$false;Store='trust'},@{Name='reconnect';Mode='remember';Known=$true;Fail=$false;Store='trust'},@{Name='revoked';Mode='fresh';Known=$false;Fail=$true;Store='trust'},@{Name='retry';Mode='fresh';Known=$false;Fail=$false;Store='trust-retry'})) {
  & $Adb -s $Serial shell am force-stop com.floatclip.probe | Out-Null
  $start=New-Object Diagnostics.ProcessStartInfo
  $start.FileName=$Adb; $start.Arguments="-s $Serial shell am instrument -w -e address $phone -e pairing $($case.Mode) com.floatclip.probe/.ShareInstrumentation"
  $start.UseShellExecute=$false; $start.CreateNoWindow=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
  $instrument=New-Object Diagnostics.Process; $instrument.StartInfo=$start; [void]$instrument.Start()
  $prefix=New-Object Text.StringBuilder; $line=$instrument.StandardOutput.ReadLineAsync(); $ready=$false; $until=[DateTime]::UtcNow.AddSeconds(20)
  while([DateTime]::UtcNow -lt $until -and !$instrument.HasExited) {
   if($line.IsCompleted){$value=$line.Result; if($null -eq $value){break}; [void]$prefix.AppendLine($value); if($value -eq "INSTRUMENTATION_STATUS: ready=$phone"){$ready=$true;break}; $line=$instrument.StandardOutput.ReadLineAsync()}
   Start-Sleep -Milliseconds 100
  }
  if(!$ready){throw "Phone QA not ready: $($prefix.ToString())"}
  $tail=$instrument.StandardOutput.ReadToEndAsync(); $errors=$instrument.StandardError.ReadToEndAsync()
  $report=Join-Path $OutputDirectory ($case.Name+'.json'); if(Test-Path $report){Remove-Item -LiteralPath $report -Force}; $store=Join-Path $OutputDirectory $case.Store
  $pc=Start-Process -FilePath $Exe -ArgumentList @('--qa-nearby',$phone,$report,$store) -PassThru
  if(!$pc.WaitForExit(75000)){ $pc.Kill(); throw 'Windows QA timeout' }
  if(!(Test-Path $report)){throw 'Windows QA report missing'}
  $result=Get-Content -Raw -Encoding UTF8 $report | ConvertFrom-Json
  if($case.Fail){
   if($result.result -ne 'FAIL' -or $result.error -notmatch '\u6388\u6743\u5df2\u5931\u6548'){throw 'Revoked credential was not rejected'}
   & $Adb -s $Serial shell am force-stop com.floatclip.probe | Out-Null
  } else {
   if(!$instrument.WaitForExit(15000)){throw ('Phone QA completion timeout; Windows report: '+(Get-Content -Raw -Encoding UTF8 $report))}
   $android=$prefix.ToString()+$tail.Result
   if($result.result -ne 'PASS' -or $android -notmatch 'INSTRUMENTATION_RESULT: result=PASS'){throw "Generated data failed: $android"}
   if([bool]$result.rememberedReconnect -ne $case.Known){throw 'Remembered connection state mismatch'}
   if(!$case.Known -and $android -notmatch [regex]::Escape('INSTRUMENTATION_RESULT: verificationCode='+$result.verificationCode)){throw 'Verification fingerprints differ'}
  }
  $row=[ordered]@{name=$case.Name;result='PASS';remembered=$case.Known;expectedRejection=$case.Fail;fileBytesEach=if($case.Fail){0}else{$result.fileBytesEach};tunRunning=[bool](Get-Process verge-mihomo -ErrorAction SilentlyContinue)}
  $rows+=[pscustomobject]$row; $row | ConvertTo-Json -Compress | Write-Output
  if(!$instrument.HasExited){$instrument.Kill()}; $instrument.Dispose()
 }
 $rows | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory 'summary.json')
} finally {
 & $Adb -s $Serial shell am force-stop com.floatclip.probe | Out-Null
 & $Adb -s $Serial shell run-as com.floatclip.probe rm -f shared_prefs/share-pairs-qa.xml | Out-Null
 foreach($name in @('trust','trust-retry')){ $path=Join-Path $OutputDirectory $name; if(Test-Path $path){Remove-Item -LiteralPath $path -Recurse -Force} }
}
