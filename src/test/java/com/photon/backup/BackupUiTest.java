package com.photon.backup;

import com.photon.AppPaths;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.phonearchive.PhoneDuplicateAction;
import com.photon.profiles.Profile;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.Callable;

/**
 * Zakładka „Kopia zapasowa” jako panel sterowania agentem. Agent działa tu w wątku tego samego procesu
 * (z krótkimi odstępami), ale porozumiewa się z widokiem tak samo jak osobny proces — przez pliki.
 */
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
    private AgentLoop loop;
    private Thread agentThread;

    @Start
    void start(Stage stage) throws IOException {
        PhoneArchiveProfileStore store = new PhoneArchiveProfileStore(dir.resolve("profile"));
        store.save(new Profile<>("Telefon", Instant.now(), new PhoneArchiveOptions(
                dir.resolve("Upload"), dir.resolve("Archiwum"), true, false, 800, null,
                PhoneDuplicateAction.REMOVE, null)));
        store.save(new Profile<>("Inny", Instant.now(), new PhoneArchiveOptions(
                dir.resolve("Inny"), dir.resolve("Archiwum2"), true)));
        Files.createDirectories(dir.resolve("Upload"));

        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, profileNames -> {
            List<AgentProfile> chosen = new ArrayList<>();
            for (String name : profileNames) {
                chosen.add(new AgentProfile(name, store.loadAll().stream().filter(p -> p.name().equals(name))
                        .findFirst().orElseThrow().options()));
            }
            loop = new AgentLoop(chosen, files, null, Duration.ofMillis(300), Duration.ofMillis(300),
                    Duration.ofHours(1), Duration.ZERO, Duration.ofMillis(500), Clock.systemDefaultZone(), null);
            agentThread = new Thread(() -> {
                try {
                    loop.run();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, "agent-ui-test");
            agentThread.setDaemon(true);
            agentThread.start();
        });
        view = new BackupView(store, control);
        Scene scene = new Scene(view, 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Stop
    void stop() throws InterruptedException {
        if (loop != null) {
            loop.requestStop();
            agentThread.join(10_000);
        }
    }

    @Test
    void startsAgentShowsItsWorkAndControlsIt(FxRobot robot) throws Exception {
        Path upload = dir.resolve("Upload");
        Path archive = dir.resolve("Archiwum");
        write(upload.resolve("IMG_1.jpg"), "jeden");
        write(upload.resolve("IMG_dup.jpg"), "już jest");
        write(archive.resolve("2020-01-01/IMG_dup.jpg"), "już jest");

        robot.interact(() -> view.selectProfiles(List.of("Telefon")));
        robot.clickOn("Uruchom");

        waitFor(robot, () -> view.isRunning());
        assertTrue(view.isProfileSelectionLocked(), "profili nie zmienia się w trakcie pracy");
        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        waitFor(robot, () -> Files.exists(day.resolve("IMG_1.jpg")));

        // Stan i dziennik agenta widoczne w zakładce.
        waitFor(robot, () -> robot.lookup(".backup-state").queryAs(Label.class).getText().contains("profil „Telefon”"));
        waitFor(robot, () -> view.logEntries().stream().anyMatch(e -> e.contains("Zarchiwizowano 1 plik")));

        // Duplikat do decyzji → przycisk z licznikiem → potwierdzenie → agent usuwa.
        waitFor(robot, () -> robot.lookup(".button").queryAllAs(Button.class).stream()
                .anyMatch(b -> b.isVisible() && b.getText() != null
                        && b.getText().startsWith("Usuń zgłoszone duplikaty (1)")));
        robot.clickOn(".action-bar .button-danger");
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(".dialog-pane .button-danger");
        waitFor(robot, () -> !Files.exists(upload.resolve("IMG_dup.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-01-01/IMG_dup.jpg")));

        // Zatrzymanie.
        robot.clickOn("Zatrzymaj");
        waitFor(robot, () -> !view.isRunning());
        assertFalse(view.isProfileSelectionLocked());
        waitFor(robot, () -> robot.lookup(".backup-state").queryAs(Label.class).getText().startsWith("Zatrzymany"));
        write(upload.resolve("IMG_3.jpg"), "trzy");
        Thread.sleep(1000);
        assertFalse(Files.exists(day.resolve("IMG_3.jpg")), "po zatrzymaniu nic nie jest archiwizowane");
    }

    private static void waitFor(FxRobot robot, Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> {
            WaitForAsyncUtils.waitForFxEvents();
            return condition.call();
        });
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
