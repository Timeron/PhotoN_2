package com.photon.ui;

import javafx.application.Platform;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Integracja z systemem: otwieranie folderów w Eksploratorze / menedżerze plików.
 */
public final class Desktops {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Desktops() {
    }

    /**
     * Otwiera folder (tworząc go, jeśli nie istnieje). Działa w tle, aby nie blokować interfejsu;
     * ewentualny błąd jest przekazywany do {@code onError} w wątku JavaFX.
     */
    public static void openFolder(Path dir, Consumer<String> onError) {
        Thread thread = new Thread(() -> {
            try {
                Files.createDirectories(dir);
                if (WINDOWS) {
                    new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
                } else {
                    Desktop.getDesktop().open(dir.toFile());
                }
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        }, "open-folder");
        thread.setDaemon(true);
        thread.start();
    }

    /** Otwiera folder zawierający plik i zaznacza ten plik (na Windows przez Eksplorator). */
    public static void showInFolder(Path file, Consumer<String> onError) {
        runInBackground("show-in-folder", onError, () -> {
            if (WINDOWS) {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
            } else {
                Desktop.getDesktop().open(file.toAbsolutePath().getParent().toFile());
            }
        });
    }

    /** Otwiera plik w domyślnym programie systemu. */
    public static void openFile(Path file, Consumer<String> onError) {
        runInBackground("open-file", onError, () -> Desktop.getDesktop().open(file.toFile()));
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    private static void runInBackground(String name, Consumer<String> onError, IoAction action) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }
}
