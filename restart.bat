@echo off
setlocal
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

call "%ROOT%\stop.bat"
timeout /t 1 /nobreak >nul
call "%ROOT%\start.bat"
