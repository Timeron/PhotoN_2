package com.photon.duplicates.scan;

import com.photon.duplicates.DuplicateAction;
import com.photon.duplicates.DuplicateSearchOptions;
import com.photon.duplicates.KeepStrategy;
import com.photon.duplicates.SearchMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateScannerTest {

    @TempDir
    Path dir;

    // =====================================================================
    // Metody porównania
    // =====================================================================

    @Test
    void exactFindsIdenticalFilesAndIgnoresSameSizeDifferentContent() throws IOException {
        Path a = write("A/zdjecie.jpg", bytes(5000, 1));
        Path b = write("B/kopia.jpg", bytes(5000, 1));
        write("B/inne.jpg", bytes(5000, 2));           // ten sam rozmiar, inna treść
        write("A/unikat.jpg", bytes(7000, 3));

        ScanResult result = scan(options(SearchMethod.EXACT, KeepStrategy.SHORTEST_PATH));

        assertEquals(4, result.scannedFiles());
        assertEquals(1, result.groups().size());
        DuplicateGroup group = result.groups().get(0);
        assertEquals(Set.of(a, b), Set.copyOf(group.files().stream().map(ScannedFile::path).toList()));
        assertEquals(10_000, group.totalSize());
        assertEquals(5_000, group.reclaimableSize());
    }

    @Test
    void nameAndSizeMatchesDespiteDifferentContent() throws IOException {
        write("A/IMG_1.jpg", bytes(4000, 1));
        write("B/img_1.JPG", bytes(4000, 9));
        write("C/IMG_1.jpg", bytes(4001, 1));          // inny rozmiar

        ScanResult result = scan(options(SearchMethod.NAME_SIZE, KeepStrategy.OLDEST));

        assertEquals(1, result.groups().size());
        assertEquals(2, result.groups().get(0).count());
    }

    @Test
    void similarFindsResizedAndRecompressedImage() throws IOException {
        BufferedImage picture = picture(800, 600);
        Files.createDirectories(dir.resolve("A"));
        ImageIO.write(picture, "png", dir.resolve("A/oryginal.png").toFile());
        ImageIO.write(scale(picture, 400, 300), "jpg", dir.resolve("A/maly.jpg").toFile());
        ImageIO.write(inverted(picture), "png", dir.resolve("A/inny.png").toFile());
        write("A/film.mp4", bytes(3000, 1));

        DuplicateSearchOptions o = options(SearchMethod.SIMILAR, KeepStrategy.HIGHEST_QUALITY,
                Set.of("png", "jpg", "mp4"));
        ScanResult result = scan(o);

        assertEquals(1, result.groups().size(), "Grupy: " + result.groups());
        DuplicateGroup group = result.groups().get(0);
        assertEquals(Set.of("oryginal.png", "maly.jpg"),
                Set.copyOf(group.files().stream().map(ScannedFile::fileName).toList()));
        assertEquals(1, result.skippedFiles(), "Film nie jest porównywany jako obraz");
    }

    // =====================================================================
    // Filtry konfiguracji
    // =====================================================================

    @Test
    void respectsExtensionsSizeAndExclusions() throws IOException {
        write("A/a.jpg", bytes(5000, 1));
        write("A/b.jpg", bytes(5000, 1));
        write("A/c.png", bytes(5000, 1));               // rozszerzenie spoza listy
        write("A/male1.jpg", bytes(100, 2));            // poniżej minimum
        write("A/male2.jpg", bytes(100, 2));
        write("A/pominiete/d.jpg", bytes(5000, 1));     // folder wykluczony

        DuplicateSearchOptions o = new DuplicateSearchOptions(
                List.of(dir.resolve("A")), List.of(dir.resolve("A/pominiete")),
                true, true, false, Set.of("jpg"), SearchMethod.EXACT, 90,
                1000, 0, null, null, KeepStrategy.SHORTEST_PATH, DuplicateAction.REPORT_ONLY,
                null, false, 2, false, true);
        ScanResult result = scan(o);

        assertEquals(1, result.groups().size());
        assertEquals(Set.of("a.jpg", "b.jpg"),
                Set.copyOf(result.groups().get(0).files().stream().map(ScannedFile::fileName).toList()));
    }

    @Test
    void withoutSubfoldersScansOnlyTopLevel() throws IOException {
        write("A/a.jpg", bytes(5000, 1));
        write("A/sub/b.jpg", bytes(5000, 1));

        DuplicateSearchOptions o = new DuplicateSearchOptions(
                List.of(dir.resolve("A")), List.of(), false, true, false, Set.of("jpg"),
                SearchMethod.EXACT, 90, 0, 0, null, null, KeepStrategy.OLDEST,
                DuplicateAction.REPORT_ONLY, null, false, 2, false, true);

        assertEquals(0, scan(o).groups().size());
    }

    @Test
    void crossLocationOnlyIgnoresDuplicatesWithinOneLocation() throws IOException {
        write("A/1.jpg", bytes(5000, 1));
        write("A/2.jpg", bytes(5000, 1));               // duplikat w tej samej lokalizacji
        write("A/3.jpg", bytes(6000, 2));
        write("B/3.jpg", bytes(6000, 2));               // duplikat między lokalizacjami

        DuplicateSearchOptions o = new DuplicateSearchOptions(
                List.of(dir.resolve("A"), dir.resolve("B")), List.of(), true, true, true, Set.of("jpg"),
                SearchMethod.EXACT, 90, 0, 0, null, null, KeepStrategy.LOCATION_PRIORITY,
                DuplicateAction.REPORT_ONLY, null, false, 2, false, true);
        ScanResult result = scan(o);

        assertEquals(1, result.groups().size());
        assertEquals(dir.resolve("A/3.jpg"), result.groups().get(0).original().path());
    }

    // =====================================================================
    // Oryginał, pamięć podręczna, przerwanie
    // =====================================================================

    @Test
    void keepStrategyChoosesOriginal() throws IOException {
        Path older = write("B/stary.jpg", bytes(5000, 1));
        Path newer = write("A/nowy.jpg", bytes(5000, 1));
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")));

        assertEquals(newer, scan(options(SearchMethod.EXACT, KeepStrategy.NEWEST)).groups().get(0).original().path());
        assertEquals(newer, scan(options(SearchMethod.EXACT, KeepStrategy.LOCATION_PRIORITY))
                .groups().get(0).original().path());
    }

    @Test
    void hashCacheIsReusedBetweenScans() throws IOException {
        write("A/a.jpg", bytes(5000, 1));
        write("B/b.jpg", bytes(5000, 1));
        Path cacheFile = dir.resolve("cache/fingerprints.json");

        DuplicateSearchOptions o = new DuplicateSearchOptions(
                List.of(dir.resolve("A"), dir.resolve("B")), List.of(), true, true, false, Set.of("jpg"),
                SearchMethod.EXACT, 90, 0, 0, null, null, KeepStrategy.OLDEST,
                DuplicateAction.REPORT_ONLY, null, false, 2, true, true);
        new DuplicateScanner(o, new HashCache(cacheFile).load()).scan((m, d, t) -> { }, () -> false);
        assertTrue(Files.exists(cacheFile));

        HashCache reloaded = new HashCache(cacheFile).load();
        assertEquals(2, reloaded.size());
        assertEquals(1, new DuplicateScanner(o, reloaded).scan((m, d, t) -> { }, () -> false).groups().size());
    }

    @Test
    void cancelStopsScan() throws IOException {
        write("A/a.jpg", bytes(5000, 1));
        try {
            new DuplicateScanner(options(SearchMethod.EXACT, KeepStrategy.OLDEST), null)
                    .scan((m, d, t) -> { }, () -> true);
        } catch (DuplicateScanner.CancelledException expected) {
            return;
        }
        throw new AssertionError("Oczekiwano przerwania skanowania");
    }

    @Test
    void moveActionKeepsOriginalAndPreservesStructure() throws IOException {
        Path original = write("A/2020/foto.jpg", bytes(5000, 1));
        Path copy = write("B/kopie/foto.jpg", bytes(5000, 1));
        Path target = dir.resolve("Duplikaty");

        DuplicateSearchOptions o = options(SearchMethod.EXACT, KeepStrategy.LOCATION_PRIORITY);
        ScanResult result = scan(o);
        DuplicateFileActions.Outcome outcome = DuplicateFileActions.apply(result.groups(),
                DuplicateFileActions.Mode.MOVE, target, o.locations(), dir.resolve("log.txt"), () -> false);

        assertEquals(Set.of(copy), outcome.processed());
        assertTrue(outcome.errors().isEmpty(), outcome.errors().toString());
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(target.resolve("B/kopie/foto.jpg")));
        assertTrue(Files.readString(dir.resolve("log.txt")).contains("PRZENIESIONO"));
    }

    @Test
    void csvReportListsAllFiles() throws IOException {
        write("A/a.jpg", bytes(5000, 1));
        write("B/b;c.jpg", bytes(5000, 1));
        ScanResult result = scan(options(SearchMethod.EXACT, KeepStrategy.LOCATION_PRIORITY));

        Path csv = dir.resolve("raport.csv");
        DuplicateReport.write(result.groups(), csv);
        List<String> lines = Files.readAllLines(csv);

        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("Oryginał"));
        assertTrue(lines.get(2).contains("\"b;c.jpg\""));
    }

    // =====================================================================
    // Pomocnicze
    // =====================================================================

    private ScanResult scan(DuplicateSearchOptions options) {
        return new DuplicateScanner(options, null).scan((m, d, t) -> { }, () -> false);
    }

    private DuplicateSearchOptions options(SearchMethod method, KeepStrategy keep) {
        return options(method, keep, Set.of("jpg", "png"));
    }

    private DuplicateSearchOptions options(SearchMethod method, KeepStrategy keep, Set<String> extensions) {
        return new DuplicateSearchOptions(
                List.of(dir.resolve("A"), dir.resolve("B"), dir.resolve("C")), List.of(),
                true, true, false, extensions, method, 90,
                0, 0, null, null, keep, DuplicateAction.REPORT_ONLY, null, false, 2, false, true);
    }

    private Path write(String relative, byte[] content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return file;
    }

    private static byte[] bytes(int size, int seed) {
        byte[] data = new byte[size];
        new java.util.Random(seed).nextBytes(data);
        return data;
    }

    private static BufferedImage picture(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(20, 60, 160), w, h, new Color(250, 210, 90)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(30, 120, 40));
        g.fillOval(w / 5, h / 4, w / 3, h / 2);
        g.setColor(Color.WHITE);
        g.fillRect(w * 3 / 5, h / 6, w / 4, h / 3);
        g.dispose();
        return image;
    }

    private static BufferedImage scale(BufferedImage source, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return image;
    }

    private static BufferedImage inverted(BufferedImage source) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                image.setRGB(x, y, ~source.getRGB(x, y) & 0xffffff);
            }
        }
        return image;
    }
}
