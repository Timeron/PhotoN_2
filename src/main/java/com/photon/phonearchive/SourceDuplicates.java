package com.photon.phonearchive;

import com.photon.duplicates.scan.ProtectedPaths;
import com.photon.remote.Locations;
import com.photon.ui.Texts;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Duplikaty w folderze archiwizowanym — pliki, które już są w archiwum: usuwanie albo przenoszenie do
 * folderu zastępczego (śmietnika). Kopie w archiwum zawsze zostają.
 */
public final class SourceDuplicates {

    private SourceDuplicates() {
    }

    /**
     * Usuwa ze źródła pliki, które już są w archiwum. Przed usunięciem sprawdza, czy kopia w archiwum
     * nadal istnieje i ma ten sam rozmiar. Plik z chronionego folderu ({@code __…}) jest usuwany tylko
     * wtedy, gdy kopia z archiwum leży w tym samym chronionym folderze.
     *
     * @param toTrash do kosza systemowego zamiast trwałego usunięcia (serwer nie ma kosza — tam trwale)
     */
    public static PhoneArchiver.RemovalOutcome remove(List<PhoneArchiver.Duplicate> duplicates, boolean toTrash) {
        return process(duplicates, file -> {
            if (toTrash && !Locations.isRemote(file)) {
                if (!Desktop.getDesktop().moveToTrash(file.toFile())) {
                    throw new IOException("nie udało się przenieść do kosza");
                }
            } else {
                Files.delete(file);
            }
        });
    }

    /**
     * Przenosi ze źródła pliki, które już są w archiwum, do wskazanego folderu z zachowaniem struktury
     * podfolderów względem folderu archiwizowanego. Gdy plik o tej nazwie już tam jest, dostaje sufiks
     * {@code (1)}, {@code (2)}… Zabezpieczenia jak w {@link #remove}.
     *
     * @param source folder archiwizowany (do odtworzenia struktury podfolderów)
     * @param folder folder na duplikaty
     */
    public static PhoneArchiver.RemovalOutcome move(List<PhoneArchiver.Duplicate> duplicates, Path source, Path folder) {
        Path root = source.toAbsolutePath().normalize();
        Path destinationRoot = folder.toAbsolutePath().normalize();
        return process(duplicates, file -> {
            Path absolute = file.toAbsolutePath().normalize();
            Path relative = absolute.startsWith(root) ? root.relativize(absolute) : absolute.getFileName();
            Path destination = SafeTransfer.uniqueName(destinationRoot.resolve(relative.toString()));
            Files.createDirectories(destination.getParent());
            SafeTransfer.transfer(file, destination, false);
        });
    }

    /** Czy system pozwala przenosić pliki do kosza. */
    public static boolean trashSupported() {
        try {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private interface FileOperation {
        void apply(Path file) throws IOException;
    }

    /**
     * Wspólne zabezpieczenia: kopia w archiwum musi nadal istnieć i mieć ten sam rozmiar, a plik z chronionego
     * folderu ({@code __…}) jest ruszany tylko wtedy, gdy kopia z archiwum leży w tym samym chronionym folderze.
     */
    private static PhoneArchiver.RemovalOutcome process(List<PhoneArchiver.Duplicate> duplicates, FileOperation operation) {
        List<Path> processed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skippedProtected = 0;
        for (PhoneArchiver.Duplicate duplicate : duplicates) {
            Path file = duplicate.source();
            try {
                Path existing = duplicate.existing().toAbsolutePath().normalize();
                boolean protectedCopyKept = ProtectedPaths.protectedDirs(file).stream().allMatch(existing::startsWith);
                if (!protectedCopyKept) {
                    skippedProtected++;
                    continue;
                }
                if (!Files.isRegularFile(file)) {
                    continue; // usunięty w międzyczasie
                }
                if (!Files.isRegularFile(existing) || Files.size(existing) != Files.size(file)) {
                    throw new IOException("kopia w archiwum zniknęła lub się zmieniła (" + existing + ")");
                }
                operation.apply(file);
                processed.add(file);
            } catch (IOException | RuntimeException e) {
                errors.add(file + ": " + Texts.errorMessage(e));
            }
        }
        return new PhoneArchiver.RemovalOutcome(List.copyOf(processed), skippedProtected, List.copyOf(errors));
    }
}
