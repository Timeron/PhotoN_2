package com.photon.profiles;

import java.time.Instant;

/**
 * Nazwany, zapisany zestaw ustawień formularza modułu.
 *
 * @param name      nazwa profilu (unikalna w module, bez rozróżniania wielkości liter)
 * @param updatedAt czas ostatniego zapisu
 * @param options   zapisane ustawienia formularza
 * @param <O>       typ ustawień modułu
 */
public record Profile<O>(String name, Instant updatedAt, O options) {

    /** Wyświetlane w liście profili. */
    @Override
    public String toString() {
        return name;
    }
}
