package com.photon;

/**
 * Punkt wejścia aplikacji.
 * <p>
 * Osobna klasa (nie dziedzicząca po {@link javafx.application.Application}) pozwala
 * uruchomić JavaFX z classpath bez ostrzeżenia o "nieobsługiwanej konfiguracji",
 * np. bezpośrednio z IDE.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        PhotonApp.main(args);
    }
}
