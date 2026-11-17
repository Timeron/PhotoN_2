package com.photon.settings;

/**
 * Szczegółowość dziennika zdarzeń aplikacji.
 */
public enum LogLevel {

    ERROR("Tylko błędy"),
    WARNING("Błędy i ostrzeżenia"),
    INFO("Informacje (zalecane)"),
    DEBUG("Szczegółowe — do diagnozowania problemów");

    private final String label;

    LogLevel(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
