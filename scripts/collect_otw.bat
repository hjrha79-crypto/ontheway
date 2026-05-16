@echo off
REM OnTheWay collect_otw v1 — Windows wrapper
REM READ-ONLY MODE

REM Git Bash 경로 자동 감지
set "GITBASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "GITBASH=C:\Program Files\Git\bin\bash.exe"
if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "GITBASH=C:\Program Files (x86)\Git\bin\bash.exe"

if "%GITBASH%"=="" (
    echo [ERROR] Git Bash not found. Install Git for Windows.
    exit /b 1
)

"%GITBASH%" "%~dp0collect_otw.sh"
