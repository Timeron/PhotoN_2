package com.photon.backup;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckPacerTest {

    private final ArchiveWatcherTest.MutableClock clock =
            new ArchiveWatcherTest.MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
    private final CheckPacer pacer = new CheckPacer(CheckPacer.FAST, CheckPacer.SLOW, CheckPacer.IDLE_AFTER, clock);

    @Test
    void checksEvery15SecondsThenSlowsDownAfter15QuietMinutes() {
        assertEquals(Duration.ofSeconds(15), pacer.currentInterval());

        // 14 min 45 s ciszy — wciąż szybko.
        for (int i = 0; i < 59; i++) {
            clock.advance(Duration.ofSeconds(15));
            assertEquals(Duration.ofSeconds(15), pacer.afterCycle(false));
        }
        assertFalse(pacer.isSlow());

        // Pełne 15 minut bez nowych plików — co 15 minut.
        clock.advance(Duration.ofSeconds(15));
        assertEquals(Duration.ofMinutes(15), pacer.afterCycle(false));
        assertTrue(pacer.isSlow());
        assertEquals(CheckPacer.Change.SLOWED_DOWN, pacer.lastChange());

        clock.advance(Duration.ofMinutes(15));
        assertEquals(Duration.ofMinutes(15), pacer.afterCycle(false));
        assertEquals(CheckPacer.Change.NONE, pacer.lastChange());
    }

    @Test
    void speedsUpAgainWhenFilesAppear() {
        clock.advance(Duration.ofMinutes(15));
        pacer.afterCycle(false);
        assertTrue(pacer.isSlow());

        clock.advance(Duration.ofMinutes(15));
        assertEquals(Duration.ofSeconds(15), pacer.afterCycle(true));
        assertEquals(CheckPacer.Change.SPED_UP, pacer.lastChange());

        // Cisza liczy się od ostatniej aktywności, nie od startu.
        clock.advance(Duration.ofMinutes(14));
        assertEquals(Duration.ofSeconds(15), pacer.afterCycle(false));
        clock.advance(Duration.ofMinutes(1));
        assertEquals(Duration.ofMinutes(15), pacer.afterCycle(false));
    }
}
