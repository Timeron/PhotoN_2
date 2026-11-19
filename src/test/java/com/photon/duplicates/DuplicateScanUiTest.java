package com.photon.duplicates;

import com.photon.profiles.Profile;

import com.photon.AppPaths;
import com.photon.duplicates.scan.DuplicateGroup;
import com.photon.settings.SettingsService;
import com.photon.ui.MainView;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import javafx.scene.Node;
import javafx.scene.control.ListCell;

/**
 * Pełna ścieżka w GUI: profil → skanowanie → wyniki z filtrowaniem i sortowaniem.
 * Działa bez ekranu (Monocle), nie rusza prawdziwej myszy.
 */
@ExtendWith(ApplicationExtension.class)
class DuplicateScanUiTest {

    private static final String PROFILE = "Skan testowy";

    static {
        if (System.getProperty(AppPaths.DATA_DIR_PROPERTY) == null) {
            try {
                System.setProperty(AppPaths.DATA_DIR_PROPERTY, Files.createTempDirectory("photon-ui-test").toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @TempDir
    Path photos;

    @Start
    void start(Stage stage) throws IOException {
        deleteRecursively(AppPaths.dataDir());

        write("A/foto.jpg", 50_000, 1);
        write("B/foto-kopia.jpg", 50_000, 1);
        write("A/wideo.mp4", 200_000, 2);
        write("B/wideo.mp4", 200_000, 2);
        write("A/unikat.png", 60_000, 3);

        DuplicateSearchOptions options = new DuplicateSearchOptions(
                List.of(photos.resolve("A"), photos.resolve("B")), List.of(),
                true, true, false, Set.of("jpg", "png", "mp4"),
                SearchMethod.EXACT, 90, 0, 0, null, null,
                KeepStrategy.LOCATION_PRIORITY, DuplicateAction.REPORT_ONLY, null, false, 2, false, true);
        DuplicateProfileStore.createDefault().save(new Profile<>(PROFILE, Instant.now(), options));

        Scene scene = new Scene(new MainView(new SettingsService(AppPaths.settingsFile())), 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void scanShowsDuplicateGroupsWithFiltersAndSorting(FxRobot robot) throws Exception {
        robot.clickOn(navButton(robot, "Profile"));
        robot.clickOn("Otwórz");
        robot.clickOn("Rozpocznij skanowanie");

        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
                () -> !robot.lookup(".duplicate-results-view").queryAll().isEmpty());
        DuplicateResultsView results = robot.lookup(".duplicate-results-view").queryAs(DuplicateResultsView.class);

        // Dwie grupy; domyślnie sortowane malejąco po łącznym rozmiarze.
        assertEquals(List.of("wideo.mp4", "foto.jpg"), names(results));
        DuplicateGroup video = results.visibleGroups().get(0);
        assertEquals(400_000, video.totalSize());
        assertEquals(photos.resolve("A/wideo.mp4"), video.original().path(), "Oryginał z lokalizacji o priorytecie");

        // Sortowanie po nazwie (klik w nagłówek).
        robot.clickOn("Nazwa");
        assertEquals(List.of("foto.jpg", "wideo.mp4"), names(results));

        // Sortowanie po rozmiarze pliku: rosnąco, potem malejąco.
        robot.clickOn("Rozmiar pliku");
        assertEquals(List.of("foto.jpg", "wideo.mp4"), names(results));
        robot.clickOn("Rozmiar pliku");
        assertEquals(List.of("wideo.mp4", "foto.jpg"), names(results));

        // Filtr typu.
        @SuppressWarnings("unchecked")
        ComboBox<DuplicateResultsView.TypeFilter> type =
                robot.lookup(".results-filter-bar .combo-box").queryAs(ComboBox.class);
        robot.clickOn(type).clickOn("Filmy");
        assertEquals(List.of("wideo.mp4"), names(results));
        robot.clickOn(type);
        // Tekst zaczynający się od kropki TestFX traktuje jak selektor CSS — szukamy etykiety wprost.
        robot.clickOn(robot.lookup(n -> n instanceof ListCell<?> cell
                && ".JPG".equals(cell.getText())).queryAs(Node.class));
        assertEquals(List.of("foto.jpg"), names(results));
        robot.clickOn("Wyczyść filtry");
        assertEquals(2, results.visibleGroups().size());

        // Filtr nazwy (pasuje także do ścieżki kopii).
        robot.clickOn(".duplicate-results-view .search-field").write("kopia");
        assertEquals(List.of("foto.jpg"), names(results));
        robot.clickOn("Wyczyść filtry");

        // Filtr łącznego rozmiaru: grupy zajmujące razem co najmniej 1 MB — żadna.
        @SuppressWarnings("unchecked")
        Spinner<Integer> minTotal = (Spinner<Integer>) robot.lookup(".results-filter-bar .spinner").queryAll()
                .stream().skip(1).findFirst().orElseThrow();
        robot.interact(() -> minTotal.getValueFactory().setValue(1));
        assertEquals(0, results.visibleGroups().size());
        robot.clickOn("Wyczyść filtry");

        // Powrót do konfiguracji i ponowne otwarcie wyników.
        robot.clickOn("Konfiguracja");
        assertTrue(robot.lookup(".duplicate-results-view").queryAll().isEmpty());
        robot.clickOn("Ostatnie wyniki");
        assertEquals(2, robot.lookup(".duplicate-results-view").queryAs(DuplicateResultsView.class)
                .visibleGroups().size());
    }

    // =====================================================================
    // Pomocnicze
    // =====================================================================

    private static List<String> names(DuplicateResultsView results) {
        WaitForAsyncUtils.waitForFxEvents();
        return results.visibleGroups().stream().map(DuplicateGroup::name).toList();
    }

    private static ToggleButton navButton(FxRobot robot, String title) {
        return robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .filter(b -> title.equals(b.getText()))
                .findFirst()
                .orElseThrow();
    }

    private void write(String relative, int size, int seed) throws IOException {
        Path file = photos.resolve(relative);
        Files.createDirectories(file.getParent());
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Files.write(file, data);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
