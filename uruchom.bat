@echo off
rem Uruchamia aplikację PhotoN 2.0 (kompiluje ją w razie potrzeby).
chcp 65001 >nul
setlocal
cd /d "%~dp0"

where mvn >nul 2>nul
if errorlevel 1 (
    echo [BŁĄD] Nie znaleziono Mavena ^(mvn^) w zmiennej PATH.
    echo Zainstaluj Apache Maven i dodaj jego folder bin do PATH.
    pause
    exit /b 1
)

echo Uruchamianie PhotoN 2.0...
call mvn -q compile javafx:run
if errorlevel 1 (
    echo.
    echo [BŁĄD] Nie udało się uruchomić aplikacji. Szczegóły powyżej.
    pause
    exit /b 1
)
endlocal
