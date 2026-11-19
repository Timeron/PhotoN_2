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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void startsOneAgentForTwoCheckedProfiles(FxRobot robot) throws Exception {
        Path upload = dir.resolve("Upload");
        Path other = Files.createDirectories(dir.resolve("Inny"));
        Path archive = dir.resolve("Archiwum");
        Path otherArchive = dir.resolve("Archiwum2");

        robot.interact(() -> view.selectProfiles(List.of("Telefon", "Inny")));
        assertEquals(2, view.selectedProfiles().size());
        robot.clickOn("Uruchom");
        waitFor(robot, () -> view.isRunning());
        assertTrue(view.isProfileSelectionLocked());

        write(upload.resolve("IMG_dwa_1.jpg"), "z pierwszego profilu");
        write(other.resolve("IMG_dwa_2.jpg"), "z drugiego profilu");
        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        Path otherDay = otherArchive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        waitFor(robot, () -> Files.exists(day.resolve("IMG_dwa_1.jpg")));
        waitFor(robot, () -> Files.exists(otherDay.resolve("IMG_dwa_2.jpg")));
        assertFalse(Files.exists(day.resolve("IMG_dwa_2.jpg")), "każdy profil do swojego archiwum");

        // Stan: oba profile w opisie agenta i osobne bloki stanu dla każdego profilu.
        waitFor(robot, () -> {
            String state = robot.lookup(".backup-state").queryAs(Label.class).getText();
            return state.contains("profile") && state.contains("„Telefon”") && state.contains("„Inny”");
        });
        waitFor(robot, () -> robot.lookup(".profile-state").queryAll().size() == 2);
        waitFor(robot, () -> robot.lookup(".profile-state-title").queryAllAs(Label.class).stream()
                .map(Label::getText).filter(t -> t.startsWith("Telefon") || t.startsWith("Inny")).count() == 2);
        waitFor(robot, () -> view.logEntries().stream().anyMatch(e -> e.contains("[Inny] Zarchiwizowano 1 plik")));

        robot.clickOn("Zatrzymaj");
        waitFor(robot, () -> !view.isRunning());
        assertFalse(view.isProfileSelectionLocked());
    }

    @Test
    void refusesProfilesWithOverlappingFolders(FxRobot robot) throws Exception {
        Path camera = Files.createDirectories(dir.resolve("Upload/Camera"));
        new PhoneArchiveProfileStore(dir.resolve("profile")).save(new Profile<>("Aparat", Instant.now(),
                new PhoneArchiveOptions(camera, dir.resolve("Archiwum3"), true)));
        robot.interact(() -> {
            view.reloadProfiles();
            view.selectProfiles(List.of("Telefon", "Aparat"));
        });

        robot.clickOn("Uruchom");
        WaitForAsyncUtils.waitForFxEvents();
        String feedback = robot.lookup(".action-bar .action-feedback").queryAs(Label.class).getText();
        assertTrue(feedback.contains("pokrywające się foldery źródłowe"), feedback);
        Thread.sleep(500);
        assertFalse(view.isRunning(), "agent nie startuje");
        assertFalse(view.isProfileSelectionLocked(), "wybór można poprawić");
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
