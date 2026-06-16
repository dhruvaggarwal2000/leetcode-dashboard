@echo off
setlocal

echo Stopping backend (port 8080) ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
  taskkill /f /pid %%a >nul 2>&1
)

echo Stopping frontend (port 5173) ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5173" ^| findstr "LISTENING"') do (
  taskkill /f /pid %%a >nul 2>&1
)

taskkill /f /fi "WINDOWTITLE eq leetcode-backend*"  >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq leetcode-frontend*" >nul 2>&1

echo Stopped.
