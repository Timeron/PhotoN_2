package com.photon.ui;

import com.photon.AppPaths;
import com.photon.duplicates.DuplicateAction;
import com.photon.profiles.Profile;
import com.photon.duplicates.DuplicateProfileStore;
import com.photon.duplicates.DuplicateSearchOptions;
import com.photon.duplicates.KeepStrategy;
import com.photon.duplicates.SearchMethod;
import com.photon.settings.SettingsService;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy GUI głównego okna (TestFX). Uruchamiane przez Maven działają bez ekranu
 * (Monocle Headless), więc nie ruszają prawdziwej myszy ani klawiatury.
 */
@ExtendWith(ApplicationExtension.class)
class MainViewUiTest {

    private static final String PROFILE_NAME = "Test UI";

    static {
        // Uruchomienie z IDE bez konfiguracji Mavena: nigdy nie używaj prawdziwego %APPDATA%\PhotoN2.
        if (System.getProperty(AppPaths.DATA_DIR_PROPERTY) == null) {
            try {
                System.setProperty(AppPaths.DATA_DIR_PROPERTY,
                        Files.createTempDirectory("photon-ui-test").toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Start
    void start(Stage stage) throws IOException {
        Path dataDir = AppPaths.dataDir();
        deleteRecursively(dataDir);

        DuplicateProfileStore.createDefault()
                .save(new Profile<>(PROFILE_NAME, Instant.parse("2026-11-17T10:00:00Z"), sampleOptions()));

        MainView mainView = new MainView(new SettingsService(AppPaths.settingsFile()));
        Scene scene = new Scene(mainView, 1280, 800);
        scene.getStylesheets().add(Objects.requireNonNull(
                MainViewUiTest.class.getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void startsWithWelcomeScreen(FxRobot robot) {
        assertEquals("Witaj w PhotoN 2.0", contentTitle(robot));
    }

    @Test
    void sidebarHasAllSections(FxRobot robot) {
        List<String> titles = robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .map(ToggleButton::getText)
                .toList();
        assertEquals(List.of("Szukaj Duplikatów", "Archiwizacja", "Archiwizacja telefonu", "Miniaturyzacja", "Kopia Zapasowa",
                "Profile", "Ustawienia"), titles);
    }

    @Test
    void modulesInPreparationShowPlaceholder(FxRobot robot) {
        for (Section section : List.of(Section.ARCHIVE)) {
            robot.clickOn(navButton(robot, section));
            assertEquals(section.getTitle(), contentTitle(robot));
            assertTrue(isShowing(robot, ".empty-state-title", "Moduł w przygotowaniu"),
                    "Brak komunikatu o module w przygotowaniu: " + section);
        }
    }

    @Test
    void duplicatesSectionShowsSearchForm(FxRobot robot) {
        robot.clickOn(navButton(robot, Section.DUPLICATES));

        assertEquals("Szukaj duplikatów", contentTitle(robot));
        assertFalse(robot.lookup(".duplicate-search-view").queryAll().isEmpty());
        Button start = robot.lookup(".button-primary").queryButton();
        assertEquals("Rozpocznij skanowanie", start.getText());
    }

    @Test
    void settingsSectionShowsForm(FxRobot robot) {
        robot.clickOn(navButton(robot, Section.SETTINGS));

        assertEquals("Ustawienia", contentTitle(robot));
    }

    @Test
    void profilesSectionListsSavedProfileAndOpensIt(FxRobot robot) {
        robot.clickOn(navButton(robot, Section.PROFILES));

        assertEquals("Profile", contentTitle(robot));
        assertTrue(isShowing(robot, ".profile-name", PROFILE_NAME), "Profil nie jest widoczny na liście");

        robot.clickOn("Otwórz");

        assertEquals("Szukaj duplikatów", contentTitle(robot));
        assertTrue(navButton(robot, Section.DUPLICATES).isSelected());
        @SuppressWarnings("unchecked")
        ComboBox<Profile<DuplicateSearchOptions>> combo = robot.lookup(".profile-bar .combo-box").queryAs(ComboBox.class);
        assertEquals(PROFILE_NAME, combo.getValue().name());
    }

    // =====================================================================
    // Pomocnicze
    // =====================================================================

    private static ToggleButton navButton(FxRobot robot, Section section) {
        return robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .filter(b -> section.getTitle().equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Brak przycisku menu: " + section.getTitle()));
    }

    private static String contentTitle(FxRobot robot) {
        return robot.lookup(".content-title").queryAs(Label.class).getText();
    }

    private static boolean isShowing(FxRobot robot, String selector, String text) {
        return robot.lookup(selector).queryAllAs(Label.class).stream()
                .filter(Node::isVisible)
                .anyMatch(l -> text.equals(l.getText()));
    }

    private static DuplicateSearchOptions sampleOptions() {
        return new DuplicateSearchOptions(
                List.of(Path.of("C:\\Zdjęcia")),
                List.of(),
                true, true, false,
                new LinkedHashSet<>(List.of("jpg", "png")),
                SearchMethod.SIMILAR, 85,
                0L, 0L,
                null, null,
                KeepStrategy.LOCATION_PRIORITY, DuplicateAction.MOVE_TO_FOLDER,
                Path.of("E:\\Duplikaty"), true,
                4, true, true);
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
