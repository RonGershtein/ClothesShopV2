@echo off
setlocal ENABLEDELAYEDEXPANSION

REM --- תמיד לעבור לתיקייה של הסקריפט (חשוב לדאבל-קליק) ---
cd /d "%~dp0"

echo [INFO] Working dir: %CD%

REM --- בדיקת Java/Javac ---
where javac >nul 2>&1 || (
  echo [ERROR] 'javac' not found in PATH. Install JDK 17/21 or set JAVA_HOME.
  pause
  exit /b 1
)
where java >nul 2>&1 || (
  echo [ERROR] 'java' not found in PATH. Install JDK 17/21 or set JAVA_HOME.
  pause
  exit /b 1
)

REM --- קומפילציה לכל הקבצים לתיקיית out\classes ---
set OUTDIR=out\classes
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

del /q /f sources.txt 2>nul
for /R "%CD%" %%f in (*.java) do (
  echo %%f>>sources.txt
)

echo [INFO] Compiling sources...
javac -encoding UTF-8 -d "%OUTDIR%" @sources.txt 1>compile.out 2>&1
if errorlevel 1 (
  echo [ERROR] Compilation failed. See compile.out
  start "" notepad.exe compile.out
  pause
  exit /b 1
)

echo [Server] starting on 5050...
REM מריץ את המחלקה הראשית לפי ה-package שלך: server.app.StoreServer
java -cp "%OUTDIR%" server.app.StoreServer 5050 1>server.out 2>server.err

if errorlevel 1 (
  echo [ERROR] Server terminated with error. See server.err
  start "" notepad.exe server.err
)

echo.
pause
