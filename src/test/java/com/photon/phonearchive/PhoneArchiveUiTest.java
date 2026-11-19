package com.photon.phonearchive;

import com.photon.AppPaths;
import com.photon.profiles.Profile;
import com.photon.settings.SettingsService;
import com.photon.ui.MainView;
import javafx.scene.Node;
import javafx.scene.Scene;
import com.photon.thumbnails.ThumbnailMakerTest;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.photon.JsonFileStore;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;

/** Formularz „Archiwizacja telefonu” w GUI (Monocle, bez ekranu i bez ruszania myszy). */
@ExtendWith(ApplicationExtension.class)
class PhoneArchiveUiTest {

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
        Files.deleteIfExists(AppPaths.dataDir().resolve("phone-archive.json"));
        Path profiles = AppPaths.profilesDir("phone-archive");
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
    void archivesNewFilesAndRemovesDuplicatesOnRequest(FxRobot robot) throws Exception {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "zdjęcie 1");
        write(phone.resolve("VID_1.mp4"), "film 1");
        write(phone.resolve("plik.txt"), "pomijany");
        Files.createDirectories(archive);

        robot.clickOn(navButton(robot, "Archiwizacja telefonu"));
        robot.clickOn("Archiwizuj");
        assertTrue(feedback(robot).contains("Wybierz folder do archiwizacji"), feedback(robot));

        List<TextField> fields = pathFields(robot);
        robot.interact(() -> {
            fields.get(0).setText(phone.toString());
            fields.get(1).setText(archive.toString());
            duplicateAction(robot).setValue(PhoneDuplicateAction.REMOVE);
        });
        assertTrue(warning(robot).isVisible(), "ostrzeżenie: brak folderów RRRR-MM-DD");

