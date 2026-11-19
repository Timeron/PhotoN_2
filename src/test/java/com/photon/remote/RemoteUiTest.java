package com.photon.remote;

import com.photon.AppPaths;
import com.photon.settings.SettingsService;
import com.photon.ui.MainView;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Konfiguracja serwera w Ustawieniach i wybór folderu na serwerze w zakładce (GUI bez ekranu). */
@ExtendWith(ApplicationExtension.class)
class RemoteUiTest {

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

    private final TestServers servers = new TestServers();

    @Start
    void start(Stage stage) throws IOException {
        Files.deleteIfExists(AppPaths.dataDir().resolve("phone-archive.json"));
        RemoteServers.useFile(AppPaths.dataDir().resolve("servers.json"));
        Files.deleteIfExists(RemoteServers.file());
        RemoteServers.reload();
        Scene scene = new Scene(new MainView(new SettingsService(AppPaths.settingsFile())), 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() throws IOException {
        servers.close();
        Files.deleteIfExists(RemoteServers.file());
        RemoteServers.useFile(null);
    }

    @Test
    void configuresServerAndArchivesFromIt(FxRobot robot) throws Exception {
        Path ftpRoot = dir.resolve("ftp");
        Files.createDirectories(ftpRoot.resolve("upload"));
        Files.writeString(ftpRoot.resolve("upload/IMG_1.jpg"), "zdjęcie z serwera");
        RemoteServer ftp = servers.ftp("ignorowana", ftpRoot);

        // Ustawienia → Serwery zdalne: nowe połączenie, test, zapis.
        robot.clickOn(navButton(robot, "Ustawienia"));
        robot.interact(() -> {
            textField(robot, "np. NAS").setText("NAS");
            textField(robot, "np. nas.local").setText("127.0.0.1");
            robot.lookup(".remote-servers .text-field").queryAllAs(TextField.class).stream()
                    .filter(f -> "21".equals(f.getPromptText())).findFirst().orElseThrow()
                    .setText(String.valueOf(ftp.port()));
            textField(robot, "puste = anonimowo").setText(TestServers.USER);
            robot.lookup(".remote-servers .password-field").queryAs(PasswordField.class).setText(TestServers.PASSWORD);
            button(robot, "Testuj połączenie").fire();
        });
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> result(robot).startsWith("Połączono"));
        assertTrue(result(robot).contains("Folder /: 1 pozycja"), result(robot));

        robot.interact(() -> button(robot, "Zapisz połączenie").fire());
        assertTrue(result(robot).startsWith("Zapisano połączenie „NAS”"), result(robot));
        assertEquals(ftp.port(), RemoteServers.find("NAS").orElseThrow().port());
        assertTrue(Files.readString(RemoteServers.file()).contains(TestServers.PASSWORD), "hasło zapisane w pliku");

        // Archiwizacja telefonu: folder archiwizowany na serwerze „NAS”.
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2020-01-01"));
        robot.clickOn(navButton(robot, "Archiwizacja telefonu"));
        WaitForAsyncUtils.waitForFxEvents();
        @SuppressWarnings("unchecked")
        List<ComboBox<String>> locations = robot.lookup(".phone-archive-view .folder-location")
                .queryAllAs(ComboBox.class).stream().map(c -> (ComboBox<String>) c).toList();
        assertEquals(4, locations.size(), "wybór miejsca przy każdym polu folderu");
        assertTrue(locations.get(0).isVisible());
        assertTrue(locations.get(0).getItems().contains("NAS"));

        List<TextField> fields = robot.lookup(".phone-archive-view .text-field").queryAllAs(TextField.class).stream()
                .filter(f -> f.getPromptText() != null && f.getPromptText().startsWith("Wskaż")).toList();
        robot.interact(() -> {
            locations.get(0).setValue("NAS");
            fields.get(0).setText("/upload");
            fields.get(1).setText(archive.toString());
            fields.get(2).setText(dir.resolve("Smietnik").toString());
        });
        assertEquals("Folder na serwerze, np. /zdjecia", fields.get(0).getPromptText());

        robot.clickOn(".action-bar .button-primary");
        try {
            WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS, () -> feedback(robot).startsWith("Zarchiwizowano"));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("Komunikat: " + feedback(robot) + " | okna: "
                    + robot.lookup(".dialog-title").queryAllAs(Label.class).stream().map(Label::getText).toList()
                    + " | " + robot.lookup(".dialog-message").queryAllAs(Label.class).stream().map(Label::getText).toList(), e);
        }
        Path day = archive.resolve(LocalDate.now().format(com.photon.phonearchive.PhoneArchiver.DAY_FORMAT));
        assertEquals("zdjęcie z serwera", Files.readString(day.resolve("IMG_1.jpg")));
        assertFalse(Files.exists(ftpRoot.resolve("upload/IMG_1.jpg")), "przeniesione z serwera");
        assertTrue(Files.readString(AppPaths.dataDir().resolve("phone-archive.json")).contains("[NAS]/upload"),
                "folder zapamiętany jako [NAS]/upload");
    }

    private static ToggleButton navButton(FxRobot robot, String title) {
        return robot.lookup(".nav-button").queryAllAs(ToggleButton.class).stream()
                .filter(b -> title.equals(b.getText())).findFirst().orElseThrow();
    }

    private static TextField textField(FxRobot robot, String promptStart) {
        return robot.lookup(".remote-servers .text-field").queryAllAs(TextField.class).stream()
                .filter(f -> f.getPromptText() != null && f.getPromptText().startsWith(promptStart))
                .findFirst().orElseThrow();
    }

    private static Button button(FxRobot robot, String text) {
        return robot.lookup(".remote-servers .button").queryAllAs(Button.class).stream()
                .filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
    }

    private static String result(FxRobot robot) {
        return robot.lookup(".remote-servers .action-feedback").queryAs(Label.class).getText();
    }

    private static String feedback(FxRobot robot) {
        return robot.lookup(".phone-archive-view .action-feedback").queryAs(Label.class).getText();
    }
}
