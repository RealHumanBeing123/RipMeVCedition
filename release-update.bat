@echo off
REM Updates ripme.json for a new release (version, SHA-256 hash, change list).
REM Put this file and ReleaseUpdate.java in the project folder (next to gradlew.bat).
REM Build first:  gradlew.bat build -x test -PcustomVersion=<new version, e.g. 2.1.21>
cd /d "%~dp0"
java "%~dp0ReleaseUpdate.java" %*
echo.
pause
