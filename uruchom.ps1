# Uruchamia PhotoN 2.0 bez widocznej konsoli (używany przez skrót na pulpicie).
# Wynik Mavena trafia do uruchom.log; przy błędzie pokazuje okno z komunikatem.
# Do diagnozy z widoczną konsolą użyj uruchom.bat.

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot
$log = Join-Path $PSScriptRoot "uruchom.log"

function Show-Error([string]$message) {
    Add-Type -AssemblyName System.Windows.Forms
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "$message`n`nOtworzyć plik z logiem?`n$log",
        "PhotoN 2.0 – błąd uruchamiania", "YesNo", "Error")
    if ($answer -eq "Yes" -and (Test-Path -LiteralPath $log)) {
        Start-Process notepad.exe -ArgumentList "`"$log`""
    }
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    "Nie znaleziono Mavena (mvn) w zmiennej PATH." | Out-File -LiteralPath $log -Encoding utf8
    Show-Error "Nie znaleziono Mavena (mvn) w zmiennej PATH."
    exit 1
}

"[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] Uruchamianie PhotoN 2.0" | Out-File -LiteralPath $log -Encoding utf8
cmd /c "mvn -q compile javafx:run >> `"$log`" 2>&1"
if ($LASTEXITCODE -ne 0) {
    Show-Error "Nie udało się uruchomić aplikacji (kod $LASTEXITCODE)."
    exit $LASTEXITCODE
}
