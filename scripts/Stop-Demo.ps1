$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
foreach($taskName in @('frontend','backend','mysql')){
  $taskPidFile=Join-Path $taskRoot ".local\$taskName.pid"
  if(!(Test-Path -LiteralPath $taskPidFile)){continue}
  $taskPidValue=[int](Get-Content -LiteralPath $taskPidFile)
  $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPidValue" -ErrorAction SilentlyContinue
  if($taskProcess -and $taskProcess.CommandLine -like "*$taskRoot*"){
    if($taskName -eq 'mysql'){
      & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqladmin.exe' --protocol=TCP --host=127.0.0.1 --port=13316 --user=root shutdown
      if($LASTEXITCODE -ne 0){Write-Warning 'MySQL shutdown failed; leaving it running.';continue}
    }else{Stop-Process -Id $taskPidValue}
  }elseif($taskProcess){Write-Warning "PID $taskPidValue is not a workspace process; skipped.";continue}
  Remove-Item -LiteralPath $taskPidFile
}
Write-Host 'Workspace demo services stopped. Data is retained.'
