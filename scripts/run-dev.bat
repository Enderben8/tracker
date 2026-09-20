@echo off
rem Runs the desktop app straight from source, for development.
rem It compiles first, so it is slower to start than the installed app.
rem
rem The ".\" matters: this PC has NoDefaultCurrentDirectoryInExePath set, so a bare
rem "gradlew" is not found even inside the project folder.
cd /d "%~dp0.."
call .\gradlew.bat :composeApp:run
if errorlevel 1 (
    echo.
    echo Something went wrong - see the messages above.
    pause
)
