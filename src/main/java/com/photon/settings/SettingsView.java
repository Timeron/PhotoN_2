package com.photon.settings;

import com.photon.AppPaths;
import com.photon.PhotonApp;
import com.photon.ui.Desktops;
import com.photon.ui.Dialogs;
import com.photon.ui.FormSection;
import com.photon.ui.Texts;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.photon.ui.Forms.*;

/**
 * Formularz ustawień aplikacji.
 */
public class SettingsView extends BorderPane {

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    /** Podfoldery pamięci podręcznej tworzone przez aplikację — tylko one są czyszczone. */
    private static final List<String> CACHE_SUBFOLDERS = List.of("hashes", "thumbnails");

    private final SettingsService settings;

    // --- Ogólne ---
    private final ComboBox<StartupView> startupView =
            new ComboBox<>(FXCollections.observableArrayList(StartupView.values()));
    private final CheckBox rememberWindow = new CheckBox("Zapamiętuj rozmiar i położenie okna");
    private final CheckBox confirmExit = new CheckBox("Pytaj o potwierdzenie przed zamknięciem aplikacji");

    // --- Pliki i foldery ---
    private final ObjectProperty<Path> cacheDir = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> reportsDir = new SimpleObjectProperty<>();
    private final TextField cacheDirField = readOnlyField();
    private final TextField reportsDirField = readOnlyField();
    private final Label cacheSize = new Label();
    private final Button clearCache = new Button("Wyczyść", new FontIcon(MaterialDesignB.BROOM));

    // --- Bezpieczeństwo ---
    private final CheckBox confirmFileOps = new CheckBox("Zawsze pytaj przed usunięciem lub przeniesieniem plików");
    private final CheckBox useRecycleBin = new CheckBox("Usuwaj pliki do kosza systemowego zamiast trwale");
    private final CheckBox keepOperationLog =
            new CheckBox("Prowadź dziennik operacji na plikach (umożliwia cofnięcie zmian)");

    // --- Wydajność ---
    private final Spinner<Integer> threads = new Spinner<>();
    private final Spinner<Integer> cacheLimit = new Spinner<>(100, 100_000, 1024, 256);
    private final CheckBox lowPriority =
            new CheckBox("Obniżaj priorytet skanowania, aby komputer pozostał responsywny");

    // --- Powiadomienia ---
    private final CheckBox notifyOnFinish =
            new CheckBox("Pokazuj powiadomienie systemowe po zakończeniu długich operacji");
    private final CheckBox soundOnFinish = new CheckBox("Odtwarzaj dźwięk po zakończeniu skanowania");

    // --- Diagnostyka ---
    private final ComboBox<LogLevel> logLevel = new ComboBox<>(FXCollections.observableArrayList(LogLevel.values()));

    // --- Pasek akcji ---
    private final Label status = new Label();
    private final Label unsavedPill = new Label("Niezapisane zmiany");
    private final Label feedback = new Label();
    private final Button save = new Button("Zapisz", new FontIcon(MaterialDesignC.CONTENT_SAVE_OUTLINE));
    private final Button revert = new Button("Cofnij zmiany", new FontIcon(MaterialDesignR.RESTORE));

    private boolean suppressChanges;

    public SettingsView(SettingsService settings) {
        this.settings = settings;
        getStyleClass().add("settings-view");

        VBox form = new VBox(18,
                buildGeneralSection(),
                buildFilesSection(),
                buildSafetySection(),
                buildPerformanceSection(),
                buildNotificationsSection(),
                buildDiagnosticsSection(),
                buildAboutSection());
        form.getStyleClass().add("form");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        setCenter(scroll);
        setBottom(buildActionBar());

        installListeners();
        loadFrom(settings.get());
    }

    // =====================================================================
    // Sekcje
    // =====================================================================

    private Node buildGeneralSection() {
        startupView.setPrefWidth(320);
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Po uruchomieniu pokaż"), startupView);

        return new FormSection(MaterialDesignT.TUNE,
                "Ogólne",
                "Zachowanie aplikacji przy uruchamianiu i zamykaniu.",
                grid, rememberWindow, confirmExit);
    }

