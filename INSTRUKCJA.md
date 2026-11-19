# PhotoN 2.0 — instrukcja

Aplikacja do porządkowania zdjęć i filmów: wyszukiwanie duplikatów, miniatury, archiwizacja zdjęć z telefonu
i automatyczna kopia zapasowa w tle. Napisana w Javie (JavaFX), budowana Mavenem, przeznaczona dla Windows.

Spis treści:

1. [Wymagania](#1-wymagania)
2. [Uruchamianie](#2-uruchamianie)
3. [Eksport (wersja uruchomieniowa)](#3-eksport-wersja-uruchomieniowa)
4. [Na co zwracać uwagę](#4-na-co-zwracać-uwagę)
5. [Funkcje aplikacji](#5-funkcje-aplikacji)
6. [Gdzie aplikacja trzyma dane](#6-gdzie-aplikacja-trzyma-dane)
7. [Testy](#7-testy)

---

## 1. Wymagania

**Na komputerze, na którym używasz programu (wersja wyeksportowana):** tylko Windows 10/11.
Java, Maven ani internet nie są potrzebne — Java jest wbudowana w folder programu.

**Na komputerze, na którym budujesz / rozwijasz program:**

| Składnik | Wersja | Uwagi |
|---|---|---|
| Java (JDK) | **21 lub nowsza**, z narzędziem `jpackage` | Kod jest kompilowany dla Javy 21 (`maven.compiler.release=21`). `java` i `jpackage` muszą być w `PATH`. |
| Apache Maven | 3.x | `mvn` w `PATH`. Ściąga biblioteki (JavaFX, Jackson, metadata-extractor…). |
| Internet | przy pierwszym budowaniu | Biblioteki trafiają do `%USERPROFILE%\.m2\repository`. |

---

## 2. Uruchamianie

### 2.1. Wersja wyeksportowana (program)

W folderze programu (np. `C:\Programy\PhotoN`):

| Plik | Co robi |
|---|---|
| `PhotoN.exe` | Aplikacja z oknem. Można przypiąć do paska zadań lub utworzyć skrót na pulpicie. |
| `photon-backup.exe` | Agent kopii zapasowej z konsoli (`--profile`, `--status`, `--stop`, `--list`) — patrz [5.5](#55-kopia-zapasowa-agent-w-tle). |

### 2.2. Ze źródeł (w trakcie rozwijania)

| Plik | Co robi |
|---|---|
| `uruchom.bat` | Kompiluje (w razie potrzeby) i uruchamia PhotoN z widoczną konsolą — do diagnozy problemów. |
| `uruchom.ps1` | To samo bez okna konsoli; wynik trafia do `uruchom.log`, przy błędzie pokazuje okno z komunikatem. |
| `photon-backup.bat` | Agent kopii zapasowej z konsoli, uruchamiany ze źródeł. |
| `eksport.bat` | Buduje wersję uruchomieniową — patrz [3](#3-eksport-wersja-uruchomieniowa). |
| `testy.bat` | Uruchamia wszystkie testy (także testy interfejsu — bez ekranu, nie ruszają myszy). |

Z IDE (np. IntelliJ IDEA) uruchamia się klasę `com.photon.Launcher` (aplikacja) albo
`com.photon.backup.BackupAgent` z argumentami np. `--profile "Telefon"` (agent).

---

## 3. Eksport (wersja uruchomieniowa)

Eksport tworzy **samodzielny program**: folder z `PhotoN.exe`, `photon-backup.exe` i wbudowaną Javą
(JavaFX w środku). Taki folder kopiuje się na dowolny komputer z Windows i uruchamia jak zwykły program —
bez instalowania Javy i Mavena.

### 3.1. Jak wyeksportować

1. Zamknij PhotoN i **zatrzymaj agenta kopii zapasowej**, jeśli działa z poprzedniego eksportu
   (pliki programu są wtedy zablokowane i nie da się ich zastąpić).
2. Uruchom w folderze projektu **`eksport.bat`** (z Eksploratora, konsoli albo terminala w IDE).
   Skrypt:
   1. buduje aplikację Mavenem (bez testów) i kopiuje potrzebne biblioteki do `target\jpackage-input`,
   2. narzędziem `jpackage` (z JDK) tworzy `dist\PhotoN\` z wbudowaną Javą,
   3. pakuje wynik do `dist\PhotoN-2.0.zip`.
3. Wynik:
   - `dist\PhotoN\` — gotowy program (ok. 165 MB),
   - `dist\PhotoN-2.0.zip` — to samo spakowane do przeniesienia (ok. 70 MB).

Przed eksportem warto uruchomić testy (`testy.bat`) — `eksport.bat` ich nie uruchamia.

### 3.2. Instalacja na innym komputerze

1. Skopiuj `PhotoN-2.0.zip` i rozpakuj **w całości** w wybrane miejsce, np. `C:\Programy\PhotoN`
   (najlepiej poza „Pobranymi” i poza folderem synchronizowanym z chmurą).
2. Uruchom `PhotoN.exe`. Opcjonalnie: prawy klik → „Wyślij do → Pulpit (utwórz skrót)”.
3. Przy pierwszym uruchomieniu Windows SmartScreen może ostrzec o nieznanym wydawcy (program nie jest
   podpisany cyfrowo) — „Więcej informacji → Uruchom mimo to”.

Aktualizacja: zatrzymaj agenta, zamknij PhotoN, usuń stary folder programu i rozpakuj nowy w to samo miejsce.
Dane użytkownika (profile, ustawienia) są w innym miejscu i zostają.

### 3.3. Przeniesienie danych użytkownika (profile, ustawienia, dzienniki)

Dane są poza folderem programu, w `%APPDATA%\PhotoN2` (szczegóły w [6](#6-gdzie-aplikacja-trzyma-dane)).
Aby przenieść je na inny komputer:

1. **Zatrzymaj agenta kopii zapasowej** i zamknij PhotoN.
2. Skopiuj do `%APPDATA%\PhotoN2` na nowym komputerze:
   - `profiles\` — profile wszystkich modułów,
   - `settings.json` — ustawienia,
   - opcjonalnie `logs\` (historia operacji).
3. **Nie kopiuj** `backup\agent.lock`, `backup\commands\` ani `state.json` (rozmiar i położenie okna —
   na innym monitorze może być nieprawidłowe). Folder `cache\` można pominąć — odbuduje się sam.
4. **Popraw ścieżki w profilach.** Profile zawierają pełne ścieżki (np. `D:\Zdjecia`). Jeśli na nowym komputerze
   litery dysków lub foldery są inne, otwórz każdy profil w aplikacji, wskaż foldery i zapisz.

### 3.4. Na co uważać przy eksporcie

- **Folder programu musi zostać w całości** (`PhotoN.exe`, `photon-backup.exe`, `app\`, `runtime\`).
  Nie przenoś samego `PhotoN.exe` — bez `app` i `runtime` nie zadziała. Skrót tworzy się do `PhotoN.exe`.
- **Wbudowana Java pochodzi z JDK, którym budujesz** (teraz JDK 23). Eksport zrobiony innym JDK da inną Javę
  w środku — warto budować zawsze tym samym.
- **Wbudowana Java zawiera `java.exe` i `javaw.exe`** (opcja `--jlink-options` w `eksport.bat`, bez
  `--strip-native-commands`) — PhotoN uruchamia nimi agenta w tle. Nie usuwaj tej opcji.
- **Wersja programu** jest ustawiona w `eksport.bat` (`APP_VERSION=2.0`) — przy kolejnych wydaniach podnieś ją
  (zmienia się też nazwa pliku ZIP).
- **Ikona** pochodzi z `photon.ico` w folderze projektu.
- **Agent uruchomiony z PhotoN działa z folderu programu** — przed usunięciem lub aktualizacją folderu programu
  zatrzymaj agenta.
- **Instalator** (`.msi` / `.exe` z kreatorem) nie jest tworzony — wymagałby dodatkowego narzędzia WiX.
  Wystarcza rozpakowanie ZIP. Gdyby był potrzebny: zainstaluj WiX Toolset i w `eksport.bat` zmień
  `--type app-image` na `--type msi` (z opcjami `--win-menu --win-shortcut --win-dir-chooser`).
- `dist\` i `target\` są w `.gitignore` — wyniki eksportu nie trafiają do repozytorium.

---

## 4. Na co zwracać uwagę

**Ochrona plików**
- Foldery, których nazwa zaczyna się od `__` (np. `__Archiwum`), oraz wszystkie ich podfoldery są **chronione**:
  automatyczne operacje (usuwanie, przenoszenie duplikatów) ich nie ruszają, a ręcznie można usunąć plik tylko
  wtedy, gdy w tym chronionym folderze zostaje inna kopia. Archiwizacja telefonu kopiuje pliki z takich folderów
  zamiast je przenosić.
- Domyślnie pliki są usuwane do **kosza systemowego** (Ustawienia → „usuwaj do kosza”). Po wyłączeniu tej opcji
  usuwanie jest trwałe — aplikacja zawsze o to pyta.
- Operacje na plikach w „Szukaj duplikatów” są zapisywane w dzienniku `logs\operacje-na-plikach.log`
  (gdy włączone w ustawieniach).

**Agent kopii zapasowej**
- Agent działa **dalej po zamknięciu PhotoN** — to celowe. Zatrzymuje się dopiero poleceniem (przycisk,
  `photon-backup --stop`), wylogowaniem lub wyłączeniem komputera. Automatycznie przy logowaniu się nie uruchamia.
- Agent korzysta z plików programu: w wersji wyeksportowanej z folderu programu (`app\`, `runtime\`), przy
  uruchamianiu ze źródeł z `target\classes` i bibliotek w `.m2` / `target\agent-lib`.
  **Przed aktualizacją programu, `mvn clean`, eksportem albo przeniesieniem folderu zatrzymaj agenta** — Windows
  blokuje pliki używane przez działający proces, a nowa wersja zadziała dopiero po ponownym uruchomieniu agenta.
- Ustawienia profilu są wczytywane przy starcie agenta. Po zmianie profilu agenta trzeba zatrzymać i uruchomić.
- Naraz działa tylko jeden agent (pilnuje tego plik blokady `backup\agent.lock`, zwalniany automatycznie,
  gdy proces się zakończy).
- Nie uruchamiaj ręcznej archiwizacji (zakładka „Archiwizacja telefonu”) na tych samych folderach, na których
  pracuje agent.

**Foldery i dyski**
- Telefon podłączony kablem jako „urządzenie” (bez litery dysku, MTP) **nie jest zwykłym folderem** — aplikacja
  go nie zobaczy. Zdjęcia trzeba najpierw skopiować na dysk albo synchronizować programem do wskazanego folderu.
- **Dyski sieciowe** (NAS, `Z:`, `\\serwer\udział`) są obsługiwane, z zabezpieczeniami na wypadek ich
  niedostępności:
  - Gdy archiwum jest niedostępne, nie daje się go w całości odczytać albo nagle nie widać w nim folderów
    z datą (choć wcześniej były), archiwizacja **nic nie przenosi** — pliki zostają w folderze archiwizowanym.
    Bez pełnego spisu archiwum nie da się wykryć duplikatów, więc lepiej poczekać, niż zarchiwizować coś
    drugi raz. Agent pomija wtedy sprawdzenie, pokazuje ostrzeżenie (dziennik, ikona w zasobniku) i sam wraca
    do pracy, gdy dysk znów jest dostępny. Ręczna archiwizacja kończy się komunikatem „Archiwum niedostępne”.
  - Gdy połączenie zerwie się w trakcie, archiwizacja zatrzymuje się po bieżącym pliku. Pliki zarchiwizowane
    wcześniej są już bezpiecznie w archiwum, a reszta zostaje w źródle.
  - Między różnymi dyskami pliki są zawsze kopiowane do pliku tymczasowego `.photon-tmp`, sprawdzane i dopiero
    wtedy oryginał jest usuwany — po zerwaniu połączenia w archiwum nie zostaje niepełny plik.
  - Folder archiwum jest tworzony tylko wtedy, gdy istnieje folder nad nim (np. sam dysk) — przy odłączonym
    dysku nie powstanie „zastępcze” archiwum w innym miejscu.
  - Nieczytelny folder w archiwum (np. brak uprawnień) również wstrzymuje archiwizację — komunikat wskazuje
    który; trzeba to naprawić. Gdy nie da się odczytać pojedynczego pliku archiwum przy porównywaniu, tylko
    pliki źródłowe, które mogłyby być jego duplikatem, zostają w źródle (z błędem w podsumowaniu).
  - Agent sprawdza archiwum tylko wtedy, gdy jest coś do zarchiwizowania; po powrocie dysku wznawia pracę przy
    najbliższym sprawdzeniu (do 15 minut w trybie wolnym albo od razu po pojawieniu się nowego pliku).
  - Litery dysków sieciowych (`Z:`) są przypisane do zalogowanego użytkownika. Dla pewności lepiej wskazywać
    ścieżki `\\serwer\udział\…`.
  - Nieosiągalny dysk sieciowy potrafi blokować operacje na plikach przez kilkadziesiąt sekund — w tym czasie
    okno PhotoN (np. przy wpisywaniu ścieżki) może na chwilę przestać odpowiadać.
- Natychmiastowe powiadomienia o nowych plikach mogą nie działać na dyskach sieciowych — agent wtedy po prostu
  sprawdza folder cyklicznie (widać to w stanie agenta).
- Foldery synchronizowane z chmurą (OneDrive itp.) z plikami „tylko online” mogą być pobierane przy czytaniu
  przez aplikację.

**Inne**
- Miniatury powstają tylko ze zdjęć **JPEG**; inne formaty są pomijane i liczone w podsumowaniu.
- Pliki `.bat` muszą mieć windowsowe końce linii (CRLF). Git na Windows robi to automatycznie; przy edycji
  w innym systemie trzeba o tym pamiętać, inaczej `cmd` źle odczyta skrypt.

---

## 5. Funkcje aplikacji

Menu boczne: Szukaj duplikatów, Archiwizacja (w przygotowaniu), Archiwizacja telefonu, Miniaturyzacja,
Kopia zapasowa, Profile, Ustawienia.

### 5.1. Szukaj duplikatów

**Konfiguracja wyszukiwania**
- **Lokalizacje** — foldery do przeszukania; kolejność na liście to priorytet (ważny dla wyboru oryginału).
  **Wykluczenia** — foldery pomijane. Opcje: podfoldery, pomijanie ukrytych plików i skrótów, „tylko duplikaty
  między różnymi lokalizacjami”.
- **Typy plików** — zdjęcia (JPG, PNG, HEIC, WEBP, GIF, BMP, TIFF, RAW) i filmy (MP4, MOV, M4V, AVI, MKV, MTS,
  3GP, WMV, MPG, WEBM).
- **Metoda rozpoznawania**:
  - *Identyczne pliki* — suma SHA-256, tylko kopie identyczne bajt w bajt (najpewniejsza),
  - *Podobne zdjęcia* — hash percepcyjny z progiem podobieństwa 70–100%; znajduje kopie przeskalowane lub
    zapisane w innym formacie (tylko JPG, PNG, GIF, BMP, TIFF),
  - *Metadane EXIF* — ta sama data i godzina wykonania, aparat i rozdzielczość,
  - *Nazwa i rozmiar* — najszybsza, najmniej dokładna.
- **Filtry** rozmiaru i daty modyfikacji.
- **Oryginał** (plik, który zostaje): najstarszy, najnowszy, najwyższa rozdzielczość/największy, z lokalizacji
  o najwyższym priorytecie albo z najkrótszą ścieżką.
- **Co zrobić z duplikatami**: tylko pokaż wyniki, przenieś do kosza albo przenieś do wskazanego folderu
  (z zachowaniem struktury folderów).
- **Wydajność**: liczba wątków, pamięć sum kontrolnych między skanowaniami (kolejne skanowania tych samych
  plików są dużo szybsze), raport CSV.

**Wyniki**
- Grupy duplikatów z sortowaniem i filtrami (nazwa/ścieżka, typ, rozmiar pliku, łączny rozmiar).
- Etykiety: *Oryginał*, *Folder __* (plik chroniony folderem), *Chroniony* (oznaczony ręcznie),
  *Do usunięcia / Do przeniesienia / Do kosza*.
- **Zaznaczanie wielu plików**: Ctrl+klik dodaje/odejmuje plik, Shift+klik zaznacza zakres. Pasek nad tabelą:
  - *Usuń zaznaczone* (lub klawisz Delete) — zawsze z potwierdzeniem i listą plików; w każdej grupie musi
    zostać co najmniej jeden plik, a pliki chronione i ostatnie kopie w folderach `__` nie są usuwane,
  - *Oznacz jako chronione* — zaznaczone pliki zostają, a wszystkie pozostałe pliki z ich grup (także oryginał)
    zostają zaznaczone do usunięcia/przeniesienia; główny przycisk akcji też to uwzględnia,
  - *Zdejmij ochronę*. Ręczna ochrona obowiązuje tylko w bieżących wynikach.
- Menu kontekstowe: pokaż w folderze, otwórz, kopiuj ścieżkę, usuń pojedynczy plik. Dwuklik — pokaż w folderze.
- Eksport widocznych grup do CSV.

### 5.2. Archiwizacja telefonu

Przenosi nowe zdjęcia i filmy z folderu (np. kopii DCIM z telefonu) do archiwum zorganizowanego w folderach
z datą `RRRR-MM-DD` (np. `2016-02-23`).

- **Foldery**: archiwizowany (źródło) i archiwum. W archiwum brane są pod uwagę tylko podfoldery o nazwie
  w formacie `RRRR-MM-DD`; gdy nie ma żadnego, formularz pokazuje ostrzeżenie, a start prosi o potwierdzenie.
- **Działanie**: każdy plik ze źródła jest porównywany (SHA-256) z plikami w folderach z datą.
  - nie ma go w archiwum → przeniesienie do folderu z **dzisiejszą datą** (oryginalna nazwa i data pliku;
    przy kolizji nazw `IMG (1).jpg` — nic nie jest nadpisywane),
  - jest już w archiwum (także pod inną nazwą) → **duplikat**, zostaje w źródle.
- Brane są tylko zdjęcia i filmy; inne pliki zostają na miejscu.
- **Duplikaty** (sekcja „Duplikaty”): domyślnie *przenieś do folderu zastępczego (śmietnika)* — z zachowaniem
  podfolderów źródła; albo *usuń z folderu archiwizowanego*. W obu przypadkach na końcu pojawia się lista
  i pytanie o potwierdzenie. Kopie w archiwum zawsze zostają.
- **Miniatury** (opcjonalnie): dla każdego zarchiwizowanego zdjęcia JPEG powstaje miniatura w
  `<folder miniatur>\RRRR-MM-DD\` — zasady jak w [Miniaturyzacji](#53-miniaturyzacja).
- Profile (zapisz, zapisz jako, niezapisane zmiany, przywróć domyślne) — jak w innych modułach.
- Profile zapisane przed dodaniem wyboru akcji na duplikatach zachowują dawne zachowanie (usuwanie po potwierdzeniu).

### 5.3. Miniaturyzacja

Tworzy pomniejszone kopie zdjęć JPEG.

- **Foldery**: źródłowy i docelowy (muszą być różne — miniatury mają te same nazwy co oryginały).
  Opcja odtwarzania struktury podfolderów i zastępowania istniejących miniatur.
- **Rozmiar**: dłuższy bok w pikselach (domyślnie 800 px). Mniejsze zdjęcia nie są powiększane — są kopiowane
  bez ponownej kompresji.
- **Jakość**: skalowanie filtrem Lanczos-3, zapis JPEG w jakości 95% bez podpróbkowania kolorów (4:4:4).
- **Metadane**: zachowane EXIF (data, aparat, GPS, orientacja; wymiary poprawione), IPTC, XMP, profil kolorów ICC
  i data modyfikacji pliku. Do XMP dopisywana jest suma SHA-256 oryginału (`photon:OriginalSHA256`), dzięki
  której miniaturę można powiązać z oryginałem.
- Inne formaty (PNG, HEIC, RAW…) są pomijane i liczone w podsumowaniu.

### 5.4. Profile

Wspólna lista profili wszystkich modułów (duplikaty, miniatury, archiwizacja telefonu). Podwójne kliknięcie lub
„Otwórz” przenosi do modułu i wczytuje profil; profile można też usuwać. Profile to pliki JSON
w `%APPDATA%\PhotoN2\profiles\<moduł>\`.

### 5.5. Kopia zapasowa (agent w tle)

Automatyczna archiwizacja według wybranego profilu „Archiwizacji telefonu”, wykonywana przez **agenta** —
osobny, lekki program bez okna (bez JavaFX, z ograniczoną pamięcią). Działa także wtedy, gdy PhotoN jest zamknięty.

**Jak działa**
- Robi to samo co „Archiwizacja telefonu”: przenosi nowe pliki do folderu z dzisiejszą datą, wykrywa duplikaty,
  tworzy miniatury.
- **Tempo**: sprawdza folder co **15 sekund**; po **15 minutach** bez nowych plików — co **15 minut**; gdy coś się
  pojawi, wraca do 15 sekund.
- **Natychmiastowe powiadomienia**: pojawienie się nowego pliku (także po zmianie nazwy, np. `IMG.jpg.part` →
  `IMG.jpg`) budzi agenta po ok. 1,5 s, nawet w trybie 15-minutowym.
- **Pliki w trakcie przesyłania** (np. z internetu) czekają: plik trafia do archiwum dopiero, gdy nie jest pusty,
  przez **30 sekund** nie zmienił rozmiaru ani daty i żaden program nie trzyma go otwartego do zapisu.
- **Duplikaty**: przy akcji „śmietnik” są przenoszone automatycznie; przy akcji „usuń” nic nie jest usuwane
  bez zgody — czekają na przycisk „Usuń zgłoszone duplikaty” (każdy jest zgłaszany tylko raz).
- Niedostępny folder (np. odłączony dysk) — sprawdzenie jest pomijane z wpisem w dzienniku, agent działa dalej.
- Optymalizacje: gdy w źródle nie ma zdjęć ani filmów, archiwum nie jest czytane; spis archiwum jest pamiętany
  i odświeżany najpóźniej co 15 minut; agent ma własną, małą pamięć sum kontrolnych.

**Ikona w zasobniku systemowym** (obok zegara, czasem pod strzałką „^”) — widoczna, dopóki agent działa,
także gdy PhotoN jest zamknięty:
- dymek po najechaniu: profil, ostatnie sprawdzenie, liczba zarchiwizowanych plików, ewentualny problem;
  podczas sprawdzania ikona zmienia kolor na turkusowo-zielony;
- menu pod prawym przyciskiem: *Pokaż stan…*, *Sprawdź teraz*, *Otwórz PhotoN*, *Zatrzymaj kopię zapasową*;
  dwuklik otwiera PhotoN (a przy uruchamianiu ze źródeł — okienko stanu);
- *Pokaż stan…* otwiera okienko w prawym dolnym rogu ekranu: pełny stan, **ostatnie zdarzenia** (do 20 —
  to samo, co trafia do powiadomień) i przyciski *Sprawdź teraz*, *Otwórz PhotoN*, *Zatrzymaj*. Okienko jest
  widoczne zawsze, także gdy Windows ukrywa powiadomienia (tryb „Nie przeszkadzać”, wyłączone powiadomienia);
- powiadomienia w rogu ekranu, gdy pliki zostaną zarchiwizowane, duplikaty przeniesione albo czekają na decyzję
  (wyłącza je ustawienie „powiadomienie po zakończeniu”; ostrzeżenia o błędach pokazują się zawsze). Jeśli się
  nie pokazują, sprawdź *Ustawienia Windows → System → Powiadomienia* („Nie przeszkadzać”, powiadomienia dla
  „Java(TM) Platform SE binary” lub PhotoN) — zdarzenia i tak widać w okienku stanu i w dzienniku;
- *Otwórz PhotoN* działa w wersji wyeksportowanej; przy uruchamianiu ze źródeł jest nieaktywne.

**Jak zatrzymać agenta**, gdy PhotoN jest zamknięty: ikona w zasobniku → *Zatrzymaj kopię zapasową*, albo
`photon-backup --stop`, albo otwórz PhotoN → „Kopia zapasowa” → Zatrzymaj. Agent uruchomiony z PhotoN nie ma okna
konsoli; okno konsoli jest tylko wtedy, gdy uruchomisz go sam przez `photon-backup --profile …`.

**Zakładka w PhotoN** — panel sterowania: Uruchom / Zatrzymaj / Sprawdź teraz / Usuń zgłoszone duplikaty oraz
podgląd (odświeżany co sekundę): stan, PID, od kiedy działa, ostatnie i następne sprawdzenie z bieżącym tempem,
powiadomienia, liczniki, problemy i dziennik.

**Konsola** — w folderze programu `photon-backup.exe`, a przy pracy ze źródeł `photon-backup.bat`:

```
photon-backup --profile "Telefon"   uruchamia agenta w tym oknie (Ctrl+C kończy)
photon-backup --status              stan agenta (także uruchomionego z PhotoN)
photon-backup --stop                zatrzymuje agenta
photon-backup --list                lista profili „Archiwizacji telefonu”
```

`photon-backup.bat` (źródła) najpierw kompiluje kod i kopiuje potrzebne biblioteki (bez JavaFX) do
`target\agent-lib`, potem uruchamia agenta zwykłą Javą; dodatkowe opcje Javy można podać w zmiennej
`PHOTON_BACKUP_OPTS` (np. `-Dphoton.dataDir=D:\inne-dane`). Agent uruchomiony z PhotoN zajmuje ok. 100 MB pamięci (z ikoną w zasobniku).

**Jak PhotoN rozmawia z agentem** (pliki w `%APPDATA%\PhotoN2`):

| Plik | Rola |
|---|---|
| `backup\agent.lock` | Trzymany przez działającego agenta — jedna kopia naraz; po nim PhotoN poznaje, że agent żyje. |
| `backup\status.json` | Stan agenta, zapisywany po każdym sprawdzeniu i co 30 s. |
| `backup\commands\*.json` | Polecenia (zatrzymaj, sprawdź teraz, usuń duplikaty); agent wykonuje je i usuwa. Polecenia sprzed startu agenta są pomijane. |
| `logs\kopia-zapasowa.log` | Dziennik zdarzeń (przy starcie agenta przenoszony do `.1`, gdy przekroczy 5 MB). |
| `logs\backup-agent.out` | Wyjście procesu agenta uruchomionego z PhotoN — tu szukać przyczyny, gdy agent się nie uruchamia. |

### 5.6. Ustawienia

- Ekran startowy (powitanie, ostatnia sekcja, wybrany moduł), zapamiętywanie rozmiaru i położenia okna,
  pytanie przed zamknięciem.
- Foldery pamięci podręcznej i raportów.
- Bezpieczeństwo plików: potwierdzanie operacji, usuwanie do kosza, dziennik operacji.
- Wydajność: domyślna liczba wątków, niski priorytet skanowania (dotyczy też agenta kopii zapasowej).
- *Powiadomienie po zakończeniu* — włącza powiadomienia agenta kopii zapasowej w rogu ekranu (odczytywane
  przy starcie agenta).
- Ustawienia *limit pamięci podręcznej*, *dźwięk po zakończeniu* i *poziom szczegółowości dziennika* są
  zapisywane, ale **jeszcze nie są wykorzystywane** przez aplikację.

### 5.6a. Serwery zdalne (FTP, FTPS, SFTP)

Foldery mogą leżeć na serwerze: w **Szukaj duplikatów**, **Archiwizacji telefonu** (folder archiwizowany,
archiwum, śmietnik na duplikaty, miniatury), **Miniaturyzacji** i — przez profil archiwizacji — w **Kopii
zapasowej**.

**Konfiguracja (jedno miejsce dla całej aplikacji):** Ustawienia → *Serwery zdalne*.
- *Dodaj serwer* → nazwa połączenia (np. `NAS`), protokół, adres, port (puste = domyślny), użytkownik, hasło.
- Protokoły: **FTP** (bez szyfrowania), **FTPS jawny** (FTPES, port 21), **FTPS niejawny** (port 990),
  **SFTP** (SSH, port 22). Dla FTP/FTPS można wyłączyć tryb pasywny (domyślnie włączony — działa za routerem).
- **Testuj połączenie** łączy się, loguje i odczytuje wskazany folder; pokazuje czas i liczbę pozycji.
- **Zapisz połączenie** — zmiany połączeń zapisują się od razu (niezależnie od przycisku „Zapisz” ustawień).
  Otwarte połączenia ze zmienionym serwerem są zamykane.
- SFTP: klucz serwera jest **zapamiętywany przy pierwszym połączeniu**. Jeśli później się zmieni, PhotoN
  odmówi połączenia (zanim wyśle hasło) — to może oznaczać podszywanie się pod serwer. Po reinstalacji
  serwera kliknij *Zapomnij klucz* i zapisz połączenie.
- FTPS: certyfikat serwera nie jest weryfikowany (sprawdzana jest tylko data ważności) — NAS-y zwykle mają
  certyfikaty samopodpisane. Szyfrowanie chroni przed podsłuchem, ale nie przed podszyciem się pod serwer.
- **Hasła są zapisywane zwykłym tekstem** w `servers.json` w folderze danych PhotoN.

**W zakładkach** przy polu folderu pojawia się lista *Ten komputer / nazwa połączenia* (gdy zdefiniowano
co najmniej jeden serwer). Po wybraniu połączenia wpisujesz folder na serwerze (np. `/zdjecia/telefon`)
albo klikasz *Wybierz…*, by przejrzeć foldery serwera. W „Szukaj duplikatów” foldery z serwera dodaje
przycisk *Z serwera…*. W profilach folder zapisuje się jako `[NAS]/zdjecia/telefon` — dlatego po zmianie
nazwy połączenia profile trzeba poprawić (można też wkleić taką pełną ścieżkę do pola folderu).

**Jak to działa i na co uważać:**
- Porównywanie plików (SHA-256, podobieństwo, metadane) wymaga **pobrania pliku z serwera**. Pierwsze
  przeszukanie dużego archiwum na serwerze trwa więc tyle, ile pobranie całego archiwum; kolejne są szybkie
  dzięki pamięci sum (gdy rozmiar i data pliku się nie zmieniły). Przy archiwizacji pobierane są tylko pliki
  archiwum o takim samym rozmiarze jak nowe zdjęcia.
- Przenoszenie w obrębie jednego serwera to zmiana nazwy (szybka). Między serwerem a dyskiem albo dwoma
  serwerami plik jest kopiowany przez plik tymczasowy `.photon-tmp`, sprawdzany i dopiero wtedy usuwany
  ze źródła.
- **Serwer nie ma kosza** — pliki na serwerze są usuwane trwale, także gdy w ustawieniach włączone jest
  usuwanie do kosza (dziennik operacji zapisuje „USUNIĘTO (serwer — bez kosza)”).
- Data modyfikacji jest przenoszona na serwer, jeśli serwer na to pozwala (FTP: polecenie MFMT, SFTP: zawsze).
- Niedostępny serwer działa jak odłączony dysk sieciowy (patrz [4](#4-na-co-zwracać-uwagę)): archiwizacja nic
  nie przenosi i czeka. Po nieudanej próbie połączenia kolejne przez 30 s od razu zgłaszają ten sam błąd,
  zamiast za każdym razem czekać do 15 s na odpowiedź — ale pierwsza próba może na chwilę zatrzymać okno.
- Agent kopii zapasowej z folderem źródłowym na serwerze: serwer nie powiadamia o nowych plikach, więc działa
  tylko sprawdzanie cykliczne (co 15 s, a po 15 minutach ciszy co 15 minut). Plik jest uznawany za w pełni
  przesłany, gdy jego rozmiar i data nie zmieniają się przez 30 s (blokady pliku nie da się sprawdzić na serwerze).
- *Otwórz folder* w Eksploratorze nie działa dla folderów na serwerze (pojawia się komunikat). Otwarcie
  pliku z serwera (np. w wynikach duplikatów) najpierw pobiera go do folderu tymczasowego.
- Ukryte pliki i foldery: na serwerze ukryte są tylko nazwy zaczynające się od kropki.

### 5.7. Archiwizacja

Moduł w przygotowaniu (na razie tylko ekran informacyjny).

---

## 6. Gdzie aplikacja trzyma dane

Folder danych: `%APPDATA%\PhotoN2` (np. `C:\Users\<użytkownik>\AppData\Roaming\PhotoN2`).
Można go zmienić właściwością Javy `-Dphoton.dataDir=<folder>` (używają jej testy).

| Ścieżka | Zawartość |
|---|---|
| `settings.json` | Ustawienia aplikacji |
| `servers.json` | Połączenia z serwerami FTP/FTPS/SFTP — **z hasłami zapisanymi zwykłym tekstem** |
| `state.json` | Rozmiar i położenie okna, ostatnia sekcja |
| `profiles\duplicates\`, `profiles\thumbnails\`, `profiles\phone-archive\` | Profile modułów |
| `thumbnails.json`, `phone-archive.json` | Ostatnio użyte ustawienia formularzy |
| `cache\hashes\` | Pamięć sum kontrolnych (można usunąć — odbuduje się) |
| `logs\` | Dzienniki: operacje na plikach, kopia zapasowa, wyjście agenta |
| `backup\` | Komunikacja z agentem kopii zapasowej (patrz [5.5](#55-kopia-zapasowa-agent-w-tle)) |

Raporty CSV domyślnie trafiają do `Dokumenty\PhotoN Raporty`.

---

## 7. Testy

- `testy.bat` albo `mvn test` — wszystkie testy (logika, interfejs, agent jako osobny proces).
- Testy interfejsu działają **bez ekranu** (Monocle) — nie przejmują myszy ani klawiatury.
  `mvn test -Dgui` uruchamia je w prawdziwych oknach (wtedy nie ruszaj myszy).
- Testy używają własnego folderu danych `target\test-data` — nie dotykają prawdziwych danych w `%APPDATA%`.
