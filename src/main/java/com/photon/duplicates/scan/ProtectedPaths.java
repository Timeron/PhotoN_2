package com.photon.duplicates.scan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Katalogi chronione: każdy katalog, którego nazwa zaczyna się od {@value #PREFIX}, wraz z podkatalogami.
 * <ul>
 *   <li>Pliki w nich nigdy nie są usuwane ani przenoszone automatycznie.</li>
 *   <li>Ręcznie można usunąć plik tylko wtedy, gdy w każdym chronionym katalogu, w którym leży,
 *       zostaje co najmniej jedna inna kopia z tej samej grupy.</li>
 * </ul>
 */
public final class ProtectedPaths {

    public static final String PREFIX = "__";

    private ProtectedPaths() {
    }

    /** Chronione katalogi nadrzędne pliku, od najbardziej zewnętrznego. */
    public static List<Path> protectedDirs(Path file) {
        List<Path> dirs = new ArrayList<>();
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            return dirs;
        }
        Path root = parent.getRoot();
        Path current = root;
        for (Path part : root == null ? parent : root.relativize(parent)) {
            current = current == null ? part : current.resolve(part);
            if (part.toString().startsWith(PREFIX)) {
                dirs.add(current);
            }
        }
        return dirs;
    }

    public static boolean isProtected(Path file) {
        return !protectedDirs(file).isEmpty();
    }

    /**
     * Pierwszy chroniony katalog, w którym usunięcie pliku nie zostawiłoby żadnej kopii z grupy,
     * albo {@code null}, gdy plik można usunąć ręcznie.
     */
    public static Path blockingDir(DuplicateGroup group, ScannedFile file) {
        for (Path dir : protectedDirs(file.path())) {
            boolean anotherCopy = group.files().stream()
                    .filter(other -> !other.path().equals(file.path()))
                    .anyMatch(other -> other.path().toAbsolutePath().normalize().startsWith(dir)
                            && Files.exists(other.path()));
            if (!anotherCopy) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Pierwszy chroniony katalog pliku, w którym po jego usunięciu nie zostałaby żadna z plików
     * {@code remaining} (istniejących), albo {@code null}, gdy plik można usunąć.
     */
    public static Path blockingDir(java.util.Collection<ScannedFile> remaining, ScannedFile file) {
        for (Path dir : protectedDirs(file.path())) {
            boolean anotherCopy = remaining.stream()
                    .filter(other -> !other.path().equals(file.path()))
                    .anyMatch(other -> other.path().toAbsolutePath().normalize().startsWith(dir)
                            && Files.exists(other.path()));
            if (!anotherCopy) {
                return dir;
            }
        }
        return null;
    }

    public static boolean canRemoveManually(DuplicateGroup group, ScannedFile file) {
        return blockingDir(group, file) == null;
    }
}
