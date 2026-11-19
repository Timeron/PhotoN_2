@echo off
rem Włącza lub wyłącza uruchamianie agenta kopii zapasowej PhotoN razem z Windows (przy logowaniu).
rem Agent startuje od razu z wybranym profilem „Archiwizacji telefonu”, bez okna konsoli (ikona przy zegarze).
rem
rem Użycie:
rem   autostart.bat                          menu
rem   autostart.bat wlacz "Nazwa profilu"    włącz dla profilu
rem   autostart.bat wylacz                   wyłącz
rem   autostart.bat stan                     pokaż, czy jest włączone
rem
rem Wpis trafia do rejestru bieżącego użytkownika (HKCU\...\Run) - nie wymaga uprawnień administratora.
rem Skrypt działa z folderu wersji wyeksportowanej (obok PhotoN.exe) albo z folderu projektu (dist\PhotoN).
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion

set "KEY=HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
set "VALUE=PhotoN-kopia-zapasowa"

set "APP=%~dp0"
if not exist "%APP%PhotoN.exe" set "APP=%~dp0dist\PhotoN\"
if not exist "%APP%PhotoN.exe" (
    echo [BŁĄD] Nie znaleziono PhotoN.exe obok skryptu ani w dist\PhotoN.
    echo        Uruchom skrypt z folderu programu albo najpierw wykonaj eksport.bat.
    set "INTERACTIVE=1"
    goto :fail
)
set "JAVAW=%APP%runtime\bin\javaw.exe"
set "BACKUP=%APP%photon-backup.exe"
if not exist "%JAVAW%" (
    echo [BŁĄD] Brak %JAVAW% - wyeksportuj program ponownie ^(eksport.bat^).
    set "INTERACTIVE=1"
    goto :fail
)

set "ACTION=%~1"
if "%ACTION%"=="" goto :menu
if /i "%ACTION%"=="wlacz" (
    call :enable "%~2"
    if errorlevel 1 goto :fail
    goto :end
)
if /i "%ACTION%"=="wylacz" (
    call :disable
    goto :end
)
if /i "%ACTION%"=="stan" (
    call :state
    goto :end
)
echo Użycie: autostart.bat [wlacz "Nazwa profilu" ^| wylacz ^| stan]
exit /b 1

rem =====================================================================
:menu
set "INTERACTIVE=1"
echo PhotoN - uruchamianie kopii zapasowej razem z Windows
echo.
call :state
echo.
echo   1 - Włącz ^(wybierz profil^)
echo   2 - Wyłącz
echo   3 - Zakończ
echo.
choice /c 123 /n /m "Wybierz [1-3]: "
if errorlevel 3 goto :end
if errorlevel 2 (
    call :disable
    goto :end
)
echo.
echo Profile „Archiwizacji telefonu”:
"%BACKUP%" --list
echo.
set "PNAME="
set /p "PNAME=Nazwa profilu (dokładnie jak wyżej): "
call :enable "%PNAME%"
if errorlevel 1 goto :fail
goto :end

rem =====================================================================
:enable
set "PNAME=%~1"
if "%PNAME%"=="" (
    echo [BŁĄD] Podaj nazwę profilu, np.: autostart.bat wlacz "Telefon"
    exit /b 1
)
rem Czy taki profil istnieje (nazwa przez zmienną środowiskową - bezpieczne dla polskich znaków).
set "PHOTON_PROFILE=%PNAME%"
set "PHOTON_BACKUP=%BACKUP%"
powershell -NoProfile -Command "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $lines = & $env:PHOTON_BACKUP --list; if ($lines | Where-Object { $_ -like ($env:PHOTON_PROFILE + '    *') }) { exit 0 } else { exit 1 }"
if errorlevel 1 (
    echo [BŁĄD] Nie ma profilu „%PNAME%”. Dostępne profile:
    "%BACKUP%" --list
    exit /b 1
)

set "COMMAND=\"%JAVAW%\" -Xms16m -Xmx512m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:TieredStopAtLevel=1 -Xss512k -Dfile.encoding=UTF-8 -Dphoton.appLauncher=\"%APP%PhotoN.exe\" -cp \"%APP%app\*\" com.photon.backup.BackupAgent --profile \"%PNAME%\""
reg add "%KEY%" /v "%VALUE%" /t REG_SZ /d "%COMMAND%" /f >nul
if errorlevel 1 (
    echo [BŁĄD] Nie udało się zapisać wpisu w rejestrze.
    exit /b 1
)
echo Włączone: agent kopii zapasowej uruchomi się przy logowaniu z profilem „%PNAME%”.
echo Program: %APP%
if /i "%APP%"=="%~dp0dist\PhotoN\" (
    echo [UWAGA] Agent będzie działał z folderu dist\PhotoN projektu - przed eksport.bat zatrzymaj go
    echo         ^(ikona przy zegarze - Zatrzymaj kopię zapasową^), bo eksport tworzy ten folder od nowa.
)

"%BACKUP%" --status | find "Agent działa." >nul
if not errorlevel 1 (
    echo Agent już działa - nowy profil zacznie obowiązywać po jego ponownym uruchomieniu.
    exit /b 0
)
choice /c TN /n /m "Uruchomić agenta teraz? [T/N]: "
if errorlevel 2 exit /b 0
start "" "%JAVAW%" -Xms16m -Xmx512m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:TieredStopAtLevel=1 -Xss512k -Dfile.encoding=UTF-8 "-Dphoton.appLauncher=%APP%PhotoN.exe" -cp "%APP%app\*" com.photon.backup.BackupAgent --profile "%PNAME%"
echo Agent uruchomiony - ikona pojawi się przy zegarze.
exit /b 0

rem =====================================================================
:disable
reg query "%KEY%" /v "%VALUE%" >nul 2>nul
if errorlevel 1 (
    echo Uruchamianie razem z Windows już jest wyłączone.
) else (
    reg delete "%KEY%" /v "%VALUE%" /f >nul
    echo Wyłączone: agent nie będzie uruchamiany przy logowaniu.
)
"%BACKUP%" --status | find "Agent działa." >nul
if errorlevel 1 exit /b 0
choice /c TN /n /m "Agent działa teraz. Zatrzymać go? [T/N]: "
if errorlevel 2 exit /b 0
"%BACKUP%" --stop
exit /b 0

rem =====================================================================
:state
reg query "%KEY%" /v "%VALUE%" >nul 2>nul
if errorlevel 1 (
    echo Uruchamianie razem z Windows: WYŁĄCZONE
) else (
    for /f "tokens=2,*" %%A in ('reg query "%KEY%" /v "%VALUE%" ^| find "REG_SZ"') do set "CURRENT=%%B"
    call :printProfile
)
"%BACKUP%" --status | find "Agent "
exit /b 0

:printProfile
set "CURRENT_PROFILE=%CURRENT:*--profile =%"
echo Uruchamianie razem z Windows: WŁĄCZONE, profil %CURRENT_PROFILE%
exit /b 0

rem =====================================================================
:fail
if defined INTERACTIVE pause
exit /b 1

:end
if defined INTERACTIVE (
    echo.
    pause
)
exit /b 0
