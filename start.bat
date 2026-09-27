@echo off
setlocal

set "JAVA_HOME=F:\work-tool\jdk21"
set "M2_HOME=F:\work-tool\apache-maven-3.9.12"
set "LOCAL_REPO=F:\work-tool\localRepository"

set "PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%"

cd /d "%~dp0"

echo [INFO] JAVA_HOME  = %JAVA_HOME%
echo [INFO] M2_HOME    = %M2_HOME%
echo [INFO] LOCAL_REPO = %LOCAL_REPO%
set "APP_PORT=8080"
set "ADMIN_PORT=8088"

echo [INFO] Checking ports %APP_PORT% / %ADMIN_PORT% ...

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ports=@(%APP_PORT%,%ADMIN_PORT%); foreach($port in $ports){$c=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue; if($c){$p=$c|Select-Object -ExpandProperty OwningProcess -Unique; Write-Host ('[INFO] Port '+$port+' in use by PID(s): '+($p -join ', ')+'. Killing...'); $p|ForEach-Object{Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue}}}; Start-Sleep -Seconds 2; foreach($port in $ports){$s=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue; if($s){Write-Host ('[WARN] Port '+$port+' still in use.')}else{Write-Host ('[INFO] Port '+$port+' released.')}}"

echo [INFO] Starting AgentWork (user port: %APP_PORT%, admin port: %ADMIN_PORT%) ...
echo [INFO] User UI:  http://localhost:%APP_PORT%
echo [INFO] Admin UI: http://localhost:%ADMIN_PORT%/admin/index.html

call "%M2_HOME%\bin\mvn.cmd" -Dmaven.repo.local="%LOCAL_REPO%" spring-boot:run

endlocal