@echo off
setlocal

REM --- תמיד לעבוד מתיקיית הסקריפט ---
cd /d "%~dp0"

REM --- תיקיית פלט ---
set OUTDIR=out\classes
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo [INFO] Compiling sources to %OUTDIR% ...

REM --- קומפילציה לכל קבצי ה-Java תחת src\main\java ---
javac -encoding UTF-8 -d "%OUTDIR%" ^
  src\main\java\server\net\*.java ^
  src\main\java\server\domain\customers\*.java ^
  src\main\java\server\domain\employees\*.java ^
  src\main\java\server\domain\invantory\*.java ^
  src\main\java\server\domain\sales\*.java ^
  src\main\java\server\shared\*.java ^
  src\main\java\server\util\*.java

if errorlevel 1 (
  echo [ERROR] Compilation failed.
  pause
  exit /b 1
)

echo [INFO] Compilation successful.
pause
