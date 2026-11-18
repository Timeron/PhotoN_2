package com.photon.backup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Tempo sprawdzania folderu: często, dopóki coś się dzieje, rzadko po dłuższej ciszy.
 * <ul>
 *   <li>domyślnie co {@code fast} (15 s),</li>
 *   <li>gdy przez {@code idleAfter} (15 min) nie pojawiło się nic nowego — co {@code slow} (15 min),</li>
 *   <li>gdy coś się pojawi (także plik w trakcie przesyłania) — z powrotem co {@code fast}.</li>
 * </ul>
 * Używany z jednego wątku (wątku archiwizacji w tle); stan do wyświetlenia jest {@code volatile}.
 */
public class CheckPacer {

    public static final Duration FAST = Duration.ofSeconds(15);
    public static final Duration SLOW = Duration.ofMinutes(15);
    public static final Duration IDLE_AFTER = Duration.ofMinutes(15);

    /** Zmiana tempa po przebiegu (do dziennika). */
    public enum Change { NONE, SLOWED_DOWN, SPED_UP }

    private final Duration fast;
    private final Duration slow;
    private final Duration idleAfter;
    private final Clock clock;
    private volatile Instant lastActivity;
    private volatile boolean slowMode;
    private volatile Change lastChange = Change.NONE;

    public CheckPacer(Duration fast, Duration slow, Duration idleAfter, Clock clock) {
        this.fast = fast;
        this.slow = slow;
        this.idleAfter = idleAfter;
        this.clock = clock;
        this.lastActivity = clock.instant();
    }

    /**
     * Uwzględnia wynik przebiegu i zwraca odstęp do następnego sprawdzenia.
     *
     * @param activity czy w przebiegu pojawiło się coś nowego
     */
    public Duration afterCycle(boolean activity) {
        Instant now = clock.instant();
        boolean wasSlow = slowMode;
        if (activity) {
            lastActivity = now;
            slowMode = false;
        } else if (Duration.between(lastActivity, now).compareTo(idleAfter) >= 0) {
            slowMode = true;
        }
        lastChange = wasSlow == slowMode ? Change.NONE : (slowMode ? Change.SLOWED_DOWN : Change.SPED_UP);
        return currentInterval();
    }

    public Duration currentInterval() {
        return slowMode ? slow : fast;
    }

    public boolean isSlow() {
        return slowMode;
    }

    public Change lastChange() {
        return lastChange;
    }

    public Duration fast() {
        return fast;
    }

    public Duration slow() {
        return slow;
    }

    public Duration idleAfter() {
        return idleAfter;
    }
}
