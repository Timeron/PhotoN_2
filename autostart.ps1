# Włącza lub wyłącza uruchamianie agenta kopii zapasowej PhotoN razem z Windows (przy logowaniu).
# Agent startuje od razu z wybranymi profilami „Archiwizacji telefonu” (jednym albo kilkoma), bez okna konsoli.
#
# Użycie (zwykle przez autostart.bat):
#   autostart.bat                                menu
#   autostart.bat wlacz "Telefon" ["Tablet" …]   włącz dla profili
#   autostart.bat wylacz                         wyłącz
#   autostart.bat stan                           pokaż, czy jest włączone
#
# Wpis trafia do rejestru bieżącego użytkownika (HKCU\...\Run) - nie wymaga uprawnień administratora.
param([string]$Action = '')
$Names = @($args)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$Key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$Value = 'PhotoN-kopia-zapasowa'
$Interactive = $Action -eq ''

function Finish([int]$code) {
    if ($Interactive) {
        Write-Host ''
        Read-Host 'Naciśnij Enter, aby zamknąć' | Out-Null
    }
    exit $code
}

# Folder programu: obok skryptu (wersja wyeksportowana) albo dist\PhotoN (folder projektu).
$App = $PSScriptRoot
if (-not (Test-Path (Join-Path $App 'PhotoN.exe'))) { $App = Join-Path $PSScriptRoot 'dist\PhotoN' }
$InProject = $App -ne $PSScriptRoot
$Javaw = Join-Path $App 'runtime\bin\javaw.exe'
$Backup = Join-Path $App 'photon-backup.exe'
if (-not (Test-Path (Join-Path $App 'PhotoN.exe')) -or -not (Test-Path $Javaw)) {
    Write-Host '[BŁĄD] Nie znaleziono PhotoN.exe z wbudowaną Javą obok skryptu ani w dist\PhotoN.'
    Write-Host '       Uruchom skrypt z folderu programu albo najpierw wykonaj eksport.bat.'
    Finish 1
}

function Get-Profiles {
    @(& $Backup --list | Where-Object { $_ -match '^(.+?)    ' } | ForEach-Object { $Matches[1] })
}

