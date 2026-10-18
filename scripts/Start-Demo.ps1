param(
  [string]$JavaHome = $(if(Test-Path -LiteralPath 'D:\jdk21\jdk-21.0.10\bin\java.exe'){'D:\jdk21\jdk-21.0.10'}else{$env:JAVA_HOME}),
  [string]$Maven = 'D:\apache-maven-3.9.12\bin\mvn.cmd',
  [string]$MysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
  [switch]$Build,
  [switch]$Https
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLocal=Join-Path $taskRoot '.local'
New-Item -ItemType Directory -Path $taskLocal -Force | Out-Null
function Test-Port([int]$Port){$taskClient=[System.Net.Sockets.TcpClient]::new();try{$taskClient.Connect('127.0.0.1',$Port);return $true}catch{return $false}finally{$taskClient.Dispose()}}
function Wait-Port([int]$Port){for($taskRetry=0;$taskRetry -lt 60;$taskRetry++){if(Test-Port $Port){return};Start-Sleep -Milliseconds 500};throw "Port $Port did not start. Check .local logs."}
function Assert-WorkspacePort([int]$Port){$taskOwner=(Get-NetTCPConnection -LocalPort $Port -State Listen | Select-Object -First 1).OwningProcess;$taskOwnerProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskOwner";if(!$taskOwnerProcess -or $taskOwnerProcess.CommandLine -notlike "*$taskRoot*"){throw "Port $Port is used by another application. It will not be reused or stopped."}}
if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))){throw 'Java 21 not found. Pass -JavaHome or set JAVA_HOME.'}
$taskJavaVersion=(& "$JavaHome\bin\java.exe" --version | Out-String)
if($taskJavaVersion -notmatch '\b21\.'){throw 'This project requires Java 21. Pass -JavaHome with a JDK 21 path.'}
$env:JAVA_HOME=$JavaHome
if(!(Test-Path -LiteralPath "$taskRoot\frontend\node_modules\vite\bin\vite.js")){
  Push-Location "$taskRoot\frontend"
  try{& npm.cmd ci --no-audit --no-fund;if($LASTEXITCODE -ne 0){throw 'npm ci failed'}}finally{Pop-Location}
}
if($Build -or !(Test-Path -LiteralPath "$taskRoot\backend\target\harvest-ledger-1.0.0.jar")){
  Push-Location "$taskRoot\backend"
  try {& $Maven -q package;if($LASTEXITCODE -ne 0){throw 'Backend build failed'}}finally{Pop-Location}
}
if($Build -or !(Test-Path -LiteralPath "$taskRoot\frontend\dist\index.html")){
  Push-Location "$taskRoot\frontend"
  try{if(!(Test-Path -LiteralPath node_modules)){& npm.cmd ci --no-audit --no-fund;if($LASTEXITCODE -ne 0){throw 'npm ci failed'}};& npm.cmd run build;if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}}finally{Pop-Location}
}
if(!(Test-Port 13316)){
  $taskData=Join-Path $taskLocal 'mysql-data'
  if(!(Test-Path -LiteralPath "$taskData\mysql")){
    New-Item -ItemType Directory -Path $taskData -Force | Out-Null
    & "$MysqlBin\mysqld.exe" --no-defaults "--basedir=$(Split-Path -Parent $MysqlBin)" "--datadir=$taskData" --initialize-insecure --console
    if($LASTEXITCODE -ne 0){throw 'Workspace MySQL initialization failed'}
  }
  $taskArgs=@('--no-defaults',"--basedir=`"$(Split-Path -Parent $MysqlBin)`"","--datadir=`"$taskData`"",'--port=13316','--bind-address=127.0.0.1','--mysqlx=OFF','--console')
  $taskProcess=Start-Process -FilePath "$MysqlBin\mysqld.exe" -ArgumentList $taskArgs -WindowStyle Hidden -RedirectStandardOutput "$taskLocal\mysql.out.log" -RedirectStandardError "$taskLocal\mysql.err.log" -PassThru
  $taskProcess.Id | Set-Content -LiteralPath "$taskLocal\mysql.pid"
  Wait-Port 13316
  $taskOwner=(Get-NetTCPConnection -LocalPort 13316 -State Listen | Select-Object -First 1).OwningProcess
  $taskOwner | Set-Content -LiteralPath "$taskLocal\mysql.pid"
}else{Assert-WorkspacePort 13316}
if(!(Test-Port 8080)){
  $taskProcess=Start-Process -FilePath "$JavaHome\bin\java.exe" -ArgumentList '-jar',"`"$taskRoot\backend\target\harvest-ledger-1.0.0.jar`"" -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput "$taskLocal\backend.out.log" -RedirectStandardError "$taskLocal\backend.err.log" -PassThru
  $taskProcess.Id | Set-Content -LiteralPath "$taskLocal\backend.pid"
  Wait-Port 8080
}else{Assert-WorkspacePort 8080}
if($Https){if(!(Test-Path -LiteralPath "$taskLocal\certs\server.pem")){throw 'Run New-DemoCertificate.ps1 first.'};$env:FARM_HTTPS='true'}else{$env:FARM_HTTPS='false'}
if(!(Test-Port 4173)){
  $taskNode=(Get-Command node.exe).Source
  $taskVite=Join-Path $taskRoot 'frontend\node_modules\vite\bin\vite.js'
  $taskProcess=Start-Process -FilePath $taskNode -ArgumentList "`"$taskVite`"",'preview','--host','0.0.0.0' -WorkingDirectory "$taskRoot\frontend" -WindowStyle Hidden -RedirectStandardOutput "$taskLocal\frontend.out.log" -RedirectStandardError "$taskLocal\frontend.err.log" -PassThru
  $taskProcess.Id | Set-Content -LiteralPath "$taskLocal\frontend.pid"
  Wait-Port 4173
}else{Assert-WorkspacePort 4173}
$taskScheme=if($Https){'https'}else{'http'}
Write-Host "Ready: ${taskScheme}://localhost:4173"
Write-Host 'Demo accounts: admin / recorder / recorder2; password: Farm@2026'
Write-Host 'Database: workspace .local/mysql-data, loopback port 13316 (not your existing MySQL service).'
Write-Host 'Android offline reopening requires the HTTPS setup documented in README.md.'