    private Node buildFilesSection() {
        TextField dataDirField = readOnlyField();
        dataDirField.setText(AppPaths.dataDir().toString());
        Button openData = openFolderButton(AppPaths::dataDir);

        Button chooseCache = new Button("Wybierz…", new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
        chooseCache.setOnAction(e -> chooseFolder("Folder pamięci podręcznej").ifPresent(cacheDir::set));
        Button resetCache = iconButton(MaterialDesignR.RESTORE, "Przywróć folder domyślny");
        resetCache.setOnAction(e -> cacheDir.set(null));
        resetCache.disableProperty().bind(cacheDir.isNull());

        cacheSize.getStyleClass().add("field-hint");
        clearCache.setTooltip(new Tooltip("Usuń zapamiętane sumy kontrolne i miniatury"));
        clearCache.setOnAction(e -> clearCache());
        HBox cacheInfo = new HBox(12, cacheSize, clearCache);
        cacheInfo.setAlignment(Pos.CENTER_LEFT);

        Button chooseReports = new Button("Wybierz…", new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
        chooseReports.setOnAction(e -> chooseFolder("Folder raportów").ifPresent(reportsDir::set));
        Button resetReports = iconButton(MaterialDesignR.RESTORE, "Przywróć folder domyślny");
        resetReports.setOnAction(e -> reportsDir.set(null));
        resetReports.disableProperty().bind(reportsDir.isNull());

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Folder danych aplikacji"), pathRow(dataDirField, openData));
        grid.addRow(1, fieldLabel("Pamięć podręczna"), pathRow(cacheDirField, chooseCache, resetCache));
        grid.add(cacheInfo, 1, 2);
        grid.addRow(3, fieldLabel("Folder raportów"), pathRow(reportsDirField, chooseReports, resetReports));

        return new FormSection(MaterialDesignF.FOLDER_COG_OUTLINE,
                "Pliki i foldery",
                "Gdzie PhotoN przechowuje profile, pamięć podręczną i raporty.",
                grid,
                hint("Pamięć podręczna przechowuje sumy kontrolne i miniatury, dzięki czemu kolejne skanowania "
                        + "są szybsze. Można ją bezpiecznie wyczyścić."));
    }

    private Node buildSafetySection() {
        FontIcon infoIcon = new FontIcon(MaterialDesignI.INFORMATION_OUTLINE);
        Label banner = new Label("Zalecamy pozostawienie wszystkich opcji włączonych — chronią przed "
                + "przypadkową utratą zdjęć i filmów.", infoIcon);
        banner.getStyleClass().add("info-banner");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);

        return new FormSection(MaterialDesignS.SHIELD_CHECK_OUTLINE,
                "Bezpieczeństwo plików",
                "Zabezpieczenia przy operacjach, które zmieniają lub usuwają pliki.",
                confirmFileOps, useRecycleBin, keepOperationLog, banner);
    }

    private Node buildPerformanceSection() {
        SpinnerValueFactory.IntegerSpinnerValueFactory threadsFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, CPU_CORES * 4, 0);
        threadsFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null || value == 0 ? "Auto" : value.toString();
            }

