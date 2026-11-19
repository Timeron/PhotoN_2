package com.photon.duplicates;

import com.photon.duplicates.scan.DuplicateGroup;
import com.photon.duplicates.scan.ScanResult;
import com.photon.duplicates.scan.ScannedFile;
import com.photon.settings.AppSettings;
import com.photon.settings.SettingsService;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import javafx.scene.control.TreeTableCell;

/** Zaznaczanie wielu plików (Ctrl+klik), ręczna ochrona i usuwanie zaznaczonych — w GUI bez ekranu. */
@ExtendWith(ApplicationExtension.class)
class DuplicateSelectionUiTest {

    @TempDir
    static Path dir;

    private DuplicateResultsView view;
    private Path a1;
    private Path b1;
    private Path c1;
    private Path a2;
    private Path b2;

    @Start
    void start(Stage stage) throws IOException {
        a1 = write("A/foto.jpg");
        b1 = write("B/foto.jpg");
        c1 = write("C/foto.jpg");
        a2 = write("A/film.mp4");
        b2 = write("B/film.mp4");
        DuplicateGroup photos = new DuplicateGroup(List.of(scanned(a1), scanned(b1), scanned(c1)));
        DuplicateGroup videos = new DuplicateGroup(List.of(scanned(a2), scanned(b2)));

        DuplicateSearchOptions options = new DuplicateSearchOptions(
                List.of(dir.resolve("A"), dir.resolve("B"), dir.resolve("C")), List.of(),
                true, true, false, Set.of("jpg", "mp4"),
                SearchMethod.EXACT, 90, 0, 0, null, null,
                KeepStrategy.LOCATION_PRIORITY, DuplicateAction.REPORT_ONLY, null, false, 2, false, true);
        ScanResult result = new ScanResult(options, List.of(photos, videos), 5, 500, 0, List.of(), 0,
                Duration.ofSeconds(1), Instant.now());

        // Bez kosza — w testach pliki są usuwane trwale.
        SettingsService settings = new SettingsService(dir.resolve("settings.json"));
        AppSettings d = AppSettings.defaults();
        settings.save(new AppSettings(d.startupView(), d.rememberWindow(), d.confirmExit(), d.cacheDir(),
                d.reportsDir(), d.confirmFileOperations(), false, false, d.defaultThreads(),
                d.lowPriorityScan(), d.cacheLimitMb(), d.notifyOnFinish(), d.soundOnFinish(), d.logLevel()));

        view = new DuplicateResultsView(result, settings);
        view.getTable().getRoot().getChildren().forEach(item -> item.setExpanded(true));
        Scene scene = new Scene(view, 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void ctrlClickTogglesFilesThenKeepAndRemove(FxRobot robot) throws Exception {
        // Klik + Ctrl+klik dodaje kolejne pliki, także z innej grupy.
        robot.clickOn(cell(robot, b1));
        ctrlClick(robot, c1);
        ctrlClick(robot, a2);
        assertEquals(3, selectedCount());
        assertTrue(selectionText(robot).startsWith("Zaznaczono 3 pliki"), selectionText(robot));

        // Ponowny Ctrl+klik odejmuje plik z zaznaczenia.
        ctrlClick(robot, c1);
        assertEquals(2, selectedCount());

        // Zwykły klik zaczyna zaznaczenie od nowa.
        robot.clickOn(cell(robot, b1));
        assertEquals(1, selectedCount());

        // Ochrona: B zostaje, a pozostałe pliki grupy (także oryginał A) są zaznaczone do usunięcia.
        robot.clickOn("Oznacz jako chronione");
        assertEquals(Set.of(b1), view.keptFiles());
        assertEquals(List.of(a1, c1), view.selectedFiles().values().iterator().next().stream()
                .map(ScannedFile::path).toList());
        assertEquals(2, robot.lookup(".pill-danger").queryAllAs(Label.class).stream()
                .filter(Node::isVisible).filter(l -> "Do usunięcia".equals(l.getText())).count());
        assertTrue(robot.lookup(".pill-kept").tryQuery().isPresent());

        // Usuń zaznaczone → potwierdzenie z listą → pliki znikają, chroniony zostaje.
        robot.clickOn(".selection-bar .button-danger");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(robot.lookup("Usuń zaznaczone pliki").tryQuery().isPresent());
        robot.clickOn(".dialog-pane .button-danger");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !Files.exists(a1) && !Files.exists(c1));

        assertTrue(Files.exists(b1));
        assertTrue(Files.exists(a2) && Files.exists(b2));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, view.visibleGroups().size(), "grupa zdjęć zniknęła — został jeden plik");
        assertTrue(view.keptFiles().isEmpty());
    }

    @Test
    void refusesToRemoveWholeGroup(FxRobot robot) {
        robot.clickOn(cell(robot, a2));
        ctrlClick(robot, b2);
        robot.clickOn(".selection-bar .button-danger");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(robot.lookup("Nie można usunąć zaznaczonych plików").tryQuery().isPresent());
        robot.clickOn(".dialog-pane .button-primary");
        assertTrue(Files.exists(a2) && Files.exists(b2));
    }

    private void ctrlClick(FxRobot robot, Path path) {
        robot.press(KeyCode.CONTROL).clickOn(cell(robot, path)).release(KeyCode.CONTROL);
    }

    private static Node cell(FxRobot robot, Path path) {
        return robot.lookup(".tree-table-cell").queryAllAs(TreeTableCell.class).stream()
                .filter(c -> path.toString().equals(c.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("Brak wiersza " + path));
    }

    private int selectedCount() {
        WaitForAsyncUtils.waitForFxEvents();
        return view.selectedFiles().values().stream().mapToInt(List::size).sum();
    }

    private static String selectionText(FxRobot robot) {
        return robot.lookup(".selection-count").queryAs(Label.class).getText();
    }

    private static Path write(String relative) throws IOException {
        Path path = dir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, relative.getBytes());
        return path;
    }

    private static ScannedFile scanned(Path path) throws IOException {
        Instant modified = Files.getLastModifiedTime(path).toInstant();
        return new ScannedFile(path, Files.size(path), modified, modified, 0);
    }
}
