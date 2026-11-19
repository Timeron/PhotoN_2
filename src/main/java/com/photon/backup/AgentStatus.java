package com.photon.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Stan agenta kopii zapasowej zapisywany do {@code backup/status.json} (czytany przez PhotoN i konsolę).
 *
 * @param state             RUNNING albo STOPPED (ostatni zapisany stan — o tym, czy proces żyje, decyduje blokada)
 * @param heartbeat         chwila zapisu stanu (stan jest zapisywany tylko przy zmianach — o tym, czy agent
 *                          działa, decyduje blokada pliku)
 * @param intervalSeconds   bieżący odstęp między sprawdzeniami (przy {@code eventDriven} — odstęp kontrolnego
 *                          sprawdzania, 0 = wyłączone)
 * @param slowMode          czy po dłuższej ciszy sprawdzanie zwolniło
 * @param notifications     czy działają natychmiastowe powiadomienia o nowych plikach
 * @param checking          czy właśnie trwa sprawdzanie
 * @param pendingDuplicates duplikaty czekające na decyzję o usunięciu
 * @param lastProblem       ostatni problem (np. niedostępny folder) albo null
 * @param eventDriven       agent czeka na powiadomienie o nowym pliku (bez cyklicznego sprawdzania; najwyżej
 *                          sprawdzenie kontrolne o {@code nextCheck})
 * @param session           trwająca sesja (postęp) albo null
 * @param lastSession       podsumowanie ostatniej zakończonej sesji albo null
 * @param stalled           pliki, których przesyłanie stoi (niekompletne i bez zmian od dłuższego czasu)
 */
public record AgentStatus(State state, long pid, String profile, Path source, Path target,
                          Instant startedAt, Instant heartbeat, Instant lastCheck, Instant nextCheck,
                          long intervalSeconds, boolean slowMode, boolean notifications, boolean checking,
                          int archived, int thumbnails, int movedDuplicates, int errors, int waiting,
                          List<PendingDuplicate> pendingDuplicates, String lastProblem, Instant stoppedAt,
                          boolean eventDriven, Session session, String lastSession, List<Path> stalled) {

    /**
     * Postęp trwającej sesji (od pierwszego nowego pliku).
     *
     * @param waiting pliki w trakcie przesyłania
     */
    public record Session(Instant startedAt, Instant lastActivity, int archived, long bytes, int thumbnails,
                          int duplicates, int errors, int waiting) {

        /** Tekst postępu, np. „od 13:05 — zarchiwizowano 120 plików (1,2 GB), czeka 15, 12 plików/min”. */
        public String describe(java.time.format.DateTimeFormatter time, Instant now) {
            double minutes = Math.max(1, java.time.Duration.between(startedAt, now).toSeconds()) / 60.0;
            String rate = archived == 0 ? "" : String.format(java.util.Locale.ROOT, ", %.1f plików/min",
                    archived / minutes).replace('.', ',');
            return "od " + time.format(startedAt) + " — zarchiwizowano " + com.photon.ui.Texts.count(archived,
                    "plik", "pliki", "plików") + " (" + com.photon.ui.Texts.formatBytes(bytes) + ")"
                    + ", czeka na przesłanie " + waiting + (duplicates > 0 ? ", duplikaty " + duplicates : "")
                    + (errors > 0 ? ", błędy " + errors : "") + rate;
        }
    }

    public enum State { RUNNING, STOPPED }

    public record PendingDuplicate(Path source, Path existing, long size) {
    }

    public AgentStatus {
        pendingDuplicates = pendingDuplicates == null ? List.of() : List.copyOf(pendingDuplicates);
        stalled = stalled == null ? List.of() : List.copyOf(stalled);
    }

    /** Sesja: postęp trwającej albo podsumowanie ostatniej (np. do PhotoN i ikony). */
    public String sessionText(java.time.format.DateTimeFormatter time) {
        if (session != null) {
            return session.describe(time, java.time.Instant.now());
        }
        return lastSession == null ? "—" : "ostatnia: " + lastSession;
    }

    /** Kiedy następne sprawdzenie — tekst do PhotoN, ikony i konsoli (np. „przy nowym pliku; kontrolnie o 14:05”). */
    public String nextCheckText(java.time.format.DateTimeFormatter time) {
        if (eventDriven) {
            return "przy nowym pliku (powiadomienie Windows)" + (nextCheck == null
                    ? "; bez sprawdzania kontrolnego"
                    : "; kontrolnie o " + time.format(nextCheck) + " (co "
                    + AgentLoop.describe(java.time.Duration.ofSeconds(intervalSeconds)) + ")");
        }
        if (nextCheck == null) {
            return "—";
        }
        return time.format(nextCheck) + "  (co " + AgentLoop.describe(java.time.Duration.ofSeconds(intervalSeconds))
                + (slowMode ? " — brak nowych plików od 15 minut" : "") + ")";
    }
}
