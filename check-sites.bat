@echo off
REM Runs ALL of RipMe's site tests (including the ones that are switched off) against the live websites and
REM writes site-report-live.txt, which says what works and why the rest does not (Cloudflare/403, server
REM errors, dead sites, dead test links, or rippers that need fixing).
REM Takes 15-40 minutes. Needs an internet connection. Run it from the project folder.
cd /d "%~dp0"
echo Running live site tests... this takes a while. Please wait.
call gradlew.bat testLive --continue
echo.
java "%~dp0SiteReport.java"
echo.
pause
