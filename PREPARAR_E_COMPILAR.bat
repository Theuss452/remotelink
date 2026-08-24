@echo off
setlocal
cd /d "%~dp0"
echo RemoteLink v0.2-alpha
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\prepare-build.ps1"
set ERR=%ERRORLEVEL%
echo.
if not "%ERR%"=="0" (echo A compilacao falhou. Veja o erro acima.) else (echo APK pronto na pasta do projeto.)
pause
exit /b %ERR%
