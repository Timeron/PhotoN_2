package com.photon.help;

import com.photon.AppPaths;
import com.photon.settings.SettingsService;
import com.photon.ui.MainView;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sekcja „Pomoc” w aplikacji (GUI bez ekranu). */
@ExtendWith(ApplicationExtension.class)
class HelpViewUiTest {

    static {
        if (System.getProperty(AppPaths.DATA_DIR_PROPERTY) == null) {
            try {
                System.setProperty(AppPaths.DATA_DIR_PROPERTY, Files.createTempDirectory("photon-ui-test").toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Start
    void start(Stage stage) {
        Scene scene = new Scene(new MainView(new SettingsService(AppPaths.settingsFile())), 1400, 850);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/photon/styles/dark-theme.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void f1OpensManualWithTableOfContentsSearchAndLinks(FxRobot robot) throws Exception {
        robot.press(KeyCode.F1).release(KeyCode.F1);
        WaitForAsyncUtils.waitForFxEvents();
        HelpView help = robot.lookup(".help-view").queryAs(HelpView.class);
        assertTrue(help.tocTitles().contains("5. Funkcje aplikacji"), help.tocTitles().toString());
        assertTrue(robot.lookup(".help-h2").queryAll().size() >= 5, "rozdziały wyświetlone");

        // Spis treści / odnośnik → przewinięcie w dół.
        robot.interact(() -> help.openSection("6-gdzie-aplikacja-trzyma-dane"));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> help.scrollPosition() > 0.3);

        // Wyszukiwanie bez polskich znaków: lista wyników zamiast spisu treści, licznik, przejście do trafienia.
        robot.interact(() -> help.search("zrodlo"));
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(help.hits().isEmpty());
        assertTrue(robot.lookup(".help-results").query().isVisible());
        assertFalse(robot.lookup(".help-toc").query().isVisible());
        assertTrue(robot.lookup(".help-hit-count").queryAs(Label.class).getText().startsWith("1 / "));

        robot.interact(() -> robot.lookup(".help-search").queryAs(TextField.class).clear());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(robot.lookup(".help-toc").query().isVisible(), "pusty filtr — znowu spis treści");
        assertEquals("", robot.lookup(".help-hit-count").queryAs(Label.class).getText());
    }
}