function Get-EnabledProfiles {
    $entry = (Get-ItemProperty -Path $Key -Name $Value -ErrorAction SilentlyContinue).$Value
    if (-not $entry) { return $null }
    @([regex]::Matches($entry, '--profile "([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}

function Test-AgentRunning {
    (& $Backup --status | Select-Object -First 1) -eq 'Agent działa.'
}

function Get-AgentArguments([string[]]$profiles) {
    $arguments = @('-Xms16m', '-Xmx512m', '-XX:+UseSerialGC', '-XX:MinHeapFreeRatio=10', '-XX:MaxHeapFreeRatio=20',
        '-XX:TieredStopAtLevel=1', '-Xss512k', '-Dfile.encoding=UTF-8',
        "-Dphoton.appLauncher=$(Join-Path $App 'PhotoN.exe')", '-cp', "$(Join-Path $App 'app\*')",
        'com.photon.backup.BackupAgent')
    foreach ($p in $profiles) { $arguments += @('--profile', $p) }
    $arguments
}

function Show-State {
    $enabled = Get-EnabledProfiles
    if ($null -eq $enabled) {
        Write-Host 'Uruchamianie razem z Windows: WYŁĄCZONE'
    } else {
        Write-Host ('Uruchamianie razem z Windows: WŁĄCZONE, profile: ' + (($enabled | ForEach-Object { '„' + $_ + '”' }) -join ', '))
    }
    Write-Host (& $Backup --status | Select-Object -First 1)
}

function Enable([string[]]$profiles) {
    if ($profiles.Count -eq 0) {
        Write-Host '[BŁĄD] Podaj co najmniej jeden profil, np.: autostart.bat wlacz "Telefon" "Tablet"'
        return 1
    }
    # Agent sam sprawdza, czy profile istnieją i czy mogą działać razem (np. czy foldery się nie pokrywają).
    $checkArgs = @('--check')
    foreach ($p in $profiles) { $checkArgs += @('--profile', $p) }
    $result = & $Backup @checkArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[BŁĄD] $result"
        Write-Host 'Dostępne profile:'
        Get-Profiles | ForEach-Object { Write-Host "  $_" }
        return 1
    }
    $quoted = (Get-AgentArguments $profiles | ForEach-Object { if ($_ -match '[\s"]' -or $_ -match '\*') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $command = '"' + $Javaw + '" ' + $quoted
    Set-ItemProperty -Path $Key -Name $Value -Value $command
    $list = ($profiles | ForEach-Object { '„' + $_ + '”' }) -join ', '
    Write-Host "Włączone: agent kopii zapasowej uruchomi się przy logowaniu z profilami: $list."
    Write-Host "Program: $App"
    if ($InProject) {
        Write-Host '[UWAGA] Agent będzie działał z folderu dist\PhotoN projektu - przed eksport.bat zatrzymaj go'
        Write-Host '        (ikona przy zegarze - Zatrzymaj kopię zapasową), bo eksport tworzy ten folder od nowa.'
    }
    if (Test-AgentRunning) {
        Write-Host 'Agent już działa - nowe profile zaczną obowiązywać po jego ponownym uruchomieniu.'
        return 0
    }
    if ((Read-Host 'Uruchomić agenta teraz? [T/N]') -match '^[tT]') {
        Start-Process -FilePath $Javaw -ArgumentList (Get-AgentArguments $profiles | ForEach-Object {
                if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
        Write-Host 'Agent uruchomiony - ikona pojawi się przy zegarze.'
    }
    return 0
}

function Disable {
    if ($null -eq (Get-EnabledProfiles)) {
        Write-Host 'Uruchamianie razem z Windows już jest wyłączone.'
    } else {
        Remove-ItemProperty -Path $Key -Name $Value
        Write-Host 'Wyłączone: agent nie będzie uruchamiany przy logowaniu.'
    }
    if ((Test-AgentRunning) -and ((Read-Host 'Agent działa teraz. Zatrzymać go? [T/N]') -match '^[tT]')) {
        & $Backup --stop
    }
    return 0
}

switch ($Action.ToLower()) {
    'wlacz' { Finish (Enable $Names) }
    'wylacz' { Finish (Disable) }
    'stan' { Show-State; Finish 0 }
    '' {
        Write-Host 'PhotoN - uruchamianie kopii zapasowej razem z Windows'
        Write-Host ''
        Show-State
        Write-Host ''
        Write-Host '  1 - Włącz (wybierz profile)'
        Write-Host '  2 - Wyłącz'
        Write-Host '  3 - Zakończ'
        $choice = Read-Host 'Wybierz [1-3]'
        if ($choice -eq '2') { Finish (Disable) }
        if ($choice -ne '1') { Finish 0 }
        $all = Get-Profiles
        if ($all.Count -eq 0) {
            Write-Host 'Brak profili. Utwórz je w PhotoN w zakładce „Archiwizacja telefonu”.'
            Finish 1
        }
        Write-Host ''
        Write-Host 'Profile „Archiwizacji telefonu”:'
        for ($i = 0; $i -lt $all.Count; $i++) { Write-Host ("  {0} - {1}" -f ($i + 1), $all[$i]) }
        $picked = Read-Host 'Numery profili (np. 1 albo 1,3 - agent pilnuje wtedy kilku folderów naraz)'
        $chosen = @($picked -split '[,; ]+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ } |
                Where-Object { $_ -ge 1 -and $_ -le $all.Count } | Select-Object -Unique | ForEach-Object { $all[$_ - 1] })
        Finish (Enable $chosen)
    }
    default {
        Write-Host 'Użycie: autostart.bat [wlacz "Profil" ["Profil2" …] | wylacz | stan]'
        exit 1
    }
}
