package com.photon.duplicates.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ręczna ochrona plików i usuwanie zaznaczonych plików w wynikach duplikatów. */
class DuplicateSelectionTest {

    @TempDir
    Path dir;

    @Test
    void keptFileMakesEveryOtherFileOfGroupACandidateIncludingOriginal() throws IOException {
        ScannedFile original = file("A/foto.jpg");
        ScannedFile copy1 = file("B/foto.jpg");
        ScannedFile copy2 = file("C/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(original, copy1, copy2));

        assertEquals(List.of(copy1, copy2), DuplicateFileActions.candidates(group, Set.of()));
        assertEquals(List.of(original, copy2), DuplicateFileActions.candidates(group, Set.of(copy1.path())));
        assertEquals(List.of(copy2),
                DuplicateFileActions.candidates(group, Set.of(original.path(), copy1.path())));
    }

    @Test
    void automaticActionRemovesOriginalWhenAnotherFileIsKept() throws IOException {
        ScannedFile original = file("A/foto.jpg");
        ScannedFile copy = file("B/foto.jpg");
        ScannedFile protectedCopy = file("__Archiwum/foto.jpg");
        DuplicateGroup kept = new DuplicateGroup(List.of(original, copy, protectedCopy));
        // Druga grupa bez ochrony — działa jak dotąd (oryginał zostaje).
        ScannedFile other = file("A/inny.jpg");
        ScannedFile otherCopy = file("B/inny.jpg");
        DuplicateGroup normal = new DuplicateGroup(List.of(other, otherCopy));

        DuplicateFileActions.Outcome outcome = DuplicateFileActions.apply(List.of(kept, normal), Set.of(copy.path()),
                DuplicateFileActions.Mode.DELETE, null, List.of(dir), dir.resolve("log.txt"), () -> false);

        assertEquals(Set.of(original.path(), otherCopy.path()), outcome.processed());
        assertEquals(1, outcome.skippedProtected(), "plik z katalogu __ nadal pomijany");
        assertFalse(Files.exists(original.path()));
        assertTrue(Files.exists(copy.path()));
        assertTrue(Files.exists(protectedCopy.path()));
        assertTrue(Files.exists(other.path()));
        assertTrue(Files.readString(dir.resolve("log.txt")).contains("zostaje: " + copy.path()));
    }

    @Test
    void removesSelectedFilesButAlwaysKeepsOnePerGroup() throws IOException {
        ScannedFile a1 = file("A/a.jpg");
        ScannedFile a2 = file("B/a.jpg");
        DuplicateGroup groupA = new DuplicateGroup(List.of(a1, a2));
        ScannedFile b1 = file("A/b.jpg");
        ScannedFile b2 = file("B/b.jpg");
        ScannedFile b3 = file("C/b.jpg");
        DuplicateGroup groupB = new DuplicateGroup(List.of(b1, b2, b3));

        DuplicateFileActions.RemovalPlan plan = DuplicateFileActions.planRemoval(Map.of(
                groupA, List.of(a1, a2),          // cała grupa — odmowa
                groupB, List.of(b1, b3)), Set.of()); // oryginał i kopia — można

        assertEquals(2, plan.count());
        assertEquals(List.of(b1, b3), plan.toRemove().get(groupB));
        assertEquals(1, plan.refused().size());
        assertTrue(plan.refused().get(0).contains("co najmniej jeden"), plan.refused().get(0));

        DuplicateFileActions.Outcome outcome = DuplicateFileActions.removeSelected(plan, Set.of(), false, null);
        assertEquals(Set.of(b1.path(), b3.path()), outcome.processed());
        assertTrue(outcome.errors().isEmpty());
        assertTrue(Files.exists(a1.path()) && Files.exists(a2.path()));
        assertTrue(Files.exists(b2.path()));
        assertFalse(Files.exists(b1.path()));
    }

    @Test
    void doesNotRemoveKeptFilesOrLastCopyInProtectedFolder() throws IOException {
        ScannedFile original = file("A/foto.jpg");
        ScannedFile kept = file("B/foto.jpg");
        ScannedFile protected1 = file("__Archiwum/foto.jpg");
        ScannedFile protected2 = file("__Archiwum/2024/foto.jpg");
        DuplicateGroup group = new DuplicateGroup(List.of(original, kept, protected1, protected2));

        // Obie kopie z __Archiwum zaznaczone — żadna z nich nie może zniknąć jako ostatnia.
        DuplicateFileActions.RemovalPlan plan = DuplicateFileActions.planRemoval(
                Map.of(group, List.of(kept, protected1, protected2)), Set.of(kept.path()));
        assertEquals(0, plan.count());
        assertEquals(3, plan.refused().size());

        // Jedna z dwóch kopii w __Archiwum — można, bo druga zostaje.
        plan = DuplicateFileActions.planRemoval(Map.of(group, List.of(original, protected1)), Set.of(kept.path()));
        assertEquals(List.of(original, protected1), plan.toRemove().get(group));
        assertTrue(plan.refused().isEmpty());
    }

    private ScannedFile file(String relative) throws IOException {
        Path path = dir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{1, 2, 3});
        return new ScannedFile(path, 3, Instant.now(), Instant.now(), 0);
    }
}
