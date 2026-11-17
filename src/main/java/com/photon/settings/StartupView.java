package com.photon.settings;

/**
 * Co pokazać po uruchomieniu aplikacji.
 */
public enum StartupView {

    WELCOME("Ekran powitalny"),
    LAST_SECTION("Ostatnio otwartą sekcję"),
    DUPLICATES("Szukaj Duplikatów"),
    PROFILES("Profile");

    private final String label;

    StartupView(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
