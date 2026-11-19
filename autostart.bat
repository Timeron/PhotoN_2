@echo off
rem Włącza lub wyłącza uruchamianie agenta kopii zapasowej PhotoN razem z Windows (przy logowaniu).
rem Agent startuje od razu z wybranymi profilami (jednym albo kilkoma), bez okna konsoli (ikona przy zegarze).
rem
rem Użycie:
rem   autostart.bat                                 menu
rem   autostart.bat wlacz "Telefon" ["Tablet" ...]  włącz dla profili
rem   autostart.bat wylacz                          wyłącz
rem   autostart.bat stan                            pokaż, czy jest włączone
rem
rem Logika jest w autostart.ps1 (ten sam folder). Wpis trafia do rejestru bieżącego użytkownika
rem (HKCU\...\Run) - nie wymaga uprawnień administratora.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0autostart.ps1" %*
exit /b %errorlevel%
