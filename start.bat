@echo off
setlocal
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ROOT%\.env") do (
    if not "%%a"=="" set "%%a=%%b"
  )
)

echo Starting backend on http://localhost:8080 ...
start "leetcode-backend" cmd /c "cd /d %ROOT%\backend && mvn spring-boot:run -q"

echo Starting frontend on http://localhost:5173 ...
start "leetcode-frontend" cmd /c "cd /d %ROOT%\frontend && npm run dev"

echo.
echo Backend:  http://localhost:8080
echo Frontend: http://localhost:5173
echo.
echo Press any key in this window to stop both.
pause >nul
call "%ROOT%\stop.bat"
