package com.photon.thumbnails;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailGeneratorTest {

    @TempDir
    Path dir;

    @Test
    void createsThumbnailsWithSameNamesAndFolderStructure() throws Exception {
        Path source = dir.resolve("Zdjecia");
        ThumbnailMakerTest.writePhoto(source.resolve("a.jpg"), 2000, 1000, true);
        ThumbnailMakerTest.writePhoto(source.resolve("2024/lato/B.JPEG"), 1000, 2000, false);
        ThumbnailMakerTest.writePhoto(source.resolve("male.jpg"), 300, 200, false);
        Files.write(source.resolve("grafika.png"), new byte[]{1});
        Files.write(source.resolve("notatki.txt"), new byte[]{1});
        // Miniatury w podfolderze źródła nie mogą być brane jako źródło.
        Path target = source.resolve("miniatury");

        ThumbnailGenerator.Summary summary = run(new ThumbnailOptions(source, target, 500, true, false));

        assertEquals(2, summary.resized());
        assertEquals(1, summary.keptSize());
        assertEquals(1, summary.unsupported(), "PNG pominięty, TXT nieliczony");
        assertTrue(summary.errors().isEmpty(), summary.errors().toString());
        assertEquals(500, ImageIO.read(target.resolve("a.jpg").toFile()).getWidth());
        assertEquals(500, ImageIO.read(target.resolve("2024/lato/B.JPEG").toFile()).getHeight());
        assertEquals(300, ImageIO.read(target.resolve("male.jpg").toFile()).getWidth());

        // Drugie uruchomienie: istniejące pominięte, miniatury z folderu docelowego nie są przetwarzane.
        ThumbnailGenerator.Summary again = run(new ThumbnailOptions(source, target, 500, true, false));
        assertEquals(0, again.created());
        assertEquals(3, again.skippedExisting());
        assertFalse(Files.exists(target.resolve("miniatury")));

        // Z nadpisywaniem — tworzone ponownie.
        ThumbnailGenerator.Summary overwritten = run(new ThumbnailOptions(source, target, 400, true, true));
        assertEquals(3, overwritten.created());
        assertEquals(400, ImageIO.read(target.resolve("a.jpg").toFile()).getWidth());
    }

    @Test
    void withoutSubfoldersOnlyTopLevelIsProcessed() throws Exception {
        Path source = dir.resolve("Zdjecia");
        ThumbnailMakerTest.writePhoto(source.resolve("a.jpg"), 1000, 800, false);
        ThumbnailMakerTest.writePhoto(source.resolve("sub/b.jpg"), 1000, 800, false);
        Path target = dir.resolve("Miniatury");

        ThumbnailGenerator.Summary summary = run(new ThumbnailOptions(source, target, 200, false, false));

        assertEquals(1, summary.created());
        assertTrue(Files.exists(target.resolve("a.jpg")));
        assertFalse(Files.exists(target.resolve("sub")));
    }

    @Test
    void refusesToWriteIntoSourceFolder() throws Exception {
        Path source = dir.resolve("Zdjecia");
        ThumbnailMakerTest.writePhoto(source.resolve("a.jpg"), 1000, 800, false);
        ThumbnailOptions options = new ThumbnailOptions(source, source, 200, true, true);

        assertNotNull(options.validate());
        assertThrows(IllegalArgumentException.class, () -> run(options));
        assertEquals(1000, ImageIO.read(source.resolve("a.jpg").toFile()).getWidth(), "oryginał nietknięty");
    }

    @Test
    void neverOverwritesAnOriginal() throws Exception {
        // Źródło T/sub z plikiem T/sub/sub/a.jpg → miniatura trafiłaby na oryginał T/sub/a.jpg.
        Path target = dir.resolve("T");
        Path source = target.resolve("sub");
        ThumbnailMakerTest.writePhoto(source.resolve("a.jpg"), 1000, 800, false);
        ThumbnailMakerTest.writePhoto(source.resolve("sub/a.jpg"), 1200, 800, false);
        String before = ThumbnailMakerTest.sha256(source.resolve("a.jpg"));

        ThumbnailGenerator.Summary summary = run(new ThumbnailOptions(source, target, 200, true, true));

        assertEquals(before, ThumbnailMakerTest.sha256(source.resolve("a.jpg")), "oryginał nietknięty");
        assertEquals(1, summary.errors().size(), summary.errors().toString());
        assertEquals(1, summary.created(), "T/sub/a.jpg → T/a.jpg jest poza źródłem, więc dozwolone");
        assertTrue(Files.exists(target.resolve("a.jpg")));
    }

    @Test
    void cancellationStopsProcessing() throws Exception {
        Path source = dir.resolve("Zdjecia");
        ThumbnailMakerTest.writePhoto(source.resolve("a.jpg"), 1000, 800, false);
        assertThrows(ThumbnailGenerator.CancelledException.class, () ->
                new ThumbnailGenerator(new ThumbnailOptions(source, dir.resolve("M"), 200, true, false), 2)
                        .run((m, d, t) -> { }, () -> true));
    }

    private static ThumbnailGenerator.Summary run(ThumbnailOptions options) {
        return new ThumbnailGenerator(options, 2).run((message, done, total) -> { }, () -> false);
    }
}
