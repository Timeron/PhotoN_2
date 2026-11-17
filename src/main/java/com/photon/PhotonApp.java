package com.photon;

import com.photon.settings.AppSettings;
import com.photon.settings.AppState;
import com.photon.settings.SettingsService;
import com.photon.ui.Dialogs;
import com.photon.ui.LogoView;
import com.photon.ui.MainView;
import com.photon.ui.Section;
import com.photon.ui.window.ResizeHelper;
import com.photon.ui.window.TitleBar;
import com.photon.ui.window.WindowController;
import javafx.application.Application;
import javafx.css.PseudoClass;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.util.Objects;

public class PhotonApp extends Application {

    public static final String APP_NAME = "PhotoN";
    public static final String APP_VERSION = "2.0";

    private static final String THEME = "/com/photon/styles/dark-theme.css";
    private static final PseudoClass MAXIMIZED = PseudoClass.getPseudoClass("maximized");

    @Override
    public void start(Stage stage) {
        SettingsService settings = new SettingsService(AppPaths.settingsFile());
        JsonFileStore<AppState> stateStore =
                new JsonFileStore<>(AppPaths.stateFile(), AppState.class, AppState.empty());
        AppState state = stateStore.load();

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(APP_NAME + " " + APP_VERSION);

        WindowController window = new WindowController(stage);
        MainView mainView = new MainView(settings);

        BorderPane windowRoot = new BorderPane();
        windowRoot.getStyleClass().add("window-root");
        windowRoot.setTop(new TitleBar(window));
        windowRoot.setCenter(mainView);
        window.maximizedProperty().addListener(
                (obs, was, isMax) -> windowRoot.pseudoClassStateChanged(MAXIMIZED, isMax));

        Scene scene = new Scene(windowRoot, 1280, 800);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource(THEME), "Brak pliku motywu: " + THEME)
                        .toExternalForm());
        ResizeHelper.install(window, scene);

        // Te same rozmiary co w photon.ico (skrót na pulpicie) — Windows wybiera
        // najlepiej pasujący do skali ekranu (pasek zadań, Alt+Tab).
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(LogoView.createIconImage(size));
        }
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);

        AppSettings initial = settings.get();
        boolean restored = initial.rememberWindow() && restoreBounds(stage, state);
        if (!restored) {
            stage.centerOnScreen();
        }
        stage.show();
        if (restored && state.maximized()) {
            window.maximize();
        }

        showStartupSection(mainView, initial, state);

        window.setCloseGuard(() -> !settings.get().confirmExit()
                || Dialogs.confirm(stage, "Zamknąć PhotoN?",
                "Czy na pewno chcesz zamknąć aplikację?", "Zamknij", false));

        stage.setOnHidden(e -> saveState(stateStore, window, mainView));
    }

    private static void showStartupSection(MainView mainView, AppSettings settings, AppState state) {
        if (settings.startupView() == null) {
            return;
        }
        switch (settings.startupView()) {
            case LAST_SECTION -> {
                if (state.lastSection() != null) {
                    mainView.getSidebar().select(state.lastSection());
                }
            }
            case DUPLICATES -> mainView.getSidebar().select(Section.DUPLICATES);
            case PROFILES -> mainView.getSidebar().select(Section.PROFILES);
            case WELCOME -> {
                // Ekran powitalny jest widokiem domyślnym.
            }
        }
    }

    /** Przywraca zapisane położenie okna, jeśli nadal mieści się na którymś z ekranów. */
    private static boolean restoreBounds(Stage stage, AppState state) {
        if (!state.hasWindowBounds()) {
            return false;
        }
        double w = Math.max(stage.getMinWidth(), state.width());
        double h = Math.max(stage.getMinHeight(), state.height());
        // Wymagamy, aby na ekranie był widoczny przynajmniej górny pasek okna.
        if (Screen.getScreensForRectangle(state.x() + 40, state.y(), Math.max(1, w - 80), 40).isEmpty()) {
            return false;
        }
        stage.setX(state.x());
        stage.setY(state.y());
        stage.setWidth(w);
        stage.setHeight(h);
        return true;
    }

    private static void saveState(JsonFileStore<AppState> store, WindowController window, MainView mainView) {
        Rectangle2D bounds = window.getNormalBounds();
        AppState state = new AppState(
                mainView.getSidebar().selectedSectionProperty().get(),
                bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight(),
                window.isMaximized());
        try {
            store.save(state);
        } catch (IOException e) {
            System.err.println("Nie udało się zapisać stanu okna: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
