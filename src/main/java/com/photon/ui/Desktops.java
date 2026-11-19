package com.photon.ui;

import com.photon.remote.Locations;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.io.IOException;
import java.nio.file.StandardCopyOption;

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
        if (Locations.isRemote(dir)) {
            onError.accept(remoteFolderMessage(dir));
            return;
        }
        Background.run("open-folder", () -> {
            Files.createDirectories(dir);
            if (WINDOWS) {
                new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
            } else {
                Desktop.getDesktop().open(dir.toFile());
            }
        }, onError);
    }

    /** Otwiera folder zawierający plik i zaznacza ten plik (na Windows przez Eksplorator). */
    public static void showInFolder(Path file, Consumer<String> onError) {
        if (Locations.isRemote(file)) {
            onError.accept(remoteFolderMessage(file.getParent()));
            return;
        }
        Background.run("show-in-folder", () -> {
            if (WINDOWS) {
                new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
            } else {
                Desktop.getDesktop().open(file.toAbsolutePath().getParent().toFile());
            }
        }, onError);
    }

    /** Otwiera plik w domyślnym programie systemu. */
    public static void openFile(Path file, Consumer<String> onError) {
        Background.run("open-file", () -> Desktop.getDesktop().open(localCopy(file).toFile()), onError);
    }

    /** Plik z serwera zdalnego jest najpierw pobierany do folderu tymczasowego (do podglądu). */
    private static Path localCopy(Path file) throws IOException {
        if (!Locations.isRemote(file)) {
            return file;
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "PhotoN-podglad", Locations.serverName(file));
        Files.createDirectories(dir);
        Path copy = dir.resolve(file.getFileName().toString());
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    private static String remoteFolderMessage(Path dir) {
        return "Ten folder jest na serwerze „" + Locations.serverName(dir) + "” (" + Locations.remoteFolder(dir)
                + ") — Eksplorator go nie otworzy. Użyj programu do FTP/SFTP.";
    }
}
