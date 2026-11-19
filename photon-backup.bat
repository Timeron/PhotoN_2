@echo off
rem PhotoN 2.0 — kopia zapasowa w tle (agent bez okna) uruchamiany z konsoli.
rem
rem   photon-backup --profile "NAZWA"   uruchamia archiwizację według profilu (Ctrl+C kończy)
rem   photon-backup --status            pokazuje stan agenta
rem   photon-backup --stop              zatrzymuje działającego agenta
rem   photon-backup --list              wypisuje profile „Archiwizacji telefonu”
rem
rem Przy każdym uruchomieniu kod jest kompilowany (Maven), a potrzebne biblioteki (bez JavaFX)
rem kopiowane do target\agent-lib. Sam agent działa już bez Mavena, w osobnej, lekkiej Javie.
rem Dodatkowe opcje Javy można podać w zmiennej PHOTON_BACKUP_OPTS.
chcp 65001 >nul
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
    echo [BŁĄD] Nie znaleziono Javy ^(java^) w zmiennej PATH.
    exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
    echo [BŁĄD] Nie znaleziono Mavena ^(mvn^) w zmiennej PATH.
    exit /b 1
)

call mvn -q compile dependency:copy-dependencies -DoutputDirectory=target/agent-lib -DincludeScope=runtime -DexcludeGroupIds=org.openjfx,org.kordamp.ikonli
if errorlevel 1 (
    echo [BŁĄD] Nie udało się zbudować agenta. Szczegóły powyżej.
    exit /b 1
)

java -Xms16m -Xmx512m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:TieredStopAtLevel=1 -Xss512k -Dfile.encoding=UTF-8 %PHOTON_BACKUP_OPTS% -cp "target\classes;target\agent-lib\*" com.photon.backup.BackupAgent %*
exit /b %errorlevel%
