package com.photon.duplicates;

import com.photon.duplicates.scan.DuplicateFileActions;
import com.photon.duplicates.scan.DuplicateGroup;
import com.photon.duplicates.scan.DuplicateReport;
import com.photon.duplicates.scan.ProtectedPaths;
import com.photon.duplicates.scan.ScanResult;
import com.photon.duplicates.scan.ScannedFile;
import com.photon.settings.AppSettings;
import com.photon.settings.SettingsService;
import com.photon.AppPaths;
import com.photon.ui.Desktops;
import com.photon.ui.Dialogs;
import com.photon.ui.Texts;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignO;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;
import org.kordamp.ikonli.materialdesign2.MaterialDesignV;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.photon.ui.Forms.fieldLabel;
import static com.photon.ui.Forms.linkButton;
import static com.photon.ui.Forms.setupSpinner;
import static com.photon.ui.Forms.spacer;

/**
 * Wyniki wyszukiwania duplikatów: grupy plików z sortowaniem i filtrowaniem
 * po nazwie, typie, rozmiarze pliku i łącznym rozmiarze wszystkich powtórzeń.
 */
public class DuplicateResultsView extends BorderPane {

    private static final PseudoClass GROUP = PseudoClass.getPseudoClass("group");
    private static final PseudoClass ORIGINAL = PseudoClass.getPseudoClass("original");
    private static final PseudoClass KEPT = PseudoClass.getPseudoClass("kept");
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    /** Wiersz tabeli: grupa (file == null) albo plik należący do grupy. */
    public record Row(DuplicateGroup group, ScannedFile file) {
        public boolean isGroup() {
            return file == null;
        }
    }

    /** Pozycja filtra typu. */
    public record TypeFilter(String label, Predicate<DuplicateGroup> test) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final SettingsService settings;
    private ScanResult result;
    private List<DuplicateGroup> groups;

    private final TreeItem<Row> root = new TreeItem<>();
    private final TreeTableView<Row> table = new TreeTableView<>(root);
    private final Map<DuplicateGroup, TreeItem<Row>> treeItems = new IdentityHashMap<>();
    /** Pliki oznaczone ręcznie jako chronione: zostają, a pozostałe pliki ich grup idą do usunięcia/przeniesienia. */
    private final Set<Path> kept = new HashSet<>();

    // --- Zaznaczenie wielu plików ---
    private final Label selectionCount = new Label();
    private final Button keepButton = new Button("Oznacz jako chronione", new FontIcon(MaterialDesignS.SHIELD_CHECK_OUTLINE));
    private final Button unkeepButton = new Button("Zdejmij ochronę", new FontIcon(MaterialDesignS.SHIELD_OFF_OUTLINE));
    private final Button removeSelectedButton = new Button();
    private final Button clearSelectionButton = new Button("Odznacz");
    private HBox selectionBar;

    // --- Filtry ---
    private final TextField nameFilter = new TextField();
    private final ComboBox<TypeFilter> typeFilter = new ComboBox<>();
    private final Spinner<Integer> minFileSizeMb = new Spinner<>(0, 1_000_000, 0, 1);
    private final Spinner<Integer> minTotalSizeMb = new Spinner<>(0, 10_000_000, 0, 10);
    private final Label shownLabel = new Label();

    // --- Podsumowanie ---
    private final Label groupsValue = new Label();
    private final Label duplicatesValue = new Label();
    private final Label reclaimValue = new Label();
    private final Label scannedValue = new Label();
    private final Tooltip scannedTooltip = new Tooltip();
    private final Label notice = new Label();
    private final Button actionButton = new Button();
    private final Button exportButton = new Button("Eksportuj CSV", new FontIcon(MaterialDesignF.FILE_DELIMITED_OUTLINE));

    private Runnable onBack = () -> { };
    private Runnable onRescan = () -> { };

    public DuplicateResultsView(ScanResult result, SettingsService settings) {
        this.settings = settings;
        this.result = result;
        this.groups = new ArrayList<>(result.groups());
        getStyleClass().add("duplicate-results-view");

        VBox top = new VBox(14, buildHeader(), buildNotice(), buildFilterBar(), buildSelectionBar());
        BorderPane.setMargin(top, new Insets(0, 0, 14, 0));
        setTop(top);
        setCenter(buildTable());

        refreshTypeFilter();
        refreshStats();
        applyFilters();
        refreshSelectionBar();
    }

    public void setOnBack(Runnable handler) {
        this.onBack = handler;
    }

    public void setOnRescan(Runnable handler) {
        this.onRescan = handler;
    }

    /** Dodatkowy komunikat nad filtrami (np. o zapisanym raporcie). */
    public void addNotice(String text) {
        notice.setText(notice.getText().isEmpty() ? text : notice.getText() + "\n" + text);
    }

    /** Grupy widoczne po zastosowaniu filtrów, w bieżącej kolejności sortowania. */
    public List<DuplicateGroup> visibleGroups() {
        return root.getChildren().stream().map(item -> item.getValue().group()).toList();
    }

    public TreeTableView<Row> getTable() {
        return table;
    }

    // =====================================================================
    // Budowa widoku
    // =====================================================================

