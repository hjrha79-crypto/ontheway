@echo off
REM OnTheWay DevOps Agent v0 — Windows wrapper
REM READ-ONLY MODE: 자동 commit/push/설치/수정 = 절대 X

REM Git Bash 경로 자동 감지
set "GITBASH="
if exist "C:\Program Files\Git\bin\bash.exe" set "GITBASH=C:\Program Files\Git\bin\bash.exe"
if exist "C:\Program Files (x86)\Git\bin\bash.exe" set "GITBASH=C:\Program Files (x86)\Git\bin\bash.exe"

if "%GITBASH%"=="" (
    echo [ERROR] Git Bash not found. Install Git for Windows.
    exit /b 1
)

"%GITBASH%" "%~dp0devops_daily.sh"
