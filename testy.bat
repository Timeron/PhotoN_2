@echo off
rem Uruchamia wszystkie testy PhotoN 2.0, także testy GUI.
rem Testy GUI działają bez ekranu: nie ruszają myszy ani klawiatury.
chcp 65001 >nul
setlocal
cd /d "%~dp0"

where mvn >nul 2>nul
if errorlevel 1 (
    echo [BŁĄD] Nie znaleziono Mavena ^(mvn^) w zmiennej PATH.
    pause
    exit /b 1
)

call mvn test
if errorlevel 1 (
    echo.
    echo [BŁĄD] Część testów nie przeszła. Raporty: target\surefire-reports
) else (
    echo.
    echo Wszystkie testy przeszły.
)
pause
endlocal
