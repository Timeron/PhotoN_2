package com.photon.backup;

import java.time.Duration;
import java.time.Instant;

/**
 * Kiedy agent ma sprawdzić folder następnym razem — sama decyzja, bez wątków i czekania (łatwa do testowania).
 * <ul>
 *   <li>działają powiadomienia o nowych plikach, nic nie czeka na przesłanie i nie ma problemu → agent śpi
 *       do powiadomienia, najwyżej z kontrolnym sprawdzeniem ({@code safetyCheck}),</li>
 *   <li>w przeciwnym razie — tempo z {@link CheckPacer} (15 s, po dłuższej ciszy wolniej),</li>
 *   <li>trwająca sesja wymaga jeszcze jednego sprawdzenia w chwili, gdy ma się zakończyć.</li>
 * </ul>
 */
final class CheckScheduler {

    /**
     * Decyzja po sprawdzeniu.
     *
     * @param deadline   termin następnego sprawdzenia albo null (tylko powiadomienia)
     * @param logMessage wpis do dziennika o zmianie trybu albo null
     */
    record Plan(Instant deadline, String logMessage) {
    }

    private final CheckPacer pacer;
    private final Duration safetyCheck;
    private boolean eventDriven;

    /** @param safetyCheck kontrolne sprawdzenie przy działających powiadomieniach (null = wyłączone) */
    CheckScheduler(CheckPacer pacer, Duration safetyCheck) {
        this.pacer = pacer;
        this.safetyCheck = safetyCheck;
    }

    /**
     * @param watcherActive czy działają powiadomienia o nowych plikach
     * @param sessionEnd    kiedy kończy się trwająca sesja (null = brak sesji)
     */
    Plan afterCheck(ArchiveWatcher.CycleReport report, boolean watcherActive, Instant sessionEnd, Instant now) {
        Duration next = pacer.afterCycle(report.activity());
        boolean idleWithEvents = watcherActive && report.problem() == null && report.waiting() == 0;
        String message = null;
        Instant deadline;
        if (idleWithEvents) {
            if (!eventDriven) {
                message = "Czekam na nowe pliki (powiadomienia Windows)" + (safetyCheck == null
                        ? ", bez kontrolnego sprawdzania." : "; kontrolnie co " + AgentTexts.describe(safetyCheck) + ".");
            }
            deadline = safetyCheck == null ? null : now.plus(safetyCheck);
        } else {
            if (eventDriven && report.waiting() > 0) {
                message = "Plik w trakcie przesyłania — sprawdzanie co " + AgentTexts.describe(next) + ", aż się prześle.";
            } else if (!eventDriven && pacer.lastChange() == CheckPacer.Change.SLOWED_DOWN) {
                message = "Brak nowych plików od " + AgentTexts.describe(pacer.idleAfter()) + " — sprawdzanie co "
                        + AgentTexts.describe(pacer.slow()) + ".";
            } else if (!eventDriven && pacer.lastChange() == CheckPacer.Change.SPED_UP) {
                message = "Pojawiły się nowe pliki — sprawdzanie znowu co " + AgentTexts.describe(pacer.fast()) + ".";
            }
            deadline = now.plus(next);
        }
        eventDriven = idleWithEvents;
        if (sessionEnd != null && (deadline == null || sessionEnd.isBefore(deadline))) {
            deadline = sessionEnd.isAfter(now) ? sessionEnd : now.plus(pacer.fast());
        }
        return new Plan(deadline, message);
    }

    /** Czy agent czeka na powiadomienie (bez cyklicznego sprawdzania). */
    boolean eventDriven() {
        return eventDriven;
    }

    /** Odstęp do pokazania w stanie: kontrolny w trybie zdarzeń (0 = wyłączony), inaczej bieżące tempo. */
    long intervalSeconds(boolean waitingForEvents) {
        if (waitingForEvents) {
            return safetyCheck == null ? 0 : safetyCheck.toSeconds();
        }
        return pacer.currentInterval().toSeconds();
    }

    boolean slow() {
        return pacer.isSlow();
    }

    /** Opis tempa do dziennika przy starcie agenta. */
    String startupDescription() {
        return "Nowy plik budzi agenta od razu (powiadomienia Windows), kontrolne sprawdzenie: "
                + (safetyCheck == null ? "wyłączone" : "co " + AgentTexts.describe(safetyCheck))
                + ". Bez powiadomień — co " + AgentTexts.describe(pacer.fast()) + ", a po "
                + AgentTexts.describe(pacer.idleAfter()) + " ciszy co " + AgentTexts.describe(pacer.slow()) + ".";
    }
}
