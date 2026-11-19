package com.photon.backup;

import com.photon.ui.Texts;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stan agenta kopii zapasowej zapisywany do {@code backup/status.json} (czytany przez PhotoN i konsolę).
 * Agent może obsługiwać kilka profili naraz — każdy ma własną część stanu ({@link ProfileStatus}).
 *
 * @param state    RUNNING albo STOPPED (ostatni zapisany stan — o tym, czy proces żyje, decyduje blokada)
 * @param profiles stan każdego obsługiwanego profilu, w kolejności podanej przy uruchomieniu
 */
public record AgentStatus(State state, long pid, Instant startedAt, Instant stoppedAt, List<ProfileStatus> profiles) {

    public enum State { RUNNING, STOPPED }

    public AgentStatus {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    /**
     * Stan jednego profilu.
     *
     * @param schedule          kiedy agent sprawdzał i kiedy sprawdzi folder tego profilu
     * @param pendingDuplicates duplikaty czekające na decyzję o usunięciu
     * @param lastProblem       ostatni problem (np. niedostępny folder) albo null
     * @param session           trwająca sesja (postęp) albo null
     * @param lastSession       podsumowanie ostatniej zakończonej sesji albo null
     * @param stalled           pliki, których przesyłanie stoi (niekompletne i bez zmian od dłuższego czasu)
     */
    public record ProfileStatus(String profile, Path source, Path target, Schedule schedule,
                                int archived, int thumbnails, int movedDuplicates, int errors, int waiting,
                                List<PendingDuplicate> pendingDuplicates, String lastProblem,
                                Session session, String lastSession, List<Path> stalled) {

        public ProfileStatus {
            schedule = schedule == null ? Schedule.NONE : schedule;
            pendingDuplicates = pendingDuplicates == null ? List.of() : List.copyOf(pendingDuplicates);
            stalled = stalled == null ? List.of() : List.copyOf(stalled);
        }

        /** Sesja: postęp trwającej albo podsumowanie ostatniej (np. do PhotoN i ikony). */
        public String sessionText(DateTimeFormatter time) {
            if (session != null) {
                return session.describe(time, Instant.now());
            }
            return lastSession == null ? "—" : "ostatnia: " + lastSession;
        }
    }

    public record PendingDuplicate(Path source, Path existing, long size) {
    }

    /**
     * Harmonogram sprawdzania.
     *
     * @param intervalSeconds bieżący odstęp między sprawdzeniami (przy {@code eventDriven} — odstęp kontrolnego
     *                        sprawdzania, 0 = wyłączone)
     * @param slowMode        czy po dłuższej ciszy sprawdzanie zwolniło
     * @param notifications   czy działają natychmiastowe powiadomienia o nowych plikach
     * @param checking        czy właśnie trwa sprawdzanie
     * @param eventDriven     agent czeka na powiadomienie o nowym pliku (bez cyklicznego sprawdzania; najwyżej
     *                        sprawdzenie kontrolne o {@code nextCheck})
     */
    public record Schedule(Instant lastCheck, Instant nextCheck, long intervalSeconds, boolean slowMode,
                           boolean notifications, boolean checking, boolean eventDriven) {

        public static final Schedule NONE = new Schedule(null, null, 0, false, false, false, false);

        /** Kiedy następne sprawdzenie — tekst do PhotoN, ikony i konsoli (np. „przy nowym pliku; kontrolnie o 14:05”). */
        public String nextCheckText(DateTimeFormatter time) {
            if (eventDriven) {
                return "przy nowym pliku (powiadomienie Windows)" + (nextCheck == null
                        ? "; bez sprawdzania kontrolnego"
                        : "; kontrolnie o " + time.format(nextCheck) + " (co "
                        + AgentTexts.describe(Duration.ofSeconds(intervalSeconds)) + ")");
            }
            if (nextCheck == null) {
                return "—";
            }
            return time.format(nextCheck) + "  (co " + AgentTexts.describe(Duration.ofSeconds(intervalSeconds))
                    + (slowMode ? " — brak nowych plików od 15 minut" : "") + ")";
        }
    }

    /**
     * Postęp trwającej sesji (od pierwszego nowego pliku).
     *
     * @param waiting pliki w trakcie przesyłania
     */
    public record Session(Instant startedAt, Instant lastActivity, int archived, long bytes, int thumbnails,
                          int duplicates, int errors, int waiting) {

        /** Tekst postępu, np. „od 13:05 — zarchiwizowano 120 plików (1,2 GB), czeka 15, 12 plików/min”. */
        public String describe(DateTimeFormatter time, Instant now) {
            double minutes = Math.max(1, Duration.between(startedAt, now).toSeconds()) / 60.0;
            String rate = archived == 0 ? "" : String.format(Locale.ROOT, ", %.1f plików/min",
                    archived / minutes).replace('.', ',');
            return "od " + time.format(startedAt) + " — zarchiwizowano " + Texts.count(archived,
                    "plik", "pliki", "plików") + " (" + Texts.formatBytes(bytes) + ")"
                    + ", czeka na przesłanie " + waiting + (duplicates > 0 ? ", duplikaty " + duplicates : "")
                    + (errors > 0 ? ", błędy " + errors : "") + rate;
        }
    }

    // =====================================================================
    // Zestawienia dla wszystkich profili
    // =====================================================================

    /** Nazwy profili, np. „Telefon”, „Tablet”. */
    public String profileNames() {
        return profiles.stream().map(p -> "„" + p.profile() + "”").collect(Collectors.joining(", "));
    }

    /** Stan profilu o tej nazwie albo null. */
    public ProfileStatus profile(String name) {
        return profiles.stream().filter(p -> p.profile().equals(name)).findFirst().orElse(null);
    }

    /** Czy właśnie trwa sprawdzanie któregoś profilu. */
    public boolean checking() {
        return profiles.stream().anyMatch(p -> p.schedule().checking());
    }

    public int archived() {
        return profiles.stream().mapToInt(ProfileStatus::archived).sum();
    }

    /** Duplikaty do decyzji ze wszystkich profili. */
    public List<PendingDuplicate> pendingDuplicates() {
        return profiles.stream().flatMap(p -> p.pendingDuplicates().stream()).toList();
    }

    /** Problemy profili (np. niedostępny folder). */
    public List<String> problems() {
        return profiles.stream().filter(p -> p.lastProblem() != null)
                .map(p -> (profiles.size() > 1 ? p.profile() + ": " : "") + p.lastProblem()).toList();
    }

    /** Ostatnie sprawdzenie któregokolwiek profilu. */
    public Instant lastCheck() {
        return profiles.stream().map(p -> p.schedule().lastCheck()).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }
}
