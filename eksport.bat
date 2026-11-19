@echo off
rem Eksport PhotoN 2.0 do samodzielnej wersji uruchomieniowej (nie wymaga Javy ani Mavena na komputerze docelowym).
rem
rem Wynik:
rem   dist\PhotoN\               folder programu - PhotoN.exe, photon-backup.exe, wbudowana Java
rem   dist\PhotoN-2.0.zip        ten sam folder spakowany do przeniesienia na inny komputer
rem
rem Wymagania na komputerze, na którym budujesz: JDK 21+ (z narzędziem jpackage) i Maven.
rem Wbudowana Java zachowuje java.exe/javaw.exe (bez --strip-native-commands) - PhotoN uruchamia nimi agenta w tle.
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set APP_VERSION=2.0
set INPUT=target\jpackage-input
set DIST=dist

for %%T in (mvn jpackage) do (
    where %%T >nul 2>nul
    if errorlevel 1 (
        echo [BŁĄD] Nie znaleziono %%T w zmiennej PATH. Potrzebne: JDK 21+ ^(z jpackage^) i Maven.
        exit /b 1
    )
)

if exist "%DIST%\PhotoN\PhotoN.exe" (
    tasklist /fi "imagename eq PhotoN.exe" 2>nul | find /i "PhotoN.exe" >nul
    if not errorlevel 1 (
        echo [BŁĄD] PhotoN.exe jest uruchomiony - zamknij go ^(i zatrzymaj agenta kopii zapasowej^) przed eksportem.
        exit /b 1
    )
)

rem Agent kopii zapasowej uruchomiony z dist\PhotoN (np. przez autostart.bat) blokuje pliki programu.
powershell -NoProfile -Command "if (Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like '*\dist\PhotoN\*' -and $_.Name -match 'java|photon' }) { exit 1 }"
if errorlevel 1 (
    echo [BŁĄD] Działa agent kopii zapasowej z %DIST%\PhotoN - zatrzymaj go przed eksportem
    echo        ^(ikona przy zegarze - Zatrzymaj kopię zapasową, albo %DIST%\PhotoN\photon-backup.exe --stop^).
    exit /b 1
)

echo [1/4] Budowanie aplikacji i kopiowanie bibliotek...
if exist "%INPUT%" rmdir /s /q "%INPUT%"
call mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=%INPUT%
if errorlevel 1 goto :error
copy /y "target\PhotoN_2-2.0-SNAPSHOT.jar" "%INPUT%\PhotoN.jar" >nul
if errorlevel 1 goto :error

echo [2/4] Usuwanie poprzedniego eksportu...
if exist "%DIST%\PhotoN" rmdir /s /q "%DIST%\PhotoN"
if exist "%DIST%\PhotoN\PhotoN.exe" (
    echo [BŁĄD] Nie można usunąć %DIST%\PhotoN - pliki są używane. Zamknij PhotoN i agenta.
    exit /b 1
)

echo [3/4] Tworzenie programu z wbudowaną Javą ^(jpackage^)...
jpackage --type app-image --name PhotoN --app-version %APP_VERSION% --vendor PhotoN ^
    --description "PhotoN - porządkowanie zdjęć i filmów" ^
    --input "%INPUT%" --main-jar PhotoN.jar --main-class com.photon.Launcher ^
    --icon photon.ico --java-options "-Dfile.encoding=UTF-8" ^
    --jlink-options "--strip-debug --no-man-pages --no-header-files" ^
    --add-launcher photon-backup=src\jpackage\photon-backup.properties ^
    --dest "%DIST%"
if errorlevel 1 goto :error

rem Skrypt włączający uruchamianie agenta razem z Windows (autostart.bat + autostart.ps1) - działa z folderu programu.
copy /y "autostart.bat" "%DIST%\PhotoN\autostart.bat" >nul
if errorlevel 1 goto :error
copy /y "autostart.ps1" "%DIST%\PhotoN\autostart.ps1" >nul
if errorlevel 1 goto :error

echo [4/4] Pakowanie do ZIP...
if exist "%DIST%\PhotoN-%APP_VERSION%.zip" del /q "%DIST%\PhotoN-%APP_VERSION%.zip"
powershell -NoProfile -Command "Compress-Archive -Path '%DIST%\PhotoN' -DestinationPath '%DIST%\PhotoN-%APP_VERSION%.zip'"
if errorlevel 1 goto :error

echo.
echo Gotowe:
echo   %CD%\%DIST%\PhotoN\PhotoN.exe
echo   %CD%\%DIST%\PhotoN-%APP_VERSION%.zip
exit /b 0

:error
echo.
echo [BŁĄD] Eksport nie powiódł się. Szczegóły powyżej.
exit /b 1