    private Node buildHeader() {
        Button back = new Button("Konfiguracja", new FontIcon(MaterialDesignA.ARROW_LEFT));
        back.setTooltip(new Tooltip("Wróć do ustawień wyszukiwania"));
        back.setOnAction(e -> onBack.run());

        scannedValue.setTooltip(scannedTooltip);
        HBox stats = new HBox(10,
                statTile(groupsValue, "grup duplikatów", false),
                statTile(duplicatesValue, "zbędnych kopii", false),
                statTile(reclaimValue, "do odzyskania", true),
                statTile(scannedValue, "przeanalizowano", false));
        stats.setAlignment(Pos.CENTER_LEFT);

        exportButton.setTooltip(new Tooltip("Zapisz widoczne grupy do pliku CSV"));
        exportButton.setOnAction(e -> exportCsv());

        Button rescan = new Button("Skanuj ponownie", new FontIcon(MaterialDesignR.REFRESH));
        rescan.setOnAction(e -> onRescan.run());

        configureActionButton();

        HBox actions = new HBox(8, exportButton, rescan, actionButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(14, back, stats, spacer(), actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("results-header");
        return header;
    }

    private static Node statTile(Label value, String caption, boolean accent) {
        value.getStyleClass().add("stat-value");
        if (accent) {
            value.getStyleClass().add("stat-accent");
        }
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("stat-caption");
        VBox tile = new VBox(1, value, captionLabel);
        tile.getStyleClass().add("stat-tile");
        tile.setMinWidth(VBox.USE_PREF_SIZE);
        return tile;
    }

    private Node buildNotice() {
        FontIcon icon = new FontIcon(MaterialDesignI.INFORMATION_OUTLINE);
        notice.setGraphic(icon);
        notice.getStyleClass().add("info-banner");
        notice.setWrapText(true);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.managedProperty().bind(notice.textProperty().isNotEmpty());
        notice.visibleProperty().bind(notice.managedProperty());

        List<String> lines = new ArrayList<>();
        if (result.skippedFiles() > 0) {
            lines.add(switch (result.options().method()) {
                case SIMILAR -> Texts.count(result.skippedFiles(), "plik pominięto", "pliki pominięto", "plików pominięto")
                        + " — porównanie obrazów obsługuje JPG, PNG, GIF, BMP i TIFF (bez filmów, HEIC, WEBP i RAW).";
                case METADATA -> Texts.count(result.skippedFiles(), "plik pominięto", "pliki pominięto", "plików pominięto")
                        + " — brak daty wykonania w metadanych.";
                default -> Texts.count(result.skippedFiles(), "plik pominięto", "pliki pominięto", "plików pominięto") + ".";
            });
        }
        if (result.errorCount() > 0) {
            String first = result.errors().isEmpty() ? "" : " Pierwszy: " + result.errors().get(0);
            lines.add("Nie udało się odczytać " + Texts.count(result.errorCount(), "elementu", "elementów", "elementów")
                    + "." + first);
        }
        notice.setText(String.join("\n", lines));
        return notice;
    }

    private Node buildFilterBar() {
        FontIcon searchIcon = new FontIcon(MaterialDesignM.MAGNIFY);
        searchIcon.getStyleClass().add("search-icon");
        nameFilter.setPromptText("Filtruj po nazwie lub ścieżce…");
        nameFilter.getStyleClass().add("search-field");
        nameFilter.textProperty().addListener((obs, old, text) -> applyFilters());
        nameFilter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                nameFilter.clear();
            }
        });
        StackPane searchBox = new StackPane(nameFilter, searchIcon);
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 12));
        searchBox.setPrefWidth(280);
        searchBox.setMinWidth(150);
        HBox.setHgrow(searchBox, Priority.SOMETIMES);

        typeFilter.valueProperty().addListener((obs, old, value) -> applyFilters());
        typeFilter.setPrefWidth(145);

        setupSpinner(minFileSizeMb);
        setupSpinner(minTotalSizeMb);
        minFileSizeMb.setPrefWidth(82);
        minTotalSizeMb.setPrefWidth(82);
        minFileSizeMb.valueProperty().addListener((obs, old, value) -> applyFilters());
        minTotalSizeMb.valueProperty().addListener((obs, old, value) -> applyFilters());
        minFileSizeMb.setTooltip(new Tooltip("Pokaż grupy, w których pojedynczy plik ma co najmniej tyle MB"));
        minTotalSizeMb.setTooltip(new Tooltip("Pokaż grupy, których wszystkie powtórzenia razem zajmują co najmniej tyle MB"));

        Button clear = linkButton("Wyczyść filtry");
        clear.setOnAction(e -> clearFilters());

        Button expand = iconButton(MaterialDesignU.UNFOLD_MORE_HORIZONTAL, "Rozwiń wszystkie grupy");
        expand.setOnAction(e -> root.getChildren().forEach(item -> item.setExpanded(true)));
        Button collapse = iconButton(MaterialDesignU.UNFOLD_LESS_HORIZONTAL, "Zwiń wszystkie grupy");
        collapse.setOnAction(e -> root.getChildren().forEach(item -> item.setExpanded(false)));

        shownLabel.getStyleClass().add("field-hint");
        shownLabel.setMinWidth(Label.USE_PREF_SIZE);

        clear.setMinWidth(Label.USE_PREF_SIZE);
        typeFilter.setMinWidth(Region.USE_PREF_SIZE);
        typeFilter.setTooltip(new Tooltip("Typ pliku"));
        HBox bar = new HBox(10, searchBox, typeFilter,
                fixedLabel("Plik od"), withUnit(minFileSizeMb, "MB"),
                fixedLabel("Łącznie od"), withUnit(minTotalSizeMb, "MB"),
                clear, spacer(), shownLabel, expand, collapse);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("results-filter-bar");
        return bar;
    }

    private Node buildSelectionBar() {
        selectionCount.getStyleClass().add("selection-count");
        selectionCount.setMinWidth(Label.USE_PREF_SIZE);

        keepButton.setTooltip(new Tooltip("Zaznaczone pliki zostaną zachowane, a wszystkie pozostałe pliki z ich grup "
                + "(także oryginały) zostaną zaznaczone do usunięcia lub przeniesienia"));
        keepButton.setOnAction(e -> keepSelected());
        unkeepButton.setTooltip(new Tooltip("Zaznaczone pliki przestaną być chronione"));
        unkeepButton.setOnAction(e -> unkeepSelected());
        removeSelectedButton.getStyleClass().add("button-danger");
        removeSelectedButton.setOnAction(e -> removeSelected());
        clearSelectionButton.setOnAction(e -> table.getSelectionModel().clearSelection());

        for (Button b : List.of(keepButton, unkeepButton, removeSelectedButton, clearSelectionButton)) {
            b.setMinWidth(Button.USE_PREF_SIZE);
        }
        selectionBar = new HBox(10, selectionCount, spacer(), clearSelectionButton, unkeepButton, keepButton,
                removeSelectedButton);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.getStyleClass().add("selection-bar");
        return selectionBar;
    }

    private static Label fixedLabel(String text) {
        Label label = fieldLabel(text);
        label.setMinWidth(Label.USE_PREF_SIZE);
        return label;
    }

    private static Node withUnit(Spinner<Integer> spinner, String unit) {
        Label label = new Label(unit);
        label.getStyleClass().add("field-hint");
        label.setMinWidth(Label.USE_PREF_SIZE);
        spinner.setMinWidth(Spinner.USE_PREF_SIZE);
        HBox box = new HBox(6, spinner, label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Button iconButton(org.kordamp.ikonli.Ikon icon, String tooltip) {
        Button button = new Button(null, new FontIcon(icon));
        button.getStyleClass().add("button-icon");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private Node buildTable() {
        table.getStyleClass().add("results-table");
        table.setShowRoot(false);
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TreeTableColumn<Row, Row> name = new TreeTableColumn<>("Nazwa");
        name.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getValue()));
        name.setComparator(Comparator.comparing(row -> row.group().name(), String.CASE_INSENSITIVE_ORDER));
        name.setCellFactory(c -> new NameCell());
        name.setPrefWidth(340);

        TreeTableColumn<Row, String> type = new TreeTableColumn<>("Typ");
        type.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(typeOf(c.getValue().getValue())));
        type.setPrefWidth(80);

        TreeTableColumn<Row, Integer> count = new TreeTableColumn<>("Powtórzenia");
        count.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().getValue().isGroup() ? c.getValue().getValue().group().count() : null));
        count.setCellFactory(c -> numericCell(n -> n + "×"));
        count.setPrefWidth(110);

        TreeTableColumn<Row, Long> size = new TreeTableColumn<>("Rozmiar pliku");
        size.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getValue().isGroup()
                ? c.getValue().getValue().group().fileSize()
                : c.getValue().getValue().file().size()));
        size.setCellFactory(c -> numericCell(Texts::formatBytes));
        size.setPrefWidth(120);

        TreeTableColumn<Row, Long> total = new TreeTableColumn<>("Łączny rozmiar");
        total.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().getValue().isGroup() ? c.getValue().getValue().group().totalSize() : null));
        total.setCellFactory(c -> numericCell(Texts::formatBytes));
        total.setPrefWidth(130);

        TreeTableColumn<Row, Long> reclaim = new TreeTableColumn<>("Do odzyskania");
        reclaim.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(
                c.getValue().getValue().isGroup() ? c.getValue().getValue().group().reclaimableSize() : null));
        reclaim.setCellFactory(c -> numericCell(Texts::formatBytes));
        reclaim.setPrefWidth(130);

        TreeTableColumn<Row, Instant> modified = new TreeTableColumn<>("Zmodyfikowano");
        modified.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getValue().isGroup()
                ? c.getValue().getValue().group().original().modified()
                : c.getValue().getValue().file().modified()));
        modified.setCellFactory(c -> numericCell(DATE::format));
        modified.setPrefWidth(140);

        table.getColumns().setAll(List.of(name, type, count, size, total, reclaim, modified));
        total.setSortType(TreeTableColumn.SortType.DESCENDING);
        table.getSortOrder().add(total);

        // Sortujemy tylko grupy; w grupie oryginał zostaje na górze.
        table.setSortPolicy(t -> {
            Comparator<TreeItem<Row>> comparator = t.getComparator();
            FXCollections.sort(root.getChildren(), comparator != null ? comparator
                    : Comparator.comparing((TreeItem<Row> item) -> item.getValue().group().totalSize()).reversed());
            return true;
        });

        table.setRowFactory(t -> new ResultRow());
        // Ctrl+klik dodaje/odejmuje plik z zaznaczenia, Shift+klik zaznacza zakres.
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<TreeItem<Row>>) c -> refreshSelectionBar());
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE && !selectedFiles().isEmpty()) {
                removeSelected();
            }
        });

        Label placeholder = new Label();
        placeholder.getStyleClass().add("list-placeholder");
        placeholder.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> groups.isEmpty()
                        ? "Nie znaleziono duplikatów. Wszystkie przeanalizowane pliki są unikalne."
                        : "Żadna grupa nie pasuje do filtrów.",
                shownLabel.textProperty()));
        table.setPlaceholder(placeholder);
        return table;
    }

    private static String typeOf(Row row) {
        if (!row.isGroup()) {
            return row.file().extension().toUpperCase(Locale.ROOT);
        }
        Set<String> types = new TreeSet<>();
        row.group().files().forEach(f -> types.add(f.extension().toUpperCase(Locale.ROOT)));
        return String.join(", ", types);
    }

    private static <T> TreeTableCell<Row, T> numericCell(Function<T, String> format) {
        TreeTableCell<Row, T> cell = new TreeTableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : format.apply(item));
            }
        };
        cell.getStyleClass().add("numeric-cell");
        return cell;
    }

    /** Kolumna nazwy: grupa = ikona + nazwa, plik = ścieżka (oryginał, ochrona i los pliku oznaczone). */
    private final class NameCell extends TreeTableCell<Row, Row> {

        private final Label originalPill = new Label("Oryginał");
        private final Label keptPill = new Label("Chroniony", new FontIcon(MaterialDesignS.SHIELD_CHECK_OUTLINE));
        private final Label protectedPill = new Label("Folder " + ProtectedPaths.PREFIX,
                new FontIcon(MaterialDesignL.LOCK_OUTLINE));
        private final Label candidatePill = new Label();
        private final HBox pills = new HBox(6);

        NameCell() {
            originalPill.getStyleClass().addAll("pill", "pill-success");
            keptPill.getStyleClass().addAll("pill", "pill-kept");
            keptPill.setTooltip(new Tooltip("Oznaczony ręcznie jako chroniony: zostaje, a pozostałe pliki z tej grupy "
                    + "(także oryginał) zostaną usunięte lub przeniesione."));
            protectedPill.getStyleClass().addAll("pill", "pill-protected");
            protectedPill.setTooltip(new Tooltip("Plik w katalogu zaczynającym się od „" + ProtectedPaths.PREFIX
                    + "”: nie jest usuwany automatycznie, a ręcznie tylko wtedy, gdy w tym katalogu zostaje inna kopia."));
            candidatePill.getStyleClass().addAll("pill", "pill-danger");
            pills.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            if (row.isGroup()) {
                boolean video = row.group().kind().map(k -> k == MediaFormat.Kind.VIDEO).orElse(false);
                FontIcon icon = new FontIcon(video ? MaterialDesignV.VIDEO_OUTLINE : MaterialDesignI.IMAGE_OUTLINE);
                icon.getStyleClass().add("list-icon");
                setGraphic(icon);
                setText(row.group().name());
                setTooltip(null);
            } else {
                boolean groupKept = hasKept(row.group());
                pills.getChildren().clear();
                if (kept.contains(row.file().path())) {
                    pills.getChildren().add(keptPill);
                } else if (groupKept) {
                    candidatePill.setText(candidateLabel());
                    pills.getChildren().add(candidatePill);
                } else if (row.file() == row.group().original()) {
                    pills.getChildren().add(originalPill);
                }
                if (ProtectedPaths.isProtected(row.file().path())) {
                    pills.getChildren().add(protectedPill);
                }
                setGraphic(pills.getChildren().isEmpty() ? null : pills);
                setText(row.file().path().toString());
                setTooltip(new Tooltip(row.file().path().toString()));
            }
        }
    }

    /** Wiersz: wyróżnienie grup i oryginałów, menu kontekstowe, dwuklik = pokaż w folderze. */
    private final class ResultRow extends TreeTableRow<Row> {

        ResultRow() {
            setOnMouseClicked(e -> {
                Row row = getItem();
                if (row == null || e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) {
                    return;
                }
                if (row.isGroup()) {
                    getTreeItem().setExpanded(!getTreeItem().isExpanded());
                } else {
                    showInFolder(row.file().path());
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            boolean group = !empty && row != null && row.isGroup();
            boolean file = !empty && row != null && !row.isGroup();
            boolean isKept = file && kept.contains(row.file().path());
            boolean original = file && !hasKept(row.group()) && row.file() == row.group().original();
            pseudoClassStateChanged(GROUP, group);
            pseudoClassStateChanged(ORIGINAL, original);
            pseudoClassStateChanged(KEPT, isKept);
            setContextMenu(empty || row == null ? null : contextMenu(row));
        }
    }

    private ContextMenu contextMenu(Row row) {
        Path path = row.isGroup() ? row.group().original().path() : row.file().path();
        MenuItem show = new MenuItem("Pokaż w folderze", new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
        show.setOnAction(e -> showInFolder(path));
        MenuItem open = new MenuItem("Otwórz plik", new FontIcon(MaterialDesignO.OPEN_IN_APP));
        open.setOnAction(e -> Desktops.openFile(path, this::showError));
        MenuItem copy = new MenuItem("Kopiuj ścieżkę", new FontIcon(MaterialDesignC.CONTENT_COPY));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(path.toString());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
        ContextMenu menu = new ContextMenu(show, open, new SeparatorMenuItem(), copy);
        if (row.isGroup()) {
            show.setText("Pokaż oryginał w folderze");
            open.setText("Otwórz oryginał");
        } else {
            boolean trash = settings.get().useRecycleBin();
            MenuItem remove = new MenuItem(trash ? "Przenieś plik do kosza…" : "Usuń plik trwale…",
                    new FontIcon(MaterialDesignD.DELETE_OUTLINE));
            Path blocking = ProtectedPaths.blockingDir(row.group(), row.file());
            if (blocking != null) {
                remove.setText("Nie można usunąć — ostatnia kopia w chronionym katalogu " + blocking.getFileName());
                remove.setGraphic(new FontIcon(MaterialDesignL.LOCK_OUTLINE));
                remove.setDisable(true);
            }
            remove.setOnAction(e -> removeManually(row.group(), row.file(), trash));
            menu.getItems().addAll(new SeparatorMenuItem(), remove);
        }
        return menu;
    }

    // =====================================================================
    // Filtrowanie i podsumowanie
    // =====================================================================

    private void refreshTypeFilter() {
        TypeFilter current = typeFilter.getValue();
        ObservableList<TypeFilter> items = FXCollections.observableArrayList();
        items.add(new TypeFilter("Wszystkie typy", g -> true));
        items.add(new TypeFilter("Zdjęcia", g -> g.kind().map(k -> k == MediaFormat.Kind.PHOTO).orElse(false)));
        items.add(new TypeFilter("Filmy", g -> g.kind().map(k -> k == MediaFormat.Kind.VIDEO).orElse(false)));
        Set<String> extensions = new TreeSet<>();
        groups.forEach(g -> g.files().forEach(f -> extensions.add(f.extension())));
        for (String ext : extensions) {
            items.add(new TypeFilter("." + ext.toUpperCase(Locale.ROOT),
                    g -> g.files().stream().anyMatch(f -> f.extension().equals(ext))));
        }
        typeFilter.setItems(items);
        typeFilter.setValue(current == null ? items.get(0)
                : items.stream().filter(i -> i.label().equals(current.label())).findFirst().orElse(items.get(0)));
    }

    private void clearFilters() {
        nameFilter.clear();
        typeFilter.setValue(typeFilter.getItems().get(0));
        minFileSizeMb.getValueFactory().setValue(0);
        minTotalSizeMb.getValueFactory().setValue(0);
    }

    private void applyFilters() {
        String query = nameFilter.getText() == null ? "" : nameFilter.getText().trim().toLowerCase(Locale.ROOT);
        TypeFilter type = typeFilter.getValue();
        long minFile = mb(minFileSizeMb.getValue());
        long minTotal = mb(minTotalSizeMb.getValue());

        List<TreeItem<Row>> visible = new ArrayList<>();
        for (DuplicateGroup group : groups) {
            if (type != null && !type.test().test(group)) {
                continue;
            }
            if (group.fileSize() < minFile || group.totalSize() < minTotal) {
                continue;
            }
            if (!query.isEmpty() && group.files().stream()
                    .noneMatch(f -> f.path().toString().toLowerCase(Locale.ROOT).contains(query))) {
                continue;
            }
            visible.add(treeItem(group));
        }
        root.getChildren().setAll(visible);
        table.sort();

        long visibleTotal = visible.stream().mapToLong(i -> i.getValue().group().reclaimableSize()).sum();
        shownLabel.setText(visible.size() == groups.size()
                ? Texts.count(groups.size(), "grupa", "grupy", "grup")
                : visible.size() + " z " + Texts.count(groups.size(), "grupy", "grup", "grup")
                        + "  •  " + Texts.formatBytes(visibleTotal) + " do odzyskania");
        refreshActionButton();
    }

    private TreeItem<Row> treeItem(DuplicateGroup group) {
        return treeItems.computeIfAbsent(group, g -> {
            TreeItem<Row> item = new TreeItem<>(new Row(g, null));
            for (ScannedFile file : g.files()) {
                item.getChildren().add(new TreeItem<>(new Row(g, file)));
            }
            return item;
        });
    }

    private void refreshStats() {
        int duplicates = groups.stream().mapToInt(g -> g.count() - 1).sum();
        long reclaim = groups.stream().mapToLong(DuplicateGroup::reclaimableSize).sum();
        groupsValue.setText(Integer.toString(groups.size()));
        duplicatesValue.setText(Integer.toString(duplicates));
        reclaimValue.setText(Texts.formatBytes(reclaim));
        long seconds = Math.max(0, result.elapsed().toSeconds());
        scannedValue.setText(Texts.count(result.scannedFiles(), "plik", "pliki", "plików"));
        scannedTooltip.setText(Texts.formatBytes(result.scannedBytes()) + " w " + seconds + " s");
        exportButton.setDisable(groups.isEmpty());
    }

    private static long mb(Integer value) {
        return value == null ? 0 : value * 1024L * 1024L;
    }

    // =====================================================================
    // Działania na plikach
    // =====================================================================

    private void configureActionButton() {
        actionButton.getStyleClass().add("button-primary");
        actionButton.setOnAction(e -> runAction());
        DuplicateAction action = result.options().action();
        boolean available = action != null && action != DuplicateAction.REPORT_ONLY;
        actionButton.setVisible(available);
        actionButton.setManaged(available);
    }

    private DuplicateFileActions.Mode actionMode() {
        if (result.options().action() == DuplicateAction.MOVE_TO_FOLDER) {
            return DuplicateFileActions.Mode.MOVE;
        }
        return settings.get().useRecycleBin() ? DuplicateFileActions.Mode.TRASH : DuplicateFileActions.Mode.DELETE;
    }

    private void refreshActionButton() {
        if (!actionButton.isManaged()) {
            return;
        }
        int copies = removableCopies(visibleGroups(), kept);
        String verb = switch (actionMode()) {
            case MOVE -> "Przenieś kopie do folderu";
            case TRASH -> "Przenieś kopie do kosza";
            case DELETE -> "Usuń kopie trwale";
        };
        actionButton.setText(verb + " (" + copies + ")");
        actionButton.setGraphic(new FontIcon(actionMode() == DuplicateFileActions.Mode.MOVE
                ? MaterialDesignF.FOLDER_MOVE_OUTLINE : MaterialDesignD.DELETE_OUTLINE));
        actionButton.setDisable(copies == 0);
    }

    private void runAction() {
        List<DuplicateGroup> target = visibleGroups();
        Set<Path> keptNow = Set.copyOf(kept);
        int copies = removableCopies(target, keptNow);
        int protectedCopies = target.stream()
                .mapToInt(g -> DuplicateFileActions.candidates(g, keptNow).size()).sum() - copies;
        long bytes = target.stream().flatMap(g -> DuplicateFileActions.candidates(g, keptNow).stream())
                .filter(f -> !ProtectedPaths.isProtected(f.path()))
                .mapToLong(ScannedFile::size).sum();
        long keptGroups = target.stream().filter(this::hasKept).count();
        DuplicateFileActions.Mode mode = actionMode();
        Path moveTarget = result.options().moveTarget();
        AppSettings s = settings.get();

        String what = Texts.count(copies, "kopię", "kopie", "kopii") + " (" + Texts.formatBytes(bytes) + ") z "
                + Texts.count(target.size(), "widocznej grupy", "widocznych grup", "widocznych grup");
        String message = switch (mode) {
            case MOVE -> "Przenieść " + what + " do folderu:\n" + moveTarget
                    + "\n\nOryginały zostaną na miejscu. Struktura folderów zostanie zachowana.";
            case TRASH -> "Przenieść " + what + " do kosza?\n\nOryginały zostaną na miejscu. "
                    + "Pliki można przywrócić z kosza.";
            case DELETE -> "Trwale usunąć " + what + "?\n\nOryginały zostaną na miejscu. "
                    + "Tej operacji NIE można cofnąć (w Ustawieniach wyłączono używanie kosza).";
        };
        if (keptGroups > 0) {
            message += "\n\nW " + Texts.count((int) keptGroups, "grupie", "grupach", "grupach")
                    + " z plikami oznaczonymi jako chronione zostają tylko one — pozostałe pliki, także oryginały, "
                    + "zostaną objęte operacją.";
        }
        if (protectedCopies > 0) {
            message += "\n\nPominięte zostanie " + Texts.count(protectedCopies, "kopia", "kopie", "kopii")
                    + " z chronionych katalogów („" + ProtectedPaths.PREFIX + "…”) — można je usunąć tylko ręcznie.";
        }
        if (mode == DuplicateFileActions.Mode.MOVE && moveTarget == null) {
            Dialogs.error(window(), "Brak folderu docelowego", "W konfiguracji nie wybrano folderu na duplikaty.");
            return;
        }
        boolean ask = s.confirmFileOperations() || mode == DuplicateFileActions.Mode.DELETE;
        if (ask && !Dialogs.confirm(window(), actionButton.getText(), message,
                mode == DuplicateFileActions.Mode.MOVE ? "Przenieś" : (mode == DuplicateFileActions.Mode.TRASH
                        ? "Przenieś do kosza" : "Usuń trwale"),
                mode != DuplicateFileActions.Mode.MOVE)) {
            return;
        }

        Path log = s.keepOperationLog() ? AppPaths.logsDir().resolve("operacje-na-plikach.log") : null;
        List<Path> locations = result.options().locations();
        Task<DuplicateFileActions.Outcome> task = new Task<>() {
            @Override
            protected DuplicateFileActions.Outcome call() {
                return DuplicateFileActions.apply(target, keptNow, mode, moveTarget, locations, log, this::isCancelled);
            }
        };
        setDisable(true);
        task.setOnSucceeded(e -> {
            setDisable(false);
            applyOutcome(task.getValue(), switch (mode) {
                case MOVE -> "Przeniesiono ";
                case TRASH -> "Przeniesiono do kosza ";
                case DELETE -> "Usunięto ";
            });
        });
        task.setOnFailed(e -> {
            setDisable(false);
            showError(task.getException().getMessage());
        });
        Thread thread = new Thread(task, "photon-duplicate-action");
        thread.setDaemon(true);
        thread.start();
    }

    /** Liczba kopii, które obejmie działanie automatyczne (bez plików z chronionych katalogów). */
    private static int removableCopies(List<DuplicateGroup> target, Set<Path> kept) {
        return (int) target.stream().flatMap(g -> DuplicateFileActions.candidates(g, kept).stream())
                .filter(f -> !ProtectedPaths.isProtected(f.path()))
                .count();
    }

    /** Ręczne usunięcie jednego pliku z menu kontekstowego — zawsze z potwierdzeniem. */
    private void removeManually(DuplicateGroup group, ScannedFile file, boolean trash) {
        Path blocking = ProtectedPaths.blockingDir(group, file);
        if (blocking != null) {
            Dialogs.error(window(), "Plik chroniony", "Nie można usunąć pliku:\n" + file.path()
                    + "\n\nTo ostatnia kopia w chronionym katalogu:\n" + blocking);
            return;
        }
        String message = (trash ? "Przenieść do kosza plik:\n" : "Trwale usunąć plik:\n") + file.path()
                + (file == group.original() ? "\n\nTo plik oznaczony jako oryginał — oryginałem zostanie inna kopia." : "")
                + (trash ? "" : "\n\nTej operacji NIE można cofnąć.");
        if (!Dialogs.confirm(window(), trash ? "Przenieś plik do kosza" : "Usuń plik trwale", message,
                trash ? "Przenieś do kosza" : "Usuń trwale", true)) {
            return;
        }
        Path log = settings.get().keepOperationLog() ? AppPaths.logsDir().resolve("operacje-na-plikach.log") : null;
        applyOutcome(DuplicateFileActions.removeManually(group, file, trash, log),
                trash ? "Przeniesiono do kosza " : "Usunięto ");
    }

    private void applyOutcome(DuplicateFileActions.Outcome outcome, String verb) {
        if (!outcome.processed().isEmpty()) {
            List<DuplicateGroup> updated = new ArrayList<>();
            for (DuplicateGroup group : groups) {
                Optional<DuplicateGroup> remaining = group.without(outcome.processed());
                if (remaining.isPresent()) {
                    // Grupa bez zmian zachowuje swój element drzewa (i stan rozwinięcia).
                    updated.add(remaining.get().count() == group.count() ? group : remaining.get());
                }
            }
            groups = updated;
            result = result.withGroups(updated);
            Set<DuplicateGroup> keep = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            keep.addAll(updated);
            treeItems.keySet().retainAll(keep);
            Set<Path> existing = new HashSet<>();
            updated.forEach(g -> g.files().forEach(f -> existing.add(f.path())));
            kept.retainAll(existing);
            refreshTypeFilter();
            refreshStats();
            applyFilters();
        }
        String done = verb + Texts.count(outcome.processed().size(), "plik", "pliki", "plików") + ".";
        if (outcome.skippedProtected() > 0) {
            done += " Pominięto " + Texts.count(outcome.skippedProtected(), "plik", "pliki", "plików")
                    + " z chronionych katalogów (" + ProtectedPaths.PREFIX + "…).";
        }
        notice.setText(done);
        table.refresh();
        refreshSelectionBar();
        if (!outcome.errors().isEmpty()) {
            Dialogs.error(window(), "Część plików nie została przetworzona",
                    Texts.count(outcome.errors().size(), "błąd", "błędy", "błędów") + ":\n"
                            + String.join("\n", outcome.errors().subList(0, Math.min(10, outcome.errors().size()))));
        }
    }

    // =====================================================================
    // Zaznaczanie wielu plików i ręczna ochrona
    // =====================================================================

    private boolean hasKept(DuplicateGroup group) {
        return !kept.isEmpty() && group.files().stream().anyMatch(f -> kept.contains(f.path()));
    }

    /** Pigułka plików, które obejmie operacja w grupie z ręcznie chronionymi plikami. */
    private String candidateLabel() {
        DuplicateAction action = result.options().action();
        if (action == null || action == DuplicateAction.REPORT_ONLY) {
            return "Do usunięcia";
        }
        return switch (actionMode()) {
            case MOVE -> "Do przeniesienia";
            case TRASH -> "Do kosza";
            case DELETE -> "Do usunięcia";
        };
    }

    /** Zaznaczone pliki (bez wierszy grup), pogrupowane, w kolejności grup. */
    public Map<DuplicateGroup, List<ScannedFile>> selectedFiles() {
        Map<DuplicateGroup, List<ScannedFile>> selected = new LinkedHashMap<>();
        for (TreeItem<Row> item : table.getSelectionModel().getSelectedItems()) {
            if (item == null || item.getValue() == null || item.getValue().isGroup()) {
                continue;
            }
            Row row = item.getValue();
            List<ScannedFile> files = selected.computeIfAbsent(row.group(), g -> new ArrayList<>());
            if (!files.contains(row.file())) {
                files.add(row.file());
            }
        }
        return selected;
    }

    public Set<Path> keptFiles() {
        return java.util.Collections.unmodifiableSet(kept);
    }

    private void refreshSelectionBar() {
        Map<DuplicateGroup, List<ScannedFile>> selected = selectedFiles();
        int count = selected.values().stream().mapToInt(List::size).sum();
        long bytes = selected.values().stream().flatMap(List::stream).mapToLong(ScannedFile::size).sum();
        boolean any = count > 0;
        selectionCount.setText(any
                ? "Zaznaczono " + Texts.count(count, "plik", "pliki", "plików") + " (" + Texts.formatBytes(bytes) + ")"
                + (selected.size() > 1 ? " w " + Texts.count(selected.size(), "grupie", "grupach", "grupach") : "")
                : "Zaznacz pliki: Ctrl+klik dodaje lub odejmuje plik, Shift+klik zaznacza zakres.");
        selectionCount.pseudoClassStateChanged(ACTIVE, any);
        if (selectionBar != null) {
            selectionBar.pseudoClassStateChanged(ACTIVE, any);
        }
        boolean anyKept = selected.values().stream().flatMap(List::stream).anyMatch(f -> kept.contains(f.path()));
        boolean anyNotKept = selected.values().stream().flatMap(List::stream).anyMatch(f -> !kept.contains(f.path()));
        keepButton.setDisable(!anyNotKept);
        unkeepButton.setDisable(!anyKept);
        unkeepButton.setVisible(!kept.isEmpty());
        unkeepButton.setManaged(!kept.isEmpty());
        clearSelectionButton.setDisable(table.getSelectionModel().isEmpty());

        boolean trash = settings.get().useRecycleBin();
        removeSelectedButton.setText((trash ? "Przenieś zaznaczone do kosza" : "Usuń zaznaczone")
                + (any ? " (" + count + ")" : ""));
        removeSelectedButton.setGraphic(new FontIcon(MaterialDesignD.DELETE_OUTLINE));
        removeSelectedButton.setDisable(!anyNotKept);
    }

    /**
     * Oznacza zaznaczone pliki jako chronione. Pozostałe pliki z ich grup (także oryginały) zostają
     * zaznaczone — gotowe do usunięcia przyciskiem lub objęte działaniem automatycznym.
     */
    private void keepSelected() {
        Map<DuplicateGroup, List<ScannedFile>> selected = selectedFiles();
        if (selected.isEmpty()) {
            return;
        }
        int newlyKept = 0;
        for (List<ScannedFile> files : selected.values()) {
            for (ScannedFile f : files) {
                if (kept.add(f.path())) {
                    newlyKept++;
                }
            }
        }
        // Zaznaczenie przechodzi na pliki, które teraz pójdą do usunięcia/przeniesienia.
        table.getSelectionModel().clearSelection();
        int toRemove = 0;
        for (DuplicateGroup group : selected.keySet()) {
            TreeItem<Row> groupItem = treeItem(group);
            groupItem.setExpanded(true);
            for (TreeItem<Row> child : groupItem.getChildren()) {
                if (!kept.contains(child.getValue().file().path())) {
                    toRemove++;
                    int index = table.getRow(child);
                    if (index >= 0) {
                        table.getSelectionModel().select(index);
                    }
                }
            }
        }
        table.refresh();
        refreshActionButton();
        refreshSelectionBar();
        notice.setText("Oznaczono jako chronione: " + Texts.count(newlyKept, "plik", "pliki", "plików") + ". "
                + (toRemove == 0 ? "W ich grupach nie zostały inne pliki."
                : "Pozostałe pliki z ich grup (" + toRemove + ", także oryginały) zaznaczono do "
                + (candidateLabel().equals("Do przeniesienia") ? "przeniesienia" : "usunięcia") + "."));
    }

    private void unkeepSelected() {
        int removed = 0;
        for (List<ScannedFile> files : selectedFiles().values()) {
            for (ScannedFile f : files) {
                if (kept.remove(f.path())) {
                    removed++;
                }
            }
        }
        table.refresh();
        refreshActionButton();
        refreshSelectionBar();
        notice.setText("Zdjęto ochronę z " + Texts.count(removed, "pliku", "plików", "plików") + ".");
    }

    /** Usuwa zaznaczone pliki (kosz lub trwale) — zawsze z potwierdzeniem i listą plików. */
    private void removeSelected() {
        Map<DuplicateGroup, List<ScannedFile>> selected = selectedFiles();
        if (selected.isEmpty()) {
            return;
        }
        Set<Path> keptNow = Set.copyOf(kept);
        DuplicateFileActions.RemovalPlan plan = DuplicateFileActions.planRemoval(selected, keptNow);
        if (plan.count() == 0) {
            Dialogs.error(window(), "Nie można usunąć zaznaczonych plików",
                    String.join("\n", plan.refused().subList(0, Math.min(10, plan.refused().size()))));
            return;
        }
        boolean trash = settings.get().useRecycleBin();
        List<String> details = new ArrayList<>();
        plan.toRemove().values().forEach(files -> files.forEach(f -> details.add(f.path().toString())));
        String message = (trash ? "Przenieść do kosza " : "Trwale usunąć ")
                + Texts.count(plan.count(), "plik", "pliki", "plików") + " (" + Texts.formatBytes(plan.bytes()) + ")?"
                + " W każdej grupie zostaje co najmniej jeden plik."
                + (trash ? "" : "\n\nTej operacji NIE można cofnąć.");
        if (!plan.refused().isEmpty()) {
            message += "\n\nPominięte zostanie " + Texts.count(plan.refused().size(), "zaznaczenie", "zaznaczenia",
                    "zaznaczeń") + ":\n" + String.join("\n", plan.refused().subList(0, Math.min(5, plan.refused().size())))
                    + (plan.refused().size() > 5 ? "\n…" : "");
        }
        if (!Dialogs.confirmWithDetails(window(), trash ? "Przenieś zaznaczone do kosza" : "Usuń zaznaczone pliki",
                message, details, trash ? "Przenieś do kosza" : "Usuń trwale", true)) {
            return;
        }
        Path log = settings.get().keepOperationLog() ? AppPaths.logsDir().resolve("operacje-na-plikach.log") : null;
        Task<DuplicateFileActions.Outcome> task = new Task<>() {
            @Override
            protected DuplicateFileActions.Outcome call() {
                return DuplicateFileActions.removeSelected(plan, keptNow, trash, log);
            }
        };
        setDisable(true);
        task.setOnSucceeded(e -> {
            setDisable(false);
            table.getSelectionModel().clearSelection();
            applyOutcome(task.getValue(), trash ? "Przeniesiono do kosza " : "Usunięto ");
        });
        task.setOnFailed(e -> {
            setDisable(false);
            showError(task.getException().getMessage());
        });
        Thread thread = new Thread(task, "photon-duplicate-remove-selected");
        thread.setDaemon(true);
        thread.start();
    }

    private void exportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Zapisz raport duplikatów");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plik CSV", "*.csv"));
        File dir = settings.get().effectiveReportsDir().toFile();
        if (dir.isDirectory()) {
            chooser.setInitialDirectory(dir);
        }
        chooser.setInitialFileName(DuplicateReport.defaultFileName(result));
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        try {
            DuplicateReport.write(visibleGroups(), file.toPath());
            notice.setText("Zapisano raport: " + file);
        } catch (IOException ex) {
            showError("Nie udało się zapisać raportu: " + ex.getMessage());
        }
    }

    private void showInFolder(Path path) {
        Desktops.showInFolder(path, this::showError);
    }

    private void showError(String message) {
        Dialogs.error(window(), "Błąd", message);
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
