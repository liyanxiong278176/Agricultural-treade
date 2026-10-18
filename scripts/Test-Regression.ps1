param(
  [string]$JavaHome = $(if(Test-Path -LiteralPath 'D:\jdk21\jdk-21.0.10\bin\java.exe'){'D:\jdk21\jdk-21.0.10'}else{$env:JAVA_HOME}),
  [string]$Maven = 'D:\apache-maven-3.9.12\bin\mvn.cmd',
  [string]$MysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLocal=Join-Path $taskRoot '.local\regression'
$taskId=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'Singapore Standard Time').ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
$taskReport=Join-Path $taskRoot "docs\regression-runs\$taskId"
New-Item -ItemType Directory -Path $taskLocal,$taskReport -Force | Out-Null
$taskStages=[System.Collections.Generic.List[object]]::new()
$taskMysql=$null;$taskBackend=$null;$taskLock=$null;$taskFailure=$null
$taskVars=@('JAVA_HOME','TEST_DB_URL','TEST_DB_USER','TEST_DB_PASSWORD','DB_URL','DB_USER','DB_PASSWORD','PORT','FARM_DEMO','FARM_HTTPS','QA_BACKEND_PORT','REGRESSION_REPORT_DIR')
$taskPrevious=@{};foreach($taskKey in $taskVars){$taskPrevious[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
function Test-Port([int]$Port){$taskClient=[Net.Sockets.TcpClient]::new();try{$taskClient.Connect('127.0.0.1',$Port);return $true}catch{return $false}finally{$taskClient.Dispose()}}
function Run-Native([string]$Name,[string]$Executable,[string[]]$Arguments){
  Write-Host "Running: $Name"
  $taskStarted=Get-Date
  $taskSavedPreference=$ErrorActionPreference
  try{
    $ErrorActionPreference='Continue'
    & $Executable @Arguments 2>&1 | Out-File -LiteralPath (Join-Path $taskReport "$Name.log") -Encoding utf8
    $taskCode=$LASTEXITCODE
  }finally{$ErrorActionPreference=$taskSavedPreference}
  $taskStatus=if($taskCode -eq 0){'PASS'}else{'FAIL'}
  $taskStages.Add([pscustomobject]@{name=$Name;status=$taskStatus;exitCode=$taskCode;seconds=[Math]::Round(((Get-Date)-$taskStarted).TotalSeconds,1)})
  Write-Host "$Name : $taskStatus"
  if($taskCode -ne 0){Get-Content -LiteralPath (Join-Path $taskReport "$Name.log") -Tail 18 | Write-Host}
  return ($taskCode -eq 0)
}
function Wait-Mysql{
  for($taskAttempt=0;$taskAttempt -lt 120;$taskAttempt++){
    if($taskMysql.HasExited){throw 'Regression MySQL exited; see mysql.err.log.'}
    if(Test-Port 13317){
      $taskSavedPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
      & "$MysqlBin\mysqladmin.exe" --no-defaults --protocol=TCP --host=127.0.0.1 --port=13317 --user=root --connect-timeout=2 ping --silent 2>$null | Out-Null
      $taskPingCode=$LASTEXITCODE;$ErrorActionPreference=$taskSavedPreference
      if($taskPingCode -eq 0){return}
    }
    Start-Sleep -Milliseconds 500
  };throw 'Regression MySQL startup timed out.'
}
function Start-Backend{
  $script:taskBackend=Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList '-jar',"`"$taskReport\backend.jar`"",'--server.address=127.0.0.1' -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput "$taskReport\backend-$script:taskBoot.out.log" -RedirectStandardError "$taskReport\backend-$script:taskBoot.err.log" -PassThru
  for($taskAttempt=0;$taskAttempt -lt 180;$taskAttempt++){
    if($taskBackend.HasExited){throw 'Regression backend exited; see backend logs.'}
    try{
      $taskLogin=Invoke-RestMethod 'http://127.0.0.1:8082/api/login' -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"Farm@2026"}' -TimeoutSec 2
      if($taskLogin.token){return}
    }catch{}
    Start-Sleep -Milliseconds 500
  };throw 'Regression backend readiness timed out.'
}
try{
  $taskLock=[IO.File]::Open((Join-Path $taskLocal 'run.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
  foreach($taskPort in @(13317,8082,4182)){if(Test-Port $taskPort){throw "Regression port $taskPort is occupied. Existing services will not be reused or stopped."}}
  foreach($taskFile in @("$JavaHome\bin\java.exe",$Maven,"$MysqlBin\mysqld.exe","$MysqlBin\mysql.exe","$MysqlBin\mysqladmin.exe")){if(!(Test-Path -LiteralPath $taskFile)){throw "Missing dependency: $taskFile"}}
  if((& "$JavaHome\bin\java.exe" --version | Out-String) -notmatch '\b21\.'){throw 'Java 21 is required.'}
  Get-Command node.exe,npm.cmd,python.exe -ErrorAction Stop | Out-Null
  $env:JAVA_HOME=$JavaHome;$env:FARM_HTTPS='false';$env:REGRESSION_REPORT_DIR=$taskReport
  $env:TEST_DB_URL=$null;$env:TEST_DB_USER=$null;$env:TEST_DB_PASSWORD=$null
  $env:DB_URL=$null;$env:DB_USER=$null;$env:DB_PASSWORD=$null
  Push-Location "$taskRoot\frontend"
  try{
    if(!(Run-Native 'dependencies' 'npm.cmd' @('ci','--no-audit','--no-fund'))){throw 'Dependency installation failed.'}
    $null=Run-Native 'frontend-unit' 'npm.cmd' @('test','--','--reporter=default','--reporter=json',"--outputFile=$taskReport\frontend-unit.json")
    $taskFrontendBuilt=Run-Native 'frontend-build' 'npm.cmd' @('run','build')
  }finally{Pop-Location}
  Push-Location "$taskRoot\backend"
  try{
    $null=Run-Native 'backend-h2' $Maven @('-B','test')
    Copy-Item -LiteralPath "$taskRoot\backend\target\surefire-reports" -Destination "$taskReport\backend-h2" -Recurse
  }finally{Pop-Location}
  $taskData=Join-Path $taskLocal 'mysql-data'
  if(!(Test-Path -LiteralPath "$taskData\mysql")){
    New-Item -ItemType Directory -Path $taskData -Force | Out-Null
    if(!(Run-Native 'mysql-initialize' "$MysqlBin\mysqld.exe" @('--no-defaults',"--basedir=$(Split-Path -Parent $MysqlBin)","--datadir=$taskData",'--initialize-insecure','--console'))){throw 'Test MySQL initialization failed.'}
  }
  $taskMysql=Start-Process -FilePath "$MysqlBin\mysqld.exe" -ArgumentList @('--no-defaults',"--basedir=`"$(Split-Path -Parent $MysqlBin)`"","--datadir=`"$taskData`"",'--port=13317','--bind-address=127.0.0.1','--mysqlx=OFF','--console') -WindowStyle Hidden -RedirectStandardOutput "$taskReport\mysql.out.log" -RedirectStandardError "$taskReport\mysql.err.log" -PassThru
  Wait-Mysql
  # Only this runner's private instance, fixed names, and fixed SQL may be reset.
  if(!(Run-Native 'database-prepare' "$MysqlBin\mysql.exe" @('--no-defaults','--protocol=TCP','-h127.0.0.1','-P13317','-uroot','-e','DROP DATABASE IF EXISTS harvest_test; CREATE DATABASE harvest_test CHARACTER SET utf8mb4; DROP DATABASE IF EXISTS harvest_regression; CREATE DATABASE harvest_regression CHARACTER SET utf8mb4; DROP DATABASE IF EXISTS harvest_migration_test; CREATE DATABASE harvest_migration_test CHARACTER SET utf8mb4;'))){throw 'Test database preparation failed.'}
  $env:TEST_DB_URL='jdbc:mysql://127.0.0.1:13317/harvest_test?serverTimezone=Asia/Shanghai';$env:TEST_DB_USER='root'
  Push-Location "$taskRoot\backend"
  try{
    $null=Run-Native 'backend-mysql' $Maven @('-B','test')
    Copy-Item -LiteralPath "$taskRoot\backend\target\surefire-reports" -Destination "$taskReport\backend-mysql" -Recurse
    $taskBackendBuilt=Run-Native 'backend-package' $Maven @('-B','package','-DskipTests')
  }finally{Pop-Location}
  if(!$taskFrontendBuilt -or !$taskBackendBuilt){throw 'Build failed; browser testing cannot proceed.'}
  Copy-Item -LiteralPath "$taskRoot\backend\target\harvest-ledger-1.0.0.jar" -Destination "$taskReport\backend.jar"
  $env:DB_URL='jdbc:mysql://127.0.0.1:13317/harvest_regression?serverTimezone=Asia/Shanghai';$env:DB_USER='root';$env:DB_PASSWORD='';$env:PORT='8082';$env:FARM_DEMO='true';$env:QA_BACKEND_PORT='8082'
  $script:taskBoot=1;Start-Backend
  Push-Location "$taskRoot\frontend"
  try{$null=Run-Native 'browser' 'npm.cmd' @('run','test:e2e','--','--config','playwright.regression.config.ts')}finally{Pop-Location}
  $null=Run-Native 'workbook' 'python.exe' @("$taskRoot\scripts\Verify-QAWorkbook.py")
  # Verify persisted records and account usability across a real backend restart.
  $taskLogin=Invoke-RestMethod 'http://127.0.0.1:8082/api/login' -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"Farm@2026"}'
  $taskHeaders=@{Authorization="Bearer $($taskLogin.token)"}
  $taskMonth=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'Singapore Standard Time').ToString('yyyy-MM')
  (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:8082/api/records?month=$taskMonth" -Headers $taskHeaders).Content | Set-Content -LiteralPath "$taskReport\restart-before.json" -Encoding utf8
  Stop-Process -Id $taskBackend.Id;$taskBackend.WaitForExit();$script:taskBoot=2;Start-Backend
  (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:8082/api/records?month=$taskMonth" -Headers $taskHeaders).Content | Set-Content -LiteralPath "$taskReport\restart-after.json" -Encoding utf8
  $null=Run-Native 'backend-restart' 'python.exe' @("$taskRoot\scripts\Verify-RegressionRestart.py",$taskReport)
}catch{
  $taskFailure=$_.Exception.Message
  $taskStages.Add([pscustomobject]@{name='orchestration';status='FAIL';message=$taskFailure})
  Write-Host "Regression error: $taskFailure"
}finally{
  try{
    if($taskBackend -and !$taskBackend.HasExited){Stop-Process -Id $taskBackend.Id;$taskBackend.WaitForExit()}
    if($taskMysql -and !$taskMysql.HasExited){
      & "$MysqlBin\mysqladmin.exe" --no-defaults --protocol=TCP --host=127.0.0.1 --port=13317 --user=root --connect-timeout=2 shutdown 2>&1 | Out-File -LiteralPath "$taskReport\mysql-shutdown.log"
      if(!$taskMysql.WaitForExit(15000)){Stop-Process -Id $taskMysql.Id;$taskMysql.WaitForExit()}
    }
  }catch{$taskStages.Add([pscustomobject]@{name='cleanup';status='FAIL';message=$_.Exception.Message})}
  $taskStages | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$taskReport\stages.json" -Encoding utf8
  foreach($taskKey in $taskVars){[Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey],'Process')}
  if($taskLock){$taskLock.Dispose()}
}
& python.exe "$taskRoot\scripts\Summarize-Regression.py" $taskReport
$taskSummaryExit=$LASTEXITCODE
Write-Host "Report: $taskReport\REPORT.md"
if($taskSummaryExit -ne 0 -or $taskFailure){exit 1}
exit 0