        // Brak folderów z datą → okno z ostrzeżeniem → mimo to archiwizuj.
        robot.clickOn(".action-bar .button-primary");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(robot.lookup("Brak folderów z datą w archiwum").tryQuery().isPresent());
        robot.clickOn(".dialog-pane .button-primary");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> feedback(robot).startsWith("Zarchiwizowano"));

        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        assertTrue(feedback(robot).startsWith("Zarchiwizowano 2 pliki"), feedback(robot));
        assertTrue(Files.exists(day.resolve("IMG_1.jpg")));
        assertTrue(Files.exists(day.resolve("VID_1.mp4")));
        assertTrue(Files.exists(phone.resolve("plik.txt")));
        assertFalse(warning(robot).isVisible(), "archiwum ma już folder z datą");

        // Drugie podejście: ten sam plik wrócił na telefon → duplikat → pytanie o usunięcie.
        write(phone.resolve("IMG_1.jpg"), "zdjęcie 1");
        write(phone.resolve("IMG_2.jpg"), "zdjęcie 2");
        robot.clickOn(".action-bar .button-primary");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
                () -> robot.lookup("Duplikaty już są w archiwum").tryQuery().isPresent());
        robot.clickOn(".dialog-pane .button-danger");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> feedback(robot).contains("Usunięto"));

        assertTrue(feedback(robot).contains("Usunięto 1 duplikat"), feedback(robot));
        assertFalse(Files.exists(phone.resolve("IMG_1.jpg")));
        assertTrue(Files.exists(day.resolve("IMG_1.jpg")));
        assertTrue(Files.exists(day.resolve("IMG_2.jpg")));
        assertFalse(Files.exists(day.resolve("IMG_1 (1).jpg")));
    }

    @Test
    void makesThumbnailsWhenChecked(FxRobot robot) throws Exception {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path thumbs = dir.resolve("Miniatury");
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_1.jpg"), 1600, 1200, true);
        Files.createDirectories(archive.resolve("2020-01-01"));

        robot.clickOn(navButton(robot, "Archiwizacja telefonu"));
        Node options = robot.lookup(".phone-archive-view .thumbnail-options").query();
        assertFalse(options.isVisible(), "opcje miniatur ukryte, dopóki checkbox jest odznaczony");

        // Sekcja może być poniżej widocznej części formularza — przełączamy checkbox bez klikania w ekran.
        CheckBox checkBox = robot.lookup(".phone-archive-view .check-box")
                .queryAllAs(CheckBox.class).stream()
                .filter(c -> "Utwórz miniatury zarchiwizowanych zdjęć".equals(c.getText()))
                .findFirst().orElseThrow();
        robot.interact(checkBox::fire);
        assertTrue(options.isVisible());
        @SuppressWarnings("unchecked")
        Spinner<Integer> side = robot.lookup(".phone-archive-view .spinner").queryAs(Spinner.class);
        assertEquals(800, side.getValue(), "domyślnie 800 px");

        List<TextField> fields = pathFields(robot);
        assertEquals(4, fields.size(), "źródło, archiwum, duplikaty, miniatury");
        robot.interact(() -> {
            fields.get(0).setText(phone.toString());
            fields.get(1).setText(archive.toString());
            duplicateAction(robot).setValue(PhoneDuplicateAction.REMOVE);
            side.getValueFactory().setValue(640);
        });

        // Bez folderu na miniatury — komunikat zamiast startu.
        robot.clickOn(".action-bar .button-primary");
        assertTrue(feedback(robot).contains("Wybierz folder na miniatury"), feedback(robot));

        robot.interact(() -> fields.get(3).setText(thumbs.toString()));
        robot.clickOn(".action-bar .button-primary");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> feedback(robot).startsWith("Zarchiwizowano"));

        String day = LocalDate.now().format(PhoneArchiver.DAY_FORMAT);
        assertTrue(feedback(robot).contains("Miniatury: 1"), feedback(robot));
        assertEquals(640, ImageIO.read(thumbs.resolve(day).resolve("IMG_1.jpg").toFile()).getWidth());
        assertTrue(Files.exists(archive.resolve(day).resolve("IMG_1.jpg")));

        PhoneArchiveOptions saved = new JsonFileStore<>(AppPaths.dataDir().resolve("phone-archive.json"),
                PhoneArchiveOptions.class, PhoneArchiveOptions.defaults()).load();
        assertTrue(saved.makeThumbnails());
        assertEquals(640, saved.thumbnailSide());
        assertEquals(thumbs, saved.thumbnailTarget());
    }

    @Test
    void movesDuplicatesToChosenFolder(FxRobot robot) throws Exception {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path dups = dir.resolve("Duplikaty");
        write(phone.resolve("Camera/IMG_1.jpg"), "już jest");
        write(phone.resolve("IMG_2.jpg"), "nowe");
        write(archive.resolve("2020-01-01/IMG_1.jpg"), "już jest");

        robot.clickOn(navButton(robot, "Archiwizacja telefonu"));
        Node folderRow = robot.lookup(".phone-archive-view .text-field").queryAllAs(TextField.class).stream()
                .filter(f -> "Wskaż folder, do którego trafią duplikaty".equals(f.getPromptText()))
                .findFirst().orElseThrow().getParent();
        ComboBox<PhoneDuplicateAction> action = duplicateAction(robot);
        assertEquals(PhoneDuplicateAction.MOVE_TO_FOLDER, action.getValue(), "domyślnie przenoszenie do śmietnika");
        assertTrue(folderRow.isVisible(), "pole folderu zastępczego widoczne domyślnie");
        robot.interact(() -> action.setValue(PhoneDuplicateAction.REMOVE));
        assertFalse(folderRow.isVisible(), "pole folderu ukryte przy usuwaniu");

        List<TextField> fields = pathFields(robot);
        robot.interact(() -> {
            fields.get(0).setText(phone.toString());
            fields.get(1).setText(archive.toString());
            action.setValue(PhoneDuplicateAction.MOVE_TO_FOLDER);
        });
        assertTrue(folderRow.isVisible());

        robot.clickOn(".action-bar .button-primary");
        assertTrue(feedback(robot).contains("Wybierz folder zastępczy (śmietnik)"), feedback(robot));

        robot.interact(() -> fields.get(2).setText(dups.toString()));
        robot.clickOn(".action-bar .button-primary");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
                () -> robot.lookup("Duplikaty już są w archiwum").tryQuery().isPresent());
        robot.clickOn(".dialog-pane .button-primary");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> feedback(robot).contains("Przeniesiono do śmietnika"));

        assertTrue(feedback(robot).contains("Przeniesiono do śmietnika 1 duplikat"), feedback(robot));
        assertEquals("już jest", Files.readString(dups.resolve("Camera/IMG_1.jpg")), "struktura podfolderów zachowana");
        assertFalse(Files.exists(phone.resolve("Camera/IMG_1.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-01-01/IMG_1.jpg")), "kopia w archiwum zostaje");
        assertTrue(Files.exists(archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT)).resolve("IMG_2.jpg")));
        assertTrue(robot.lookup("Otwórz folder duplikatów").tryQuery().map(Node::isVisible).orElse(false));
    }

    @Test
    void savesArchiveProfiles(FxRobot robot) throws Exception {
        robot.clickOn(navButton(robot, "Archiwizacja telefonu"));
        List<TextField> fields = pathFields(robot);
        robot.interact(() -> {
            fields.get(0).setText(dir.resolve("DCIM").toString());
            fields.get(1).setText(dir.resolve("Archiwum").toString());
        });

        robot.clickOn("Zapisz jako…");
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(".dialog-pane .text-field").write("Mój telefon");
        robot.clickOn(".dialog-pane .button-primary");
        WaitForAsyncUtils.waitForFxEvents();

        List<Profile<PhoneArchiveOptions>> saved = PhoneArchiveProfileStore.createDefault().loadAll();
        assertEquals(1, saved.size());
        assertEquals("Mój telefon", saved.get(0).name());
        assertEquals(dir.resolve("DCIM"), saved.get(0).options().source());
        assertEquals(dir.resolve("Archiwum"), saved.get(0).options().target());

        // Profil widoczny na wspólnej liście profili.
        robot.clickOn(navButton(robot, "Profile"));
        assertTrue(robot.lookup(".profile-name").queryAllAs(Label.class).stream()
                .anyMatch(l -> "Mój telefon".equals(l.getText())));
    }

    private static Node warning(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        return robot.lookup(".phone-archive-view .warning-banner").query();
    }

    private static ToggleButton navButton(FxRobot robot, String title) {
        return robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .filter(b -> title.equals(b.getText())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<PhoneDuplicateAction> duplicateAction(FxRobot robot) {
        return robot.lookup(".phone-archive-view .combo-box").queryAllAs(ComboBox.class).stream()
                .filter(c -> c.getValue() instanceof PhoneDuplicateAction)
                .findFirst().orElseThrow();
    }

    private static List<TextField> pathFields(FxRobot robot) {
        return robot.lookup(".phone-archive-view .text-field").queryAllAs(TextField.class).stream()
                .filter(f -> f.getPromptText() != null && f.getPromptText().startsWith("Wskaż"))
                .toList();
    }

    private static String feedback(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        return robot.lookup(".phone-archive-view .action-feedback").tryQueryAs(Label.class)
                .map(Label::getText).orElse("");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
