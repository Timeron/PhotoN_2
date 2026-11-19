package com.photon.backup;

import com.photon.Json;
import com.photon.JsonFileStore;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Ustawienia agenta kopii zapasowej ({@code backup/agent-settings.json}), zmieniane w zakładce „Kopia zapasowa”
 * i wczytywane przy starcie agenta.
 *
 * @param safetyCheckMinutes co ile minut sprawdzać folder, gdy nic się nie dzieje (0 = bez kontrolnego
 *                           sprawdzania, gdy działają powiadomienia o nowych plikach)
 * @param quietSeconds       jak długo plik musi się nie zmieniać, by uznać przesyłanie za zakończone
 *                           (dłużej przy wolnym internecie — telefon potrafi przerwać wysyłanie)
 */
public record AgentSettings(int safetyCheckMinutes, int quietSeconds) {

    /** Dostępne okresy spokoju (sekundy). */
    public static final List<Integer> QUIET_CHOICES = List.of(30, 120, 300);

    public AgentSettings {
        if (quietSeconds <= 0) {
            quietSeconds = 120;
        }
    }

    /** Dostępne odstępy kontrolnego sprawdzania (minuty); 0 = wyłączone. */
    public static final List<Integer> CHOICES = List.of(15, 60, 360, 0);

    /** Odstęp sprawdzania bez powiadomień, gdy kontrolne sprawdzanie jest wyłączone (nie da się go wyłączyć). */
    static final Duration POLLING_WHEN_DISABLED = Duration.ofHours(1);

    public static AgentSettings defaults() {
        return new AgentSettings(60, 120);
    }

    /** Odstęp kontrolnego sprawdzania albo null (wyłączone). */
    public Duration safetyCheck() {
        return safetyCheckMinutes > 0 ? Duration.ofMinutes(safetyCheckMinutes) : null;
    }

    /** Odstęp sprawdzania po dłuższej ciszy, gdy powiadomienia nie działają (serwer, część dysków sieciowych). */
    public Duration slowPolling() {
        return safetyCheckMinutes > 0 ? Duration.ofMinutes(safetyCheckMinutes) : POLLING_WHEN_DISABLED;
    }

    public Duration quietPeriod() {
        return Duration.ofSeconds(quietSeconds);
    }

    public AgentSettings withSafetyCheckMinutes(int minutes) {
        return new AgentSettings(minutes, quietSeconds);
    }

    public AgentSettings withQuietSeconds(int seconds) {
        return new AgentSettings(safetyCheckMinutes, seconds);
    }

    public static String quietLabel(int seconds) {
        return switch (seconds) {
            case 30 -> "30 sekund (szybkie łącze)";
            case 120 -> "2 minuty (zalecane przy wolnym internecie)";
            case 300 -> "5 minut (bardzo wolne albo niestabilne łącze)";
            default -> seconds + " s";
        };
    }

    public static String label(int minutes) {
        return switch (minutes) {
            case 0 -> "wyłączone — tylko powiadomienia o nowych plikach";
            case 60 -> "co godzinę (zalecane)";
            case 360 -> "co 6 godzin";
            default -> "co " + minutes + " minut";
        };
    }

    public static AgentSettings load(AgentFiles files) {
        return new JsonFileStore<>(files.settings(), AgentSettings.class, defaults()).load();
    }

    public void save(AgentFiles files) throws IOException {
        Json.writeAtomically(files.settings(), this);
    }
}
