package com.photon.thumbnails;

import com.photon.AppPaths;
import com.photon.settings.SettingsService;
import com.photon.ui.MainView;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import com.photon.profiles.Profile;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Formularz „Miniaturyzacja” w GUI (Monocle, bez ekranu i bez ruszania myszy). */
@ExtendWith(ApplicationExtension.class)
class ThumbnailsUiTest {

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
    Path dir;

    @Start
    void start(Stage stage) throws IOException {
        Files.deleteIfExists(AppPaths.dataDir().resolve("thumbnails.json"));
        Path profiles = AppPaths.profilesDir("thumbnails");
        if (Files.isDirectory(profiles)) {
            try (var files = Files.list(profiles)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
        }
        Scene scene = new Scene(new MainView(new SettingsService(AppPaths.settingsFile())), 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void createsThumbnailsFromForm(FxRobot robot) throws Exception {
        Path source = dir.resolve("Zdjecia");
        Path target = dir.resolve("Miniatury");
        ThumbnailMakerTest.writePhoto(source.resolve("IMG_1.jpg"), 2000, 1500, true);
        ThumbnailMakerTest.writePhoto(source.resolve("IMG_2.jpg"), 1500, 2000, true);

        robot.clickOn(robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .filter(b -> "Miniaturyzacja".equals(b.getText())).findFirst().orElseThrow());

        // Pusty formularz: komunikat zamiast startu.
        robot.clickOn("Utwórz miniatury");
        assertTrue(feedback(robot).contains("Wybierz folder ze zdjęciami"), feedback(robot));

        List<TextField> fields = pathFields(robot);
        @SuppressWarnings("unchecked")
        Spinner<Integer> side = robot.lookup(".thumbnails-view .spinner").queryAs(Spinner.class);
        robot.interact(() -> {
            fields.get(0).setText(source.toString());
            fields.get(1).setText(target.toString());
            side.getValueFactory().setValue(800);
        });

        robot.clickOn("Utwórz miniatury");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> feedback(robot).startsWith("Utworzono"));

        assertTrue(feedback(robot).startsWith("Utworzono 2 miniatury"), feedback(robot));
        assertEquals(800, ImageIO.read(target.resolve("IMG_1.jpg").toFile()).getWidth());
        assertEquals(800, ImageIO.read(target.resolve("IMG_2.jpg").toFile()).getHeight());
        assertTrue(robot.lookup("Otwórz folder miniatur").tryQuery().isPresent());

        // Ustawienia zapamiętane na następny raz.
        ThumbnailOptions saved = new com.photon.JsonFileStore<>(AppPaths.dataDir().resolve("thumbnails.json"),
                ThumbnailOptions.class, ThumbnailOptions.defaults()).load();
        assertEquals(source, saved.source());
        assertEquals(800, saved.maxSide());
    }

    @Test
    void savesAndOpensThumbnailProfiles(FxRobot robot) throws Exception {
        robot.clickOn(navButton(robot, "Miniaturyzacja"));
        @SuppressWarnings("unchecked")
        Spinner<Integer> side = robot.lookup(".thumbnails-view .spinner").queryAs(Spinner.class);
        assertEquals(800, side.getValue(), "domyślnie 800 px");

        List<TextField> fields = pathFields(robot);
        robot.interact(() -> {
            fields.get(0).setText(dir.resolve("Telefon").toString());
            fields.get(1).setText(dir.resolve("Mini").toString());
            side.getValueFactory().setValue(1200);
        });

        // Zapisz jako… → nazwa w oknie dialogowym → Enter
        robot.clickOn("Zapisz jako…");
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(".dialog-pane .text-field").write("Telefon 1200");
        robot.clickOn(".dialog-pane .button-primary");
        WaitForAsyncUtils.waitForFxEvents();

        ThumbnailProfileStore store = ThumbnailProfileStore.createDefault();
        List<Profile<ThumbnailOptions>> saved = store.loadAll();
        assertEquals(1, saved.size());
        assertEquals("Telefon 1200", saved.get(0).name());
        assertEquals(1200, saved.get(0).options().maxSide());
        assertEquals(dir.resolve("Telefon"), saved.get(0).options().source());

        // Zmiana → „Niezapisane zmiany” → Zapisz
        robot.interact(() -> side.getValueFactory().setValue(640));
        assertTrue(robot.lookup("Niezapisane zmiany").query().isVisible());
        robot.clickOn(robot.lookup(".profile-bar .button").queryAllAs(javafx.scene.control.Button.class).stream()
                .filter(b -> "Zapisz".equals(b.getText())).findFirst().orElseThrow());
        assertEquals(640, store.loadAll().get(0).options().maxSide());
        assertFalse(robot.lookup("Niezapisane zmiany").query().isVisible());

        // Przywróć domyślne zostawia foldery, a rozmiar wraca do 800.
        robot.clickOn("Przywróć domyślne");
        assertEquals(800, side.getValue());
        assertEquals(dir.resolve("Telefon").toString(), fields.get(0).getText());

        // Sekcja „Profile”: profil miniatur na wspólnej liście i otwieranie go w module.
        robot.clickOn(navButton(robot, "Profile"));
        javafx.scene.control.Button open = robot.lookup(".button").queryAllAs(javafx.scene.control.Button.class)
                .stream()
                .filter(b -> "Otwórz".equals(b.getText()) && rowContains(b, "Telefon 1200"))
                .findFirst().orElseThrow();
        robot.clickOn(open);
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<Profile<ThumbnailOptions>> combo =
                robot.lookup(".thumbnails-view .profile-bar .combo-box").queryAs(javafx.scene.control.ComboBox.class);
        assertEquals("Telefon 1200", combo.getValue().name());
        // Profil był już wybrany i miał niezapisane zmiany — tak jak w duplikatach nie są one tracone.
        assertEquals(800, side.getValue());
        assertTrue(robot.lookup("Niezapisane zmiany").query().isVisible());

        // „Cofnij niezapisane zmiany” wczytuje zapisany profil.
        robot.clickOn(robot.lookup(".thumbnails-view .profile-bar .button-icon").queryAllAs(
                javafx.scene.control.Button.class).stream().findFirst().orElseThrow());
        assertEquals(640, side.getValue(), "wczytano ustawienia profilu");
        assertFalse(robot.lookup("Niezapisane zmiany").query().isVisible());
    }

    private static boolean rowContains(javafx.scene.Node node, String text) {
        for (javafx.scene.Parent p = node.getParent(); p != null; p = p.getParent()) {
            boolean found = p.lookupAll(".profile-name").stream()
                    .anyMatch(n -> n instanceof Label l && text.equals(l.getText()));
            if (found) {
                return p.lookupAll(".profile-name").size() == 1;
            }
        }
        return false;
    }

    private static ToggleButton navButton(FxRobot robot, String title) {
        return robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .filter(b -> title.equals(b.getText())).findFirst().orElseThrow();
    }

    private static List<TextField> pathFields(FxRobot robot) {
        return robot.lookup(".thumbnails-view .text-field").queryAllAs(TextField.class).stream()
                .filter(f -> !(f.getParent() instanceof Spinner<?>) && f.getPromptText() != null
                        && f.getPromptText().startsWith("Wskaż"))
                .toList();
    }

    private static String feedback(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // W trakcie pracy formularz jest zastąpiony ekranem postępu.
        return robot.lookup(".thumbnails-view .action-feedback").tryQueryAs(Label.class)
                .map(Label::getText).orElse("");
    }
}
