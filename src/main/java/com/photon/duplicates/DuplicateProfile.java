package com.photon.duplicates;

import java.time.Instant;

/**
 * Nazwany, zapisany zestaw ustawień wyszukiwania duplikatów.
 *
 * @param name      nazwa profilu (unikalna, bez rozróżniania wielkości liter)
 * @param updatedAt czas ostatniego zapisu
 * @param options   zapisane ustawienia formularza
 */
public record DuplicateProfile(String name, Instant updatedAt, DuplicateSearchOptions options) {

    /** Wyświetlane w liście profili. */
    @Override
    public String toString() {
        return name;
    }
}
