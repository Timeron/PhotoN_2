package com.photon.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Stan agenta kopii zapasowej zapisywany do {@code backup/status.json} (czytany przez PhotoN i konsolę).
 *
 * @param state             RUNNING albo STOPPED (ostatni zapisany stan — o tym, czy proces żyje, decyduje blokada)
 * @param heartbeat         ostatni zapis stanu (co najwyżej co kilkadziesiąt sekund, także w trakcie czekania)
 * @param intervalSeconds   bieżący odstęp między sprawdzeniami
 * @param slowMode          czy po dłuższej ciszy sprawdzanie zwolniło
 * @param notifications     czy działają natychmiastowe powiadomienia o nowych plikach
 * @param checking          czy właśnie trwa sprawdzanie
 * @param pendingDuplicates duplikaty czekające na decyzję o usunięciu
 * @param lastProblem       ostatni problem (np. niedostępny folder) albo null
 */
public record AgentStatus(State state, long pid, String profile, Path source, Path target,
                          Instant startedAt, Instant heartbeat, Instant lastCheck, Instant nextCheck,
                          long intervalSeconds, boolean slowMode, boolean notifications, boolean checking,
                          int archived, int thumbnails, int movedDuplicates, int errors, int waiting,
                          List<PendingDuplicate> pendingDuplicates, String lastProblem, Instant stoppedAt) {

    public enum State { RUNNING, STOPPED }

    public record PendingDuplicate(Path source, Path existing, long size) {
    }

    public AgentStatus {
        pendingDuplicates = pendingDuplicates == null ? List.of() : List.copyOf(pendingDuplicates);
    }
}
