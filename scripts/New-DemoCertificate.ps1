param([Parameter(Mandatory=$true)][string]$LanIp,[string]$OpenSsl='D:\Git\usr\bin\openssl.exe')
$ErrorActionPreference='Stop'
$taskAddress=$null
if(![System.Net.IPAddress]::TryParse($LanIp,[ref]$taskAddress) -or $taskAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork){throw 'Pass the IPv4 LAN address of this computer.'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskCerts=Join-Path $taskRoot '.local\certs'
New-Item -ItemType Directory -Path $taskCerts -Force | Out-Null
if(!(Test-Path -LiteralPath "$taskCerts\ca.pem")){
  & $OpenSsl req -x509 -newkey rsa:2048 -nodes -keyout "$taskCerts\ca-key.pem" -out "$taskCerts\ca.pem" -days 365 -subj '/CN=Harvest Ledger Local Demo CA' -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign'
  if($LASTEXITCODE -ne 0){throw 'CA generation failed'}
}
& $OpenSsl req -newkey rsa:2048 -nodes -keyout "$taskCerts\server-key.pem" -out "$taskCerts\server.csr" -subj '/CN=Harvest Ledger Local Demo'
if($LASTEXITCODE -ne 0){throw 'Server key generation failed'}
@"
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1,IP:$LanIp
"@ | Set-Content -LiteralPath "$taskCerts\server.ext" -Encoding ascii
& $OpenSsl x509 -req -in "$taskCerts\server.csr" -CA "$taskCerts\ca.pem" -CAkey "$taskCerts\ca-key.pem" -CAcreateserial -out "$taskCerts\server.pem" -days 180 -extfile "$taskCerts\server.ext"
if($LASTEXITCODE -ne 0){throw 'Certificate signing failed'}
& $OpenSsl x509 -in "$taskCerts\ca.pem" -outform DER -out "$taskCerts\harvest-demo-ca.crt"
Write-Host "Certificate generated for https://${LanIp}:4173"
Write-Host "Import this CA on your own demo devices: $taskCerts\harvest-demo-ca.crt"
Write-Host 'This script does not alter Windows or Android trust settings.'
