package com.photon.duplicates.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedPathsTest {

    @TempDir
    Path dir;

    @Test
    void detectsDirectoriesStartingWithDoubleUnderscore() {
        assertTrue(ProtectedPaths.isProtected(dir.resolve("__Archiwum/foto.jpg")));
        assertTrue(ProtectedPaths.isProtected(dir.resolve("__Archiwum/2024/lato/foto.jpg")), "podkatalogi też");
        assertFalse(ProtectedPaths.isProtected(dir.resolve("_Archiwum/foto.jpg")), "jeden podkreślnik to za mało");
        assertFalse(ProtectedPaths.isProtected(dir.resolve("Archiwum__/foto.jpg")));
        assertFalse(ProtectedPaths.isProtected(dir.resolve("Zdjecia/__foto.jpg")), "liczy się katalog, nie nazwa pliku");
        assertEquals(List.of(dir.resolve("__A"), dir.resolve("__A/x/__B")),
                ProtectedPaths.protectedDirs(dir.resolve("__A/x/__B/foto.jpg")));
    }

    @Test
    void automaticActionSkipsProtectedFiles() throws IOException {
        ScannedFile original = file("Zdjecia/foto.jpg");
        ScannedFile copy = file("Kopie/foto.jpg");
        ScannedFile protectedCopy = file("__Archiwum/2024/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(original, copy, protectedCopy));

        DuplicateFileActions.Outcome outcome = DuplicateFileActions.apply(List.of(group),
                DuplicateFileActions.Mode.DELETE, null, List.of(dir), null, () -> false);

        assertEquals(1, outcome.processed().size());
        assertEquals(1, outcome.skippedProtected());
        assertFalse(Files.exists(copy.path()));
        assertTrue(Files.exists(protectedCopy.path()), "plik z katalogu __ nie może zniknąć automatycznie");
        assertTrue(Files.exists(original.path()));
    }

    @Test
    void automaticMoveAlsoSkipsProtectedFiles() throws IOException {
        ScannedFile original = file("Zdjecia/foto.jpg");
        ScannedFile protectedCopy = file("__Archiwum/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(original, protectedCopy));

        DuplicateFileActions.Outcome outcome = DuplicateFileActions.apply(List.of(group),
                DuplicateFileActions.Mode.MOVE, dir.resolve("cel"), List.of(dir), null, () -> false);

        assertTrue(outcome.processed().isEmpty());
        assertEquals(1, outcome.skippedProtected());
        assertTrue(Files.exists(protectedCopy.path()));
    }

    @Test
    void manualRemovalKeepsAtLeastOneCopyInProtectedDirectory() throws IOException {
        ScannedFile outside = file("Zdjecia/foto.jpg");
        ScannedFile first = file("__Archiwum/a/foto.jpg");
        ScannedFile second = file("__Archiwum/b/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(outside, first, second));

        // Dwie kopie w __Archiwum: jedną można usunąć ręcznie.
        assertNull(ProtectedPaths.blockingDir(group, first));
        DuplicateFileActions.Outcome removed = DuplicateFileActions.removeManually(group, first, false, null);
        assertEquals(1, removed.processed().size());
        assertFalse(Files.exists(first.path()));

        // Została ostatnia kopia w __Archiwum — blokada, nawet jeśli poza nim jest inna.
        DuplicateGroup after = group.without(removed.processed()).orElseThrow();
        assertEquals(dir.resolve("__Archiwum"), ProtectedPaths.blockingDir(after, second));
        DuplicateFileActions.Outcome blocked = DuplicateFileActions.removeManually(after, second, false, null);
        assertTrue(blocked.processed().isEmpty());
        assertEquals(1, blocked.errors().size());
        assertTrue(Files.exists(second.path()));

        // Pliki spoza katalogów chronionych można usuwać ręcznie.
        assertTrue(ProtectedPaths.canRemoveManually(after, outside));
    }

    @Test
    void copyDeletedOutsideAppDoesNotCountAsRemainingCopy() throws IOException {
        ScannedFile first = file("__Archiwum/a/foto.jpg");
        ScannedFile second = file("__Archiwum/b/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(first, second));
        Files.delete(second.path()); // ktoś usunął kopię w Eksploratorze

        assertEquals(dir.resolve("__Archiwum"), ProtectedPaths.blockingDir(group, first));
    }

    @Test
    void nestedProtectedDirectoriesEachKeepACopy() throws IOException {
        ScannedFile inner = file("__A/__B/foto.jpg");
        ScannedFile outer = file("__A/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(outer, inner));

        // W __A zostałaby kopia, ale w __B — już nie.
        assertEquals(dir.resolve("__A/__B"), ProtectedPaths.blockingDir(group, inner));
        // Usunięcie kopii z __A: w __A zostaje kopia z __A/__B.
        assertNull(ProtectedPaths.blockingDir(group, outer));
    }

    private ScannedFile file(String relative) throws IOException {
        Path path = dir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{1, 2, 3});
        return new ScannedFile(path, 3, Instant.now(), Instant.now(), 0);
    }
}