            @Override
            public Integer fromString(String text) {
                String t = text == null ? "" : text.trim();
                return t.isEmpty() || t.equalsIgnoreCase("auto") ? 0 : Integer.parseInt(t);
            }
        });
        threads.setValueFactory(threadsFactory);
        setupSpinner(threads);
        setupSpinner(cacheLimit);

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Domyślna liczba wątków"),
                withSuffix(threads, "", "Auto = liczba rdzeni procesora (" + CPU_CORES + ")"));
        grid.addRow(1, fieldLabel("Limit pamięci podręcznej"),
                withSuffix(cacheLimit, "MB", "Najstarsze wpisy są usuwane po przekroczeniu limitu"));

        return new FormSection(MaterialDesignS.SPEEDOMETER,
                "Wydajność",
                "Domyślne wartości dla nowych skanowań i zasoby używane przez aplikację.",
                grid, lowPriority);
    }

    private Node buildNotificationsSection() {
        return new FormSection(MaterialDesignB.BELL_OUTLINE,
                "Powiadomienia",
                "Informowanie o zakończeniu operacji, gdy pracujesz w innym oknie.",
                notifyOnFinish, soundOnFinish);
    }

    private Node buildDiagnosticsSection() {
        logLevel.setPrefWidth(320);
        TextField logsField = readOnlyField();
        logsField.setText(AppPaths.logsDir().toString());

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Poziom logowania"), logLevel);
        grid.addRow(1, fieldLabel("Folder logów"), pathRow(logsField, openFolderButton(AppPaths::logsDir)));

        return new FormSection(MaterialDesignB.BUG_OUTLINE,
                "Diagnostyka",
                "Dziennik zdarzeń pomocny przy zgłaszaniu problemów.",
                grid);
    }

    private Node buildAboutSection() {
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Wersja"), value(PhotonApp.APP_NAME + " " + PhotonApp.APP_VERSION));
        grid.addRow(1, fieldLabel("Java"), value(System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")"));
        grid.addRow(2, fieldLabel("JavaFX"), value(System.getProperty("javafx.version")));
        grid.addRow(3, fieldLabel("System"), value(System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"));
        grid.addRow(4, fieldLabel("Plik ustawień"), value(settings.getFile().toString()));

        return new FormSection(MaterialDesignI.INFORMATION_OUTLINE,
                "O programie",
                "PhotoN 2.0 — porządkowanie kolekcji zdjęć i filmów.",
                grid);
    }

    private Node buildActionBar() {
        status.getStyleClass().add("action-summary");
        unsavedPill.getStyleClass().addAll("pill", "pill-warning");
        unsavedPill.managedProperty().bind(unsavedPill.visibleProperty());
        HBox statusRow = new HBox(10, unsavedPill, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        feedback.getStyleClass().add("action-feedback");
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
        feedback.visibleProperty().bind(feedback.managedProperty());
        VBox texts = new VBox(2, statusRow, feedback);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Button defaults = new Button("Przywróć domyślne", new FontIcon(MaterialDesignR.RESTORE_ALERT));
        defaults.setOnAction(e -> {
            loadFrom(AppSettings.defaults());
            showFeedback("Przywrócono wartości domyślne. Kliknij „Zapisz”, aby je zastosować.", false);
        });
        revert.setOnAction(e -> {
            loadFrom(settings.get());
            showFeedback("Cofnięto niezapisane zmiany.", false);
        });
        save.getStyleClass().add("button-primary");
        save.setDefaultButton(true);
        save.setOnAction(e -> save());

        HBox bar = new HBox(12, texts, defaults, revert, save);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("action-bar");
        BorderPane.setMargin(bar, new Insets(14, 24, 0, 0));
        return bar;
    }

    // =====================================================================
    // Logika
    // =====================================================================

    private void installListeners() {
        ChangeListener<Object> changed = (obs, old, value) -> onFormChanged();
        for (CheckBox box : List.of(rememberWindow, confirmExit, confirmFileOps, useRecycleBin, keepOperationLog,
                lowPriority, notifyOnFinish, soundOnFinish)) {
            box.selectedProperty().addListener(changed);
        }
        startupView.valueProperty().addListener(changed);
        logLevel.valueProperty().addListener(changed);
        threads.valueProperty().addListener(changed);
        cacheLimit.valueProperty().addListener(changed);
        cacheDir.addListener(changed);
        reportsDir.addListener(changed);

        cacheDir.addListener((obs, old, dir) -> {
            showPath(cacheDirField, dir, AppPaths.defaultCacheDir());
            refreshCacheSize();
        });
        reportsDir.addListener((obs, old, dir) -> showPath(reportsDirField, dir, AppPaths.defaultReportsDir()));
    }

    private void loadFrom(AppSettings s) {
        suppressChanges = true;
        try {
            startupView.setValue(s.startupView() == null ? StartupView.WELCOME : s.startupView());
            rememberWindow.setSelected(s.rememberWindow());
            confirmExit.setSelected(s.confirmExit());
            cacheDir.set(s.cacheDir());
            reportsDir.set(s.reportsDir());
            confirmFileOps.setSelected(s.confirmFileOperations());
            useRecycleBin.setSelected(s.useRecycleBin());
            keepOperationLog.setSelected(s.keepOperationLog());
            threads.getValueFactory().setValue(Math.max(0, Math.min(CPU_CORES * 4, s.defaultThreads())));
            cacheLimit.getValueFactory().setValue(s.cacheLimitMb());
            lowPriority.setSelected(s.lowPriorityScan());
            notifyOnFinish.setSelected(s.notifyOnFinish());
            soundOnFinish.setSelected(s.soundOnFinish());
            logLevel.setValue(s.logLevel() == null ? LogLevel.INFO : s.logLevel());
        } finally {
            suppressChanges = false;
        }
        // Pola ścieżek muszą być odświeżone także wtedy, gdy wartość się nie zmieniła (pierwsze wczytanie).
        showPath(cacheDirField, cacheDir.get(), AppPaths.defaultCacheDir());
        showPath(reportsDirField, reportsDir.get(), AppPaths.defaultReportsDir());
        refreshCacheSize();
        onFormChanged();
    }

    private AppSettings buildSettings() {
        return new AppSettings(
                startupView.getValue(),
                rememberWindow.isSelected(),
                confirmExit.isSelected(),
                cacheDir.get(),
                reportsDir.get(),
                confirmFileOps.isSelected(),
                useRecycleBin.isSelected(),
                keepOperationLog.isSelected(),
                threads.getValue(),
                lowPriority.isSelected(),
                cacheLimit.getValue(),
                notifyOnFinish.isSelected(),
                soundOnFinish.isSelected(),
                logLevel.getValue());
    }

    private void onFormChanged() {
        if (suppressChanges) {
            return;
        }
        boolean dirty = !buildSettings().equals(settings.get());
        unsavedPill.setVisible(dirty);
        status.setText(dirty ? "Zmiany zaczną obowiązywać po zapisaniu." : "Wszystkie zmiany są zapisane.");
        save.setDisable(!dirty);
        revert.setDisable(!dirty);
    }

    private void save() {
        commitSpinner(threads);
        commitSpinner(cacheLimit);
        try {
            settings.save(buildSettings());
            showFeedback("Zapisano ustawienia.", false);
        } catch (IOException e) {
            Dialogs.error(getScene().getWindow(), "Błąd zapisu",
                    "Nie udało się zapisać ustawień w " + settings.getFile() + ":\n" + e.getMessage());
        }
        onFormChanged();
    }

    private void showFeedback(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
    }

    // =====================================================================
    // Pamięć podręczna
    // =====================================================================

    private Path effectiveCacheDir() {
        return cacheDir.get() != null ? cacheDir.get() : AppPaths.defaultCacheDir();
    }

    private void refreshCacheSize() {
        Path dir = effectiveCacheDir();
        cacheSize.setText("Obliczanie rozmiaru…");
        clearCache.setDisable(true);
        Task<Long> task = new Task<>() {
            @Override
            protected Long call() throws IOException {
                long total = 0;
                for (String sub : CACHE_SUBFOLDERS) {
                    Path folder = dir.resolve(sub);
                    if (Files.isDirectory(folder)) {
                        try (Stream<Path> files = Files.walk(folder)) {
                            total += files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
                        }
                    }
                }
                return total;
            }
        };
        task.setOnSucceeded(e -> {
            if (dir.equals(effectiveCacheDir())) {
                long bytes = task.getValue();
                cacheSize.setText("Zajmuje: " + Texts.formatBytes(bytes));
                clearCache.setDisable(bytes == 0);
            }
        });
        task.setOnFailed(e -> cacheSize.setText("Nie udało się obliczyć rozmiaru."));
        startDaemon(task, "cache-size");
    }

    private void clearCache() {
        Path dir = effectiveCacheDir();
        if (!Dialogs.confirm(getScene().getWindow(), "Wyczyść pamięć podręczną",
                "Usunąć zapamiętane sumy kontrolne i miniatury z folderu " + dir + "? "
                        + "Zdjęcia i filmy nie zostaną zmienione — kolejne skanowanie potrwa jedynie dłużej.",
                "Wyczyść", true)) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                // Usuwamy wyłącznie podfoldery tworzone przez aplikację — nigdy całego wskazanego folderu.
                for (String sub : CACHE_SUBFOLDERS) {
                    Path folder = dir.resolve(sub);
                    if (Files.isDirectory(folder)) {
                        try (Stream<Path> files = Files.walk(folder)) {
                            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                                Files.deleteIfExists(p);
                            }
                        }
                    }
                }
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            showFeedback("Wyczyszczono pamięć podręczną.", false);
            refreshCacheSize();
        });
        task.setOnFailed(e -> {
            showFeedback("Nie udało się wyczyścić pamięci podręcznej: " + task.getException().getMessage(), true);
            refreshCacheSize();
        });
        startDaemon(task, "cache-clear");
    }

    private static void startDaemon(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    // =====================================================================
    // Pomocnicze
    // =====================================================================

    private static void showPath(TextField field, Path value, Path defaultValue) {
        field.setText(value == null ? "" : value.toString());
        field.setPromptText(defaultValue + "  (domyślny)");
    }

    private static HBox pathRow(TextField field, Button... buttons) {
        HBox row = new HBox(8, field);
        row.getChildren().addAll(buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private Button openFolderButton(java.util.function.Supplier<Path> folder) {
        Button button = iconButton(MaterialDesignF.FOLDER_OPEN_OUTLINE, "Otwórz folder w Eksploratorze");
        button.setOnAction(e -> Desktops.openFolder(folder.get(),
                error -> showFeedback("Nie udało się otworzyć folderu: " + error, true)));
        return button;
    }

    private static Label value(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-value");
        label.setWrapText(true);
        return label;
    }

    private java.util.Optional<Path> chooseFolder(String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        File dir = chooser.showDialog(getScene().getWindow());
        return java.util.Optional.ofNullable(dir).map(File::toPath);
    }
}
