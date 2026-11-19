package com.photon.duplicates;

import com.photon.profiles.ProfileBar;
import com.photon.ui.FormSection;
import com.photon.ui.Texts;

import static com.photon.ui.Forms.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import com.photon.remote.Locations;
import com.photon.remote.RemoteServers;
import com.photon.ui.Dialogs;
import com.photon.ui.FolderField;
import javafx.stage.DirectoryChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Formularz konfiguracji wyszukiwania duplikatów zdjęć i filmów.
 */
public class DuplicateSearchView extends BorderPane {

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final IntSupplier defaultThreads;

    // --- Profile ---
    private final DuplicateProfileStore profileStore;
    private final ProfileBar<DuplicateSearchOptions> profileBar;
    /** Blokuje odświeżanie stanu podczas masowego ustawiania pól formularza. */
    private boolean suppressChanges;

    // --- Lokalizacje ---
    private final ObservableList<Path> locations = FXCollections.observableArrayList();
    private final ObservableList<Path> exclusions = FXCollections.observableArrayList();
    private final CheckBox includeSubfolders = new CheckBox("Przeszukuj również podfoldery");
    private final CheckBox skipHidden = new CheckBox("Pomijaj ukryte i systemowe foldery oraz pliki");
    private final CheckBox crossLocationOnly = new CheckBox("Szukaj tylko duplikatów pomiędzy różnymi lokalizacjami");

    // --- Formaty ---
    private final Map<MediaFormat, ToggleButton> formatChips = new EnumMap<>(MediaFormat.class);
    private final TextField customExtensions = new TextField();

    // --- Metoda ---
    private final ToggleGroup methodGroup = new ToggleGroup();
    private final Map<SearchMethod, RadioButton> methodRadios = new EnumMap<>(SearchMethod.class);
    private final Slider similarity = new Slider(70, 100, 90);

    // --- Filtry ---
    private final Spinner<Integer> minSizeKb = new Spinner<>(0, 10_000_000, 10, 10);
    private final Spinner<Integer> maxSizeMb = new Spinner<>(0, 1_000_000, 0, 100);
    private final DatePicker dateFrom = new DatePicker();
    private final DatePicker dateTo = new DatePicker();

    // --- Wyniki ---
    private final ComboBox<KeepStrategy> keepStrategy =
            new ComboBox<>(FXCollections.observableArrayList(KeepStrategy.values()));
    private final ComboBox<DuplicateAction> action =
            new ComboBox<>(FXCollections.observableArrayList(DuplicateAction.values()));
    private final TextField moveTarget = new TextField();
    private FolderField moveFolder;
    private final CheckBox saveReport = new CheckBox("Zapisz raport z wynikami do pliku CSV");

    // --- Wydajność ---
    private final Spinner<Integer> threads = new Spinner<>(1, CPU_CORES * 2, CPU_CORES);
    private final CheckBox hashCache = new CheckBox("Zapamiętuj sumy kontrolne (kolejne skanowania będą szybsze)");
    private final CheckBox skipLinks = new CheckBox("Pomijaj dowiązania symboliczne i skróty");

    // --- Pasek akcji ---
    private final Label summary = new Label();
    private final Label feedback = new Label();
    private final Button startButton = new Button("Rozpocznij skanowanie", new FontIcon(MaterialDesignP.PLAY));
    private final Button lastResultsButton = new Button("Ostatnie wyniki", new FontIcon(MaterialDesignF.FORMAT_LIST_BULLETED));

    private Consumer<DuplicateSearchOptions> onStartScan =
            options -> showFeedback("Konfiguracja gotowa. Silnik skanowania zostanie dodany w kolejnym etapie.", false);

    public DuplicateSearchView() {
        this(DuplicateProfileStore.createDefault(), () -> CPU_CORES);
    }

    /**
     * @param defaultThreads dostarcza domyślną liczbę wątków (z ustawień aplikacji)
     *                       — używaną przy „Przywróć domyślne”
     */
    public DuplicateSearchView(DuplicateProfileStore profileStore, IntSupplier defaultThreads) {
        this.profileStore = profileStore;
        this.defaultThreads = defaultThreads;
        this.profileBar = new ProfileBar<>(profileStore, this::buildOptions, this::applyOptions,
                this::showFeedback, "wyszukiwania");
        BorderPane.setMargin(profileBar, new Insets(0, 24, 14, 0));
        getStyleClass().add("duplicate-search-view");

        VBox form = new VBox(18,
                buildLocationsSection(),
                buildFormatsSection(),
                buildMethodSection(),
                buildFiltersSection(),
                buildResultsSection(),
                buildPerformanceSection());
        form.getStyleClass().add("form");

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");

        setTop(profileBar);
        setCenter(scroll);
        setBottom(buildActionBar());

        installListeners();
        applyDefaults();
        reloadProfiles();
    }

    /** Akcja wywoływana po kliknięciu „Rozpocznij skanowanie” z poprawną konfiguracją. */
    public void setOnStartScan(Consumer<DuplicateSearchOptions> handler) {
        this.onStartScan = handler;
    }

    // =====================================================================
    // Sekcje formularza
    // =====================================================================

    private Node buildLocationsSection() {
        ListView<Path> locationList = folderList(locations,
                "Przeciągnij tutaj foldery z Eksploratora lub kliknij „Dodaj folder”");
        locationList.setPrefHeight(170);

        Button add = new Button("Dodaj folder", new FontIcon(MaterialDesignF.FOLDER_PLUS_OUTLINE));
        add.setOnAction(e -> chooseFolder("Wybierz folder do przeskanowania")
                .ifPresent(p -> addUnique(locations, p)));

        Button up = iconButton(MaterialDesignA.ARROW_UP, "Przenieś wyżej (wyższy priorytet)");
        up.setOnAction(e -> moveSelected(locationList, -1));
        Button down = iconButton(MaterialDesignA.ARROW_DOWN, "Przenieś niżej (niższy priorytet)");
        down.setOnAction(e -> moveSelected(locationList, 1));
        Button remove = iconButton(MaterialDesignD.DELETE_OUTLINE, "Usuń zaznaczoną lokalizację");
        remove.setOnAction(e -> locations.remove(locationList.getSelectionModel().getSelectedItem()));
        var noSelection = locationList.getSelectionModel().selectedItemProperty().isNull();
        up.disableProperty().bind(noSelection);
        down.disableProperty().bind(noSelection);
        remove.disableProperty().bind(noSelection);

        Button addRemote = remoteButton("Z serwera…", "Dodaj folder z serwera zdalnego (Ustawienia → Serwery zdalne)");
        addRemote.setOnAction(e -> Dialogs.chooseServerFolder(getScene().getWindow())
                .ifPresent(p -> addUnique(locations, p)));
        HBox toolbar = new HBox(8, add, addRemote, spacer(), up, down, remove);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Wykluczenia
        ListView<Path> exclusionList = folderList(exclusions, "Brak wykluczonych folderów");
        exclusionList.setPrefHeight(96);

        Button addExclusion = new Button("Wyklucz folder", new FontIcon(MaterialDesignF.FOLDER_REMOVE_OUTLINE));
        addExclusion.setOnAction(e -> chooseFolder("Wybierz folder do pominięcia")
                .ifPresent(p -> addUnique(exclusions, p)));
        Button removeExclusion = iconButton(MaterialDesignD.DELETE_OUTLINE, "Usuń zaznaczone wykluczenie");
        removeExclusion.setOnAction(e -> exclusions.remove(exclusionList.getSelectionModel().getSelectedItem()));
        removeExclusion.disableProperty().bind(exclusionList.getSelectionModel().selectedItemProperty().isNull());

        Button excludeRemote = remoteButton("Z serwera…", "Wyklucz folder na serwerze zdalnym");
        excludeRemote.setOnAction(e -> Dialogs.chooseServerFolder(getScene().getWindow())
                .ifPresent(p -> addUnique(exclusions, p)));
        HBox exclusionToolbar = new HBox(8, fieldLabel("Foldery wykluczone ze skanowania"), spacer(),
                addExclusion, excludeRemote, removeExclusion);
        exclusionToolbar.setAlignment(Pos.CENTER_LEFT);

        VBox options = new VBox(10, includeSubfolders, skipHidden, crossLocationOnly);

        return new FormSection(MaterialDesignF.FOLDER_SEARCH_OUTLINE,
                "Lokalizacje do przeskanowania",
                "Foldery lub całe dyski, w których szukać duplikatów. Kolejność na liście określa priorytet "
                        + "przy wyborze oryginału.",
                toolbar, locationList, options, exclusionToolbar, exclusionList);
    }

    private Node buildFormatsSection() {
        customExtensions.setPromptText("np. psd, xcf, insv");
        HBox customRow = new HBox(12, fieldLabel("Dodatkowe rozszerzenia"), customExtensions);
        customRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(customExtensions, Priority.ALWAYS);

        return new FormSection(MaterialDesignF.FILE_MULTIPLE_OUTLINE,
                "Formaty plików",
                "Rodzaje zdjęć i filmów brane pod uwagę. Najedź na format, aby zobaczyć obsługiwane rozszerzenia.",
                formatGroup("Zdjęcia", MaterialDesignI.IMAGE_OUTLINE, MediaFormat.Kind.PHOTO),
                formatGroup("Filmy", MaterialDesignV.VIDEO_OUTLINE, MediaFormat.Kind.VIDEO),
                customRow,
                hint("Rozdziel przecinkami lub spacjami. Kropka na początku nie jest wymagana."));
    }

    private Node formatGroup(String title, Ikon icon, MediaFormat.Kind kind) {
        FontIcon groupIcon = new FontIcon(icon);
        groupIcon.getStyleClass().add("group-icon");
        Label label = new Label(title, groupIcon);
        label.getStyleClass().add("field-label");

        Button all = linkButton("Zaznacz wszystkie");
        all.setOnAction(e -> setKindSelected(kind, true));
        Button none = linkButton("Odznacz");
        none.setOnAction(e -> setKindSelected(kind, false));

        HBox header = new HBox(10, label, spacer(), all, none);
        header.setAlignment(Pos.CENTER_LEFT);

        FlowPane chips = new FlowPane(8, 8);
        for (MediaFormat format : MediaFormat.values()) {
            if (format.getKind() == kind) {
                chips.getChildren().add(formatChip(format));
            }
        }
        return new VBox(10, header, chips);
    }

    private ToggleButton formatChip(MediaFormat format) {
        FontIcon check = new FontIcon(MaterialDesignC.CHECK);
        ToggleButton chip = new ToggleButton(format.getLabel(), check);
        chip.getStyleClass().add("chip");
        check.visibleProperty().bind(chip.selectedProperty());
        check.managedProperty().bind(chip.selectedProperty());
        chip.setTooltip(new Tooltip("Rozszerzenia: ." + String.join(", .", format.getExtensions())));
        formatChips.put(format, chip);
        return chip;
    }

    private Node buildMethodSection() {
        GridPane cards = new GridPane();
        cards.setHgap(12);
        cards.setVgap(12);
        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        half.setFillWidth(true);
        cards.getColumnConstraints().addAll(half, half);

        SearchMethod[] methods = SearchMethod.values();
        for (int i = 0; i < methods.length; i++) {
            cards.add(methodCard(methods[i]), i % 2, i / 2);
        }

        Label value = new Label();
        value.getStyleClass().add("slider-value");
        value.textProperty().bind(similarity.valueProperty().asString("%.0f%%"));
        similarity.setMajorTickUnit(5);
        similarity.setMinorTickCount(4);
        similarity.setSnapToTicks(true);
        similarity.setBlockIncrement(1);
        HBox.setHgrow(similarity, Priority.ALWAYS);

        HBox similarityRow = new HBox(14, fieldLabel("Próg podobieństwa"), similarity, value);
        similarityRow.setAlignment(Pos.CENTER_LEFT);
        Label similarityHint = hint("Niższy próg = więcej wyników, ale także więcej fałszywych dopasowań.");
        VBox similarityBox = new VBox(6, similarityRow, similarityHint);
        similarityBox.disableProperty().bind(methodRadios.get(SearchMethod.SIMILAR).selectedProperty().not());

        return new FormSection(MaterialDesignM.MAGNIFY_SCAN,
                "Sposób wyszukiwania",
                "Jak rozpoznawać, że dwa pliki są duplikatami.",
                cards, similarityBox);
    }

    private Node methodCard(SearchMethod method) {
        RadioButton radio = new RadioButton();
        radio.setToggleGroup(methodGroup);
        radio.setUserData(method);
        methodRadios.put(method, radio);

        FontIcon icon = new FontIcon(method.getIcon());
        icon.getStyleClass().add("option-card-icon");
        Label title = new Label(method.getTitle());
        title.getStyleClass().add("option-card-title");

        HBox head = new HBox(10, radio, icon, title);
        head.setAlignment(Pos.CENTER_LEFT);
        if (method == SearchMethod.EXACT) {
            Label pill = new Label("Zalecane");
            pill.getStyleClass().add("pill");
            head.getChildren().addAll(spacer(), pill);
        }

        Label description = new Label(method.getDescription());
        description.getStyleClass().add("option-card-description");
        description.setWrapText(true);

        VBox card = new VBox(8, head, description);
        card.getStyleClass().add("option-card");
        card.setMaxHeight(Double.MAX_VALUE);
        card.setOnMouseClicked(e -> radio.setSelected(true));
        radio.selectedProperty().addListener((obs, was, is) -> card.pseudoClassStateChanged(SELECTED, is));
        GridPane.setHgrow(card, Priority.ALWAYS);
        GridPane.setVgrow(card, Priority.ALWAYS);
        return card;
    }

    private Node buildFiltersSection() {
        setupSpinner(minSizeKb);
        setupSpinner(maxSizeMb);
        dateFrom.setPromptText("bez ograniczeń");
        dateTo.setPromptText("bez ograniczeń");

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Minimalny rozmiar pliku"),
                withSuffix(minSizeKb, "KB", "Pomija ikony i małe miniatury"));
        grid.addRow(1, fieldLabel("Maksymalny rozmiar pliku"),
                withSuffix(maxSizeMb, "MB", "0 = bez limitu"));

        Label dash = new Label("—");
        dash.getStyleClass().add("field-hint");
        HBox dates = new HBox(10, dateFrom, dash, dateTo);
        dates.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(2, fieldLabel("Data modyfikacji pliku"), dates);

        return new FormSection(MaterialDesignF.FILTER_OUTLINE,
                "Filtry",
                "Ogranicz skanowanie do plików o określonym rozmiarze lub dacie.",
                grid);
    }

    private Node buildResultsSection() {
        keepStrategy.setMaxWidth(Double.MAX_VALUE);
        action.setMaxWidth(Double.MAX_VALUE);

        moveFolder = new FolderField(moveTarget, "Wybierz folder, do którego trafią duplikaty", "Folder na duplikaty");
        HBox targetRow = moveFolder;

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Oryginał do zachowania"), keepStrategy);
        grid.addRow(1, fieldLabel("Co zrobić z duplikatami"), action);
        Label targetLabel = fieldLabel("Folder docelowy");
        grid.addRow(2, targetLabel, targetRow);

        var moveSelected = action.valueProperty().isEqualTo(DuplicateAction.MOVE_TO_FOLDER);
        for (Node n : List.of(targetLabel, targetRow)) {
            n.visibleProperty().bind(moveSelected);
            n.managedProperty().bind(moveSelected);
        }

        FontIcon infoIcon = new FontIcon(MaterialDesignI.INFORMATION_OUTLINE);
        Label safety = new Label("Domyślnie nic nie jest usuwane — przed jakąkolwiek zmianą zobaczysz listę "
                + "znalezionych duplikatów.", infoIcon);
        safety.getStyleClass().add("info-banner");
        safety.setWrapText(true);
        safety.setMaxWidth(Double.MAX_VALUE);

        return new FormSection(MaterialDesignC.CLIPBOARD_CHECK_OUTLINE,
                "Wyniki i działania",
                "Który plik uznać za oryginał i co zrobić z pozostałymi kopiami.",
                grid, saveReport, safety);
    }

    private Node buildPerformanceSection() {
        setupSpinner(threads);
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Liczba wątków"),
                withSuffix(threads, "", "Wykryto rdzeni procesora: " + CPU_CORES));

        return new FormSection(MaterialDesignS.SPEEDOMETER,
                "Wydajność",
                "Ustawienia wpływające na szybkość skanowania dużych kolekcji.",
                grid, hashCache, skipLinks);
    }

    private Node buildActionBar() {
        summary.getStyleClass().add("action-summary");
        feedback.getStyleClass().add("action-feedback");
        feedback.setWrapText(true);
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
        feedback.visibleProperty().bind(feedback.managedProperty());
        VBox texts = new VBox(2, summary, feedback);
        HBox.setHgrow(texts, Priority.ALWAYS);

        Button reset = new Button("Przywróć domyślne", new FontIcon(MaterialDesignR.RESTORE));
        reset.setOnAction(e -> applyDefaults());

        startButton.getStyleClass().add("button-primary");
        startButton.setDefaultButton(true);
        startButton.setOnAction(e -> start());

        lastResultsButton.setVisible(false);
        lastResultsButton.managedProperty().bind(lastResultsButton.visibleProperty());

        HBox bar = new HBox(12, texts, lastResultsButton, reset, startButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("action-bar");
        // Prawy margines = wcięcie formularza (14) + pasek przewijania (10), żeby krawędzie się zgadzały.
        BorderPane.setMargin(bar, new Insets(14, 24, 0, 0));
        return bar;
    }

    // =====================================================================
    // Logika formularza
    // =====================================================================

    /** Każda zmiana w formularzu odświeża podsumowanie i stan „niezapisane zmiany”. */
    private void installListeners() {
        ChangeListener<Object> changed = (obs, old, value) -> onFormChanged();
        ListChangeListener<Path> listChanged = c -> onFormChanged();

        locations.addListener(listChanged);
        exclusions.addListener(listChanged);
        for (CheckBox box : List.of(includeSubfolders, skipHidden, crossLocationOnly, saveReport, hashCache, skipLinks)) {
            box.selectedProperty().addListener(changed);
        }
        formatChips.values().forEach(chip -> chip.selectedProperty().addListener(changed));
        customExtensions.textProperty().addListener(changed);
        methodGroup.selectedToggleProperty().addListener(changed);
        similarity.valueProperty().addListener(changed);
        minSizeKb.valueProperty().addListener(changed);
        maxSizeMb.valueProperty().addListener(changed);
        threads.valueProperty().addListener(changed);
        dateFrom.valueProperty().addListener(changed);
        dateTo.valueProperty().addListener(changed);
        keepStrategy.valueProperty().addListener(changed);
        action.valueProperty().addListener(changed);
        moveFolder.pathProperty().addListener(changed);
    }

    private void onFormChanged() {
        if (suppressChanges) {
            return;
        }
        refreshSummary();
        profileBar.refreshState();
    }

    private void applyDefaults() {
        suppressChanges = true;
        try {
            resetFieldsToDefaults();
        } finally {
            suppressChanges = false;
        }
        showFeedback("", false);
        onFormChanged();
    }

    private void resetFieldsToDefaults() {
        exclusions.clear();
        includeSubfolders.setSelected(true);
        skipHidden.setSelected(true);
        crossLocationOnly.setSelected(false);

        formatChips.forEach((format, chip) -> chip.setSelected(format.isSelectedByDefault()));
        customExtensions.clear();

        methodRadios.get(SearchMethod.EXACT).setSelected(true);
        similarity.setValue(90);

        minSizeKb.getValueFactory().setValue(10);
        maxSizeMb.getValueFactory().setValue(0);
        dateFrom.setValue(null);
        dateTo.setValue(null);

        keepStrategy.setValue(KeepStrategy.OLDEST);
        action.setValue(DuplicateAction.REPORT_ONLY);
        moveTarget.clear();
        saveReport.setSelected(false);

        threads.getValueFactory().setValue(Math.max(1, Math.min(CPU_CORES * 2, defaultThreads.getAsInt())));
        hashCache.setSelected(true);
        skipLinks.setSelected(true);

        // Lokalizacje celowo zostają — to najbardziej pracochłonna część formularza.
    }

    private void refreshSummary() {
        int formats = collectExtensions().size();
        SearchMethod method = selectedMethod();

        String problem = null;
        if (locations.isEmpty()) {
            problem = "Dodaj co najmniej jedną lokalizację do przeskanowania.";
        } else if (formats == 0) {
            problem = "Zaznacz co najmniej jeden format pliku.";
        } else if (action.getValue() == DuplicateAction.MOVE_TO_FOLDER && moveTarget.getText().isBlank()) {
            problem = "Wybierz folder docelowy dla duplikatów.";
        }

        startButton.setDisable(problem != null);
        if (problem != null) {
            summary.setText(problem);
            summary.pseudoClassStateChanged(ERROR, true);
        } else {
            summary.setText(Texts.count(locations.size(), "lokalizacja", "lokalizacje", "lokalizacji")
                    + "  •  " + Texts.count(formats, "rozszerzenie", "rozszerzenia", "rozszerzeń")
                    + "  •  " + (method != null ? method.getTitle() : "—"));
            summary.pseudoClassStateChanged(ERROR, false);
        }
    }

    private void start() {
        commitSpinner(minSizeKb);
        commitSpinner(maxSizeMb);
        commitSpinner(threads);

        if (dateFrom.getValue() != null && dateTo.getValue() != null
                && dateFrom.getValue().isAfter(dateTo.getValue())) {
            showFeedback("Data początkowa nie może być późniejsza niż końcowa.", true);
            return;
        }
        DuplicateSearchOptions options = buildOptions();
        if (options.maxSizeBytes() > 0 && options.maxSizeBytes() < options.minSizeBytes()) {
            showFeedback("Maksymalny rozmiar pliku jest mniejszy niż minimalny.", true);
            return;
        }

        showFeedback("", false);
        onStartScan.accept(options);
    }

    /** Odczytuje bieżący stan formularza (bez walidacji). */
    private DuplicateSearchOptions buildOptions() {
        return new DuplicateSearchOptions(
                List.copyOf(locations),
                List.copyOf(exclusions),
                includeSubfolders.isSelected(),
                skipHidden.isSelected(),
                crossLocationOnly.isSelected(),
                collectExtensions(),
                selectedMethod(),
                (int) Math.round(similarity.getValue()),
                minSizeKb.getValue() * 1024L,
                maxSizeMb.getValue() * 1024L * 1024L,
                dateFrom.getValue(),
                dateTo.getValue(),
                keepStrategy.getValue(),
                action.getValue(),
                moveFolder.getPath(),
                saveReport.isSelected(),
                threads.getValue(),
                hashCache.isSelected(),
                skipLinks.isSelected());
    }

    /** Wypełnia formularz zapisanymi ustawieniami. */
    private void applyOptions(DuplicateSearchOptions o) {
        suppressChanges = true;
        try {
            locations.setAll(Objects.requireNonNullElse(o.locations(), List.of()));
            exclusions.setAll(Objects.requireNonNullElse(o.exclusions(), List.of()));
            includeSubfolders.setSelected(o.includeSubfolders());
            skipHidden.setSelected(o.skipHidden());
            crossLocationOnly.setSelected(o.crossLocationOnly());

            // Format jest zaznaczony, gdy profil zawiera wszystkie jego rozszerzenia;
            // pozostałe rozszerzenia trafiają do pola „Dodatkowe rozszerzenia”.
            Set<String> extensions = Objects.requireNonNullElse(o.extensions(), Set.of());
            Set<String> remaining = new LinkedHashSet<>(extensions);
            formatChips.forEach((format, chip) -> {
                boolean selected = extensions.containsAll(format.getExtensions());
                chip.setSelected(selected);
                if (selected) {
                    format.getExtensions().forEach(remaining::remove);
                }
            });
            customExtensions.setText(String.join(", ", remaining));

            methodRadios.get(Objects.requireNonNullElse(o.method(), SearchMethod.EXACT)).setSelected(true);
            similarity.setValue(o.similarityThreshold() == 0 ? 90 : o.similarityThreshold());

            minSizeKb.getValueFactory().setValue((int) (o.minSizeBytes() / 1024));
            maxSizeMb.getValueFactory().setValue((int) (o.maxSizeBytes() / (1024 * 1024)));
            dateFrom.setValue(o.modifiedFrom());
            dateTo.setValue(o.modifiedTo());

            keepStrategy.setValue(Objects.requireNonNullElse(o.keepStrategy(), KeepStrategy.OLDEST));
            action.setValue(Objects.requireNonNullElse(o.action(), DuplicateAction.REPORT_ONLY));
            moveFolder.setPath(o.moveTarget());
            saveReport.setSelected(o.saveReport());

            threads.getValueFactory().setValue(Math.max(1, o.threads() == 0 ? CPU_CORES : o.threads()));
            hashCache.setSelected(o.useHashCache());
            skipLinks.setSelected(o.skipSymlinks());
        } finally {
            suppressChanges = false;
        }
        showFeedback("", false);
        onFormChanged();
    }

    // =====================================================================
    // Profile
    // =====================================================================

    /**
     * Wczytuje listę profili z dysku (mogły zostać zmienione np. w sekcji „Profile”).
     * Formularz pozostaje bez zmian.
     */
    public void reloadProfiles() {
        profileBar.reloadProfiles();
    }

    /** Wybiera profil o podanej nazwie i wczytuje go do formularza. */
    public void openProfile(String name) {
        profileBar.openProfile(name);
    }

    private SearchMethod selectedMethod() {
        var toggle = methodGroup.getSelectedToggle();
        return toggle == null ? null : (SearchMethod) toggle.getUserData();
    }

    private Set<String> collectExtensions() {
        Set<String> result = new LinkedHashSet<>();
        formatChips.forEach((format, chip) -> {
            if (chip.isSelected()) {
                result.addAll(format.getExtensions());
            }
        });
        Arrays.stream(customExtensions.getText().split("[,;\\s]+"))
                .map(s -> s.trim().replaceFirst("^\\*?\\.", "").toLowerCase(Locale.ROOT))
                .filter(s -> s.matches("[a-z0-9]{1,10}"))
                .forEach(result::add);
        return result;
    }

    private void setKindSelected(MediaFormat.Kind kind, boolean selected) {
        formatChips.forEach((format, chip) -> {
            if (format.getKind() == kind) {
                chip.setSelected(selected);
            }
        });
    }

    /** Pokazuje przycisk powrotu do ostatnich wyników (null = ukryj). */
    public void setOnShowLastResults(Runnable handler) {
        lastResultsButton.setOnAction(handler == null ? null : e -> handler.run());
        lastResultsButton.setVisible(handler != null);
    }

    /** Komunikat w pasku akcji (np. „Skanowanie przerwane”). */
    public void showStatus(String text, boolean error) {
        showFeedback(text, error);
    }

    private void showFeedback(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
    }

    private Optional<Path> chooseFolder(String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        File dir = chooser.showDialog(getScene().getWindow());
        return Optional.ofNullable(dir).map(File::toPath);
    }

    /** Przycisk dodawania folderu z serwera — widoczny, gdy zdefiniowano serwery zdalne. */
    private static Button remoteButton(String text, String tooltip) {
        Button button = new Button(text, new FontIcon(MaterialDesignS.SERVER_NETWORK));
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        Runnable refresh = () -> button.setVisible(!RemoteServers.all().isEmpty());
        refresh.run();
        button.managedProperty().bind(button.visibleProperty());
        RemoteServers.addListener(() -> javafx.application.Platform.runLater(refresh));
        return button;
    }

    private static void addUnique(ObservableList<Path> list, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!list.contains(normalized)) {
            list.add(normalized);
        }
    }

    private static <T> void moveSelected(ListView<T> list, int delta) {
        int from = list.getSelectionModel().getSelectedIndex();
        int to = from + delta;
        if (from < 0 || to < 0 || to >= list.getItems().size()) {
            return;
        }
        T item = list.getItems().remove(from);
        list.getItems().add(to, item);
        list.getSelectionModel().select(to);
    }

    // =====================================================================
    // Pomocnicze kontrolki
    // =====================================================================

    private static ListView<Path> folderList(ObservableList<Path> items, String placeholder) {
        ListView<Path> list = new ListView<>(items);
        list.getStyleClass().add("folder-list");

        Label placeholderLabel = new Label(placeholder);
        placeholderLabel.getStyleClass().add("list-placeholder");
        placeholderLabel.setWrapText(true);
        list.setPlaceholder(placeholderLabel);

        list.setCellFactory(v -> new ListCell<>() {
            private final FontIcon icon = new FontIcon(MaterialDesignF.FOLDER_OUTLINE);

            {
                icon.getStyleClass().add("list-icon");
            }

            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(path.toString());
                    icon.setIconCode(Locations.isRemote(path) ? MaterialDesignS.SERVER_NETWORK : MaterialDesignF.FOLDER_OUTLINE);
                    setGraphic(icon);
                }
            }
        });

        // Przeciąganie folderów z Eksploratora.
        list.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        list.setOnDragDropped(e -> {
            boolean added = false;
            if (e.getDragboard().hasFiles()) {
                for (File file : e.getDragboard().getFiles()) {
                    if (file.isDirectory()) {
                        addUnique(items, file.toPath());
                        added = true;
                    }
                }
            }
            e.setDropCompleted(added);
            e.consume();
        });
        return list;
    }

}
