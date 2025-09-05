@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

echo [Build] Compiling...
if not exist "out" mkdir "out"
dir /s /b "src\*.java" > "out\sources.txt" 2>nul

if not exist "out\sources.txt" (
  echo [Build] ERROR: no sources list.
  pause & exit /b 1
)

for /f %%A in ('type "out\sources.txt" ^| find /c /v ""') do set COUNT=%%A
if "%COUNT%"=="0" (
  echo [Build] ERROR: no .java files under src\
  pause & exit /b 1
)

javac -encoding UTF-8 -d "out" @"out\sources.txt"
if errorlevel 1 (
  echo [Build] FAILED.
  pause & exit /b 1
)

if not exist "data" mkdir "data"
if not exist "logs" mkdir "logs"
if not exist "data\employees.txt" type nul > "data\employees.txt"

echo [Build] OK.
pause
exit /b 0
