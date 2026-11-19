package com.photon.backup;

import com.photon.phonearchive.PhoneArchiver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decyzja, kiedy agent sprawdzi folder — bez wątków i czekania. */
class CheckSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final Duration FAST = Duration.ofSeconds(15);

    private final CheckPacer pacer = new CheckPacer(FAST, Duration.ofMinutes(60), Duration.ofMinutes(15),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void idleWithNotificationsWaitsForEventsWithSafetyCheck() {
        CheckScheduler scheduler = new CheckScheduler(pacer, Duration.ofHours(1));
        CheckScheduler.Plan plan = scheduler.afterCheck(report(0, null), true, null, NOW);
        assertEquals(NOW.plus(Duration.ofHours(1)), plan.deadline());
        assertTrue(scheduler.eventDriven());
        assertTrue(plan.logMessage().startsWith("Czekam na nowe pliki"));
        assertEquals(3600, scheduler.intervalSeconds(true));

        CheckScheduler.Plan again = scheduler.afterCheck(report(0, null), true, null, NOW);
        assertNull(again.logMessage(), "tryb się nie zmienił — bez wpisu w dzienniku");
    }

    @Test
    void withoutSafetyCheckThereIsNoDeadline() {
        CheckScheduler scheduler = new CheckScheduler(pacer, null);
        assertNull(scheduler.afterCheck(report(0, null), true, null, NOW).deadline());
        assertEquals(0, scheduler.intervalSeconds(true));
    }

    @Test
    void uploadsProblemsAndMissingNotificationsMeanPolling() {
        CheckScheduler scheduler = new CheckScheduler(pacer, Duration.ofHours(1));
        scheduler.afterCheck(report(0, null), true, null, NOW);

        CheckScheduler.Plan uploading = scheduler.afterCheck(report(2, null), true, null, NOW);
        assertEquals(NOW.plus(FAST), uploading.deadline());
        assertFalse(scheduler.eventDriven());
        assertTrue(uploading.logMessage().startsWith("Plik w trakcie przesyłania"));

        assertEquals(NOW.plus(FAST), scheduler.afterCheck(report(0, "dysk odłączony"), true, null, NOW).deadline());
        assertEquals(NOW.plus(FAST), scheduler.afterCheck(report(0, null), false, null, NOW).deadline(),
                "bez powiadomień (np. serwer FTP)");
    }

    @Test
    void sessionEndNeedsOneMoreCheck() {
        CheckScheduler scheduler = new CheckScheduler(pacer, Duration.ofHours(1));
        Instant sessionEnd = NOW.plus(Duration.ofMinutes(15));
        assertEquals(sessionEnd, scheduler.afterCheck(report(0, null), true, sessionEnd, NOW).deadline());
        assertEquals(NOW.plus(FAST), scheduler.afterCheck(report(0, null), true, NOW.minusSeconds(1), NOW).deadline(),
                "koniec sesji już minął — sprawdzenie za chwilę");
    }

    private static ArchiveWatcher.CycleReport report(int waiting, String problem) {
        PhoneArchiver.Summary summary = problem != null ? null : new PhoneArchiver.Summary(List.of(), List.of(),
                List.of(), 1, List.of(), 0, Path.of("archiwum", "2026-11-19"), Duration.ZERO,
                PhoneArchiver.ThumbnailStats.NONE, waiting, List.of());
        return new ArchiveWatcher.CycleReport(NOW, summary, null, List.of(), problem, waiting, List.of());
    }
}
