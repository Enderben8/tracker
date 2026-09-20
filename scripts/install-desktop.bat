@echo off
rem Builds the Windows installer from this source tree and opens it.
rem Most people should just download the .msi from the Releases page instead.
rem
rem The ".\" matters: this PC has NoDefaultCurrentDirectoryInExePath set, so a bare
rem "gradlew" is not found even inside the project folder.
cd /d "%~dp0.."
echo Building the installer. The first run downloads a few hundred MB and takes a while.
call .\gradlew.bat :composeApp:packageMsi
if errorlevel 1 (
    echo.
    echo The build failed - see the messages above.
    pause
    exit /b 1
)
for %%f in ("composeApp\build\compose\binaries\main\msi\*.msi") do set "MSI=%%~ff"
echo.
echo Built: %MSI%
echo Opening the installer. After it finishes, Revision Tracker is in the Start Menu.
start "" "%MSI%"
