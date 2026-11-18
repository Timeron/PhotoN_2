package com.photon.backup;

import com.photon.AppPaths;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.phonearchive.PhoneDuplicateAction;
import com.photon.profiles.Profile;
import com.photon.settings.SettingsService;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.framework.junit5.Stop;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zakładka „Kopia zapasowa” w GUI bez ekranu: profil → uruchom → pliki archiwizowane w tle → zatrzymaj. */
@ExtendWith(ApplicationExtension.class)
class BackupUiTest {

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
    static Path dir;

    private BackupView view;

    @Start
    void start(Stage stage) throws IOException {
        PhoneArchiveProfileStore store = new PhoneArchiveProfileStore(dir.resolve("profile"));
        store.save(new Profile<>("Telefon", Instant.now(), new PhoneArchiveOptions(
                dir.resolve("Upload"), dir.resolve("Archiwum"), true, false, 800, null,
                PhoneDuplicateAction.REMOVE, null)));
        store.save(new Profile<>("Inny", Instant.now(), new PhoneArchiveOptions(
                dir.resolve("Inny"), dir.resolve("Archiwum2"), true)));

        // Krótki odstęp i brak okresu spokoju — test nie czeka minutę.
        view = new BackupView(store, new SettingsService(AppPaths.settingsFile()), Duration.ofMillis(300),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ZERO, Clock.systemDefaultZone());
        Scene scene = new Scene(view, 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Stop
    void stop() {
        if (view != null) {
            view.stop();
        }
    }

    @Test
    void archivesNewFilesInBackgroundUntilStopped(FxRobot robot) throws Exception {
        Path upload = dir.resolve("Upload");
        Path archive = dir.resolve("Archiwum");
        write(upload.resolve("IMG_1.jpg"), "jeden");
        write(upload.resolve("IMG_dup.jpg"), "już jest");
        write(archive.resolve("2020-01-01/IMG_dup.jpg"), "już jest");

        robot.interact(() -> view.profileCombo().setValue(view.profileCombo().getItems().stream()
                .filter(p -> p.name().equals("Telefon")).findFirst().orElseThrow()));
        robot.clickOn("Uruchom");
        assertTrue(view.isRunning());
        assertTrue(view.profileCombo().isDisabled(), "profilu nie zmienia się w trakcie pracy");

        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> Files.exists(day.resolve("IMG_1.jpg")));

        // Plik dodany w trakcie pracy też trafia do archiwum w kolejnym przebiegu.
        write(upload.resolve("IMG_2.jpg"), "dwa");
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> Files.exists(day.resolve("IMG_2.jpg")));
        assertTrue(Files.exists(upload.resolve("IMG_dup.jpg")), "duplikat nie jest usuwany bez pytania");

        // Duplikat czeka na decyzję — przycisk z licznikiem.
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> robot.lookup(".button").queryAllAs(Button.class)
                .stream().anyMatch(b -> b.isVisible() && b.getText() != null
                        && b.getText().startsWith("Usuń zgłoszone duplikaty (1)")));
        robot.clickOn(".action-bar .button-danger");
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(".dialog-pane .button-danger");
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> !Files.exists(upload.resolve("IMG_dup.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-01-01/IMG_dup.jpg")));

        assertTrue(view.logEntries().stream().anyMatch(e -> e.contains("Zarchiwizowano")), view.logEntries().toString());
        assertTrue(Files.readString(AppPaths.logsDir().resolve("kopia-zapasowa.log")).contains("Start: profil „Telefon”"));

        robot.clickOn("Zatrzymaj");
        assertFalse(view.isRunning());
        assertFalse(view.profileCombo().isDisabled());
        write(upload.resolve("IMG_3.jpg"), "trzy");
        Thread.sleep(1000);
        assertFalse(Files.exists(day.resolve("IMG_3.jpg")), "po zatrzymaniu nic nie jest archiwizowane");
        assertEquals("Uruchom", robot.lookup(".action-bar .button-primary").queryAs(Button.class).getText());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
