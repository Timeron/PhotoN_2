package com.photon.phonearchive;

import com.photon.profiles.ProfileBar;
import com.photon.thumbnails.ThumbnailMaker;
import com.photon.thumbnails.ThumbnailOptions;
import com.photon.thumbnails.XmpHash;
import com.photon.ui.FormSection;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import com.photon.ui.FolderField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.photon.ui.Forms.commitSpinner;
import static com.photon.ui.Forms.fieldLabel;
import static com.photon.ui.Forms.infoBanner;
import static com.photon.ui.Forms.formGrid;
import static com.photon.ui.Forms.setupSpinner;
import static com.photon.ui.Forms.withSuffix;

/**
 * Formularz „Archiwizacja telefonu”: folder archiwizowany i folder archiwum z podfolderami RRRR-MM-DD.
 */
public class PhoneArchiveView extends BorderPane {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final TextField sourceField = new TextField();
    private final TextField targetField = new TextField();
    private FolderField sourceFolder;
    private FolderField targetFolder;
    private FolderField duplicatesFolder;
    private FolderField thumbnailFolder;
    private final CheckBox includeSubfolders = new CheckBox("Uwzględnij podfoldery folderu archiwizowanego");
    private final Label dateFoldersWarning = new Label();
    private final Label dateFoldersInfo = new Label();

    private final ComboBox<PhoneDuplicateAction> duplicateAction =
            new ComboBox<>(FXCollections.observableArrayList(PhoneDuplicateAction.values()));
    private final TextField duplicatesField = new TextField();

    private final CheckBox makeThumbnails = new CheckBox("Utwórz miniatury zarchiwizowanych zdjęć");
    private final Spinner<Integer> thumbnailSide =
            new Spinner<>(ThumbnailOptions.MIN_SIDE, ThumbnailOptions.MAX_SIDE, ThumbnailOptions.DEFAULT_SIDE, 10);
    private final TextField thumbnailField = new TextField();

    private final Label summary = new Label();
    private final Label feedback = new Label();
    private final Button openTargetButton = new Button("Otwórz folder z dzisiejszą datą",
            new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
    private final Button openDuplicatesButton = new Button("Otwórz folder duplikatów",
            new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
    private final Button startButton = new Button("Archiwizuj", new FontIcon(MaterialDesignA.ARCHIVE_ARROW_DOWN_OUTLINE));

    private final ProfileBar<PhoneArchiveOptions> profileBar;
    private final Supplier<LocalDate> today;
    private boolean suppressChanges;

    private Consumer<PhoneArchiveOptions> onStart = options -> { };

    public PhoneArchiveView(PhoneArchiveProfileStore profileStore, PhoneArchiveOptions initial,
                            Supplier<LocalDate> today) {
        this.today = today;
        getStyleClass().add("phone-archive-view");
        profileBar = new ProfileBar<>(profileStore, this::options, this::setOptions,
                this::showStatus, "archiwizacji");
        BorderPane.setMargin(profileBar, new Insets(0, 24, 14, 0));

        VBox form = new VBox(18, buildFoldersSection(), buildDuplicatesSection(), buildThumbnailsSection(), buildRulesSection());
        form.getStyleClass().add("form");
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");

        setTop(profileBar);
        setCenter(scroll);
        setBottom(buildActionBar());

        sourceFolder.pathProperty().addListener((obs, old, value) -> onFormChanged());
        targetFolder.pathProperty().addListener((obs, old, value) -> onFormChanged());
        includeSubfolders.selectedProperty().addListener((obs, old, value) -> onFormChanged());
        makeThumbnails.selectedProperty().addListener((obs, old, value) -> onFormChanged());
        duplicateAction.valueProperty().addListener((obs, old, value) -> onFormChanged());
        duplicatesFolder.pathProperty().addListener((obs, old, value) -> onFormChanged());
        thumbnailSide.valueProperty().addListener((obs, old, value) -> onFormChanged());
        thumbnailFolder.pathProperty().addListener((obs, old, value) -> onFormChanged());

        setOptions(initial);
        profileBar.reloadProfiles();
    }

    public void reloadProfiles() {
        profileBar.reloadProfiles();
    }

    public void openProfile(String name) {
        profileBar.openProfile(name);
    }

    public ProfileBar<PhoneArchiveOptions> profileBar() {
        return profileBar;
    }

    public void setOnStart(Consumer<PhoneArchiveOptions> handler) {
        this.onStart = handler;
    }

    public void setOptions(PhoneArchiveOptions options) {
        suppressChanges = true;
        try {
            sourceFolder.setPath(options.source());
            targetFolder.setPath(options.target());
            includeSubfolders.setSelected(options.includeSubfolders());
            makeThumbnails.setSelected(options.makeThumbnails());
            thumbnailSide.getValueFactory().setValue(options.thumbnailSide());
            thumbnailFolder.setPath(options.thumbnailTarget());
            duplicateAction.setValue(options.duplicateAction());
            duplicatesFolder.setPath(options.duplicatesTarget());
        } finally {
            suppressChanges = false;
        }
        onFormChanged();
    }

    public PhoneArchiveOptions options() {
        commitSpinner(thumbnailSide);
        return new PhoneArchiveOptions(sourceFolder.getPath(), targetFolder.getPath(), includeSubfolders.isSelected(),
                makeThumbnails.isSelected(), thumbnailSide.getValue(), thumbnailFolder.getPath(),
                duplicateAction.getValue(), duplicatesFolder.getPath());
    }

    public void showStatus(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
    }

    /** Pokazuje przycisk otwierający folder z dzisiejszą datą (null = ukryj). */
    public void setOnOpenTarget(Runnable handler) {
        openTargetButton.setOnAction(handler == null ? null : e -> handler.run());
        openTargetButton.setVisible(handler != null);
    }

    /** Pokazuje przycisk otwierający folder z przeniesionymi duplikatami (null = ukryj). */
    public void setOnOpenDuplicates(Runnable handler) {
        openDuplicatesButton.setOnAction(handler == null ? null : e -> handler.run());
        openDuplicatesButton.setVisible(handler != null);
    }

    /** Ponownie sprawdza foldery RRRR-MM-DD w archiwum (np. po zakończonej archiwizacji). */
    public void refreshDateFolders() {
        Path target = targetFolder.getPath();
        List<Path> folders = PhoneArchiver.dateFolders(target);
        String day = today.get().format(PhoneArchiver.DAY_FORMAT);
        boolean show = target != null && folders.isEmpty();
        dateFoldersWarning.setText(show
                ? "W folderze archiwum nie ma żadnego folderu o nazwie w formacie RRRR-MM-DD (np. 2016-02-23). "
                + "Nie będzie z czym porównać plików — wszystkie zdjęcia i filmy trafią do nowego folderu " + day + "."
                : "");
        dateFoldersInfo.setText(target == null || folders.isEmpty() ? ""
                : "Folderów z datą w archiwum: " + folders.size() + " (od " + folders.get(0).getFileName()
                + " do " + folders.get(folders.size() - 1).getFileName() + ").");
    }

    public boolean hasDateFolders() {
        return !PhoneArchiver.dateFolders(targetFolder.getPath()).isEmpty();
    }

    private void onFormChanged() {
        if (suppressChanges) {
            return;
        }
        refreshDateFolders();
        refreshSummary();
        profileBar.refreshState();
    }

    private void applyDefaults() {
        // Foldery zostają, bo ich wskazanie jest najbardziej pracochłonne.
        PhoneArchiveOptions current = options();
        PhoneArchiveOptions defaults = PhoneArchiveOptions.defaults();
        setOptions(new PhoneArchiveOptions(current.source(), current.target(), defaults.includeSubfolders(),
                defaults.makeThumbnails(), defaults.thumbnailSide(), current.thumbnailTarget(),
                defaults.duplicateAction(), current.duplicatesTarget()));
        showStatus("", false);
    }

    // =====================================================================
    // Budowa formularza
    // =====================================================================

    private Node buildFoldersSection() {
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Folder archiwizowany"),
                sourceFolder = new FolderField(sourceField, "Wskaż folder ze zdjęciami i filmami do archiwizacji",
                        "Folder archiwizowany"));
        grid.addRow(1, fieldLabel("Folder archiwum"),
                targetFolder = new FolderField(targetField, "Wskaż folder archiwum z podfolderami RRRR-MM-DD",
                        "Folder archiwum"));
        includeSubfolders.setWrapText(true);

        dateFoldersWarning.setGraphic(new FontIcon(MaterialDesignA.ALERT_OUTLINE));
        dateFoldersWarning.getStyleClass().add("warning-banner");
        dateFoldersWarning.setWrapText(true);
        dateFoldersWarning.setMaxWidth(Double.MAX_VALUE);
        dateFoldersWarning.managedProperty().bind(dateFoldersWarning.textProperty().isNotEmpty());
        dateFoldersWarning.visibleProperty().bind(dateFoldersWarning.managedProperty());

        dateFoldersInfo.getStyleClass().add("field-hint");
        dateFoldersInfo.managedProperty().bind(dateFoldersInfo.textProperty().isNotEmpty());
        dateFoldersInfo.visibleProperty().bind(dateFoldersInfo.managedProperty());

        return new FormSection(MaterialDesignC.CELLPHONE_ARROW_DOWN,
                "Foldery",
                "Skąd brać zdjęcia i filmy oraz gdzie leży archiwum.",
                grid, includeSubfolders, dateFoldersInfo, dateFoldersWarning);
    }

    private Node buildDuplicatesSection() {
        duplicateAction.setMaxWidth(Double.MAX_VALUE);
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Co zrobić z duplikatami"), duplicateAction);
        Label folderLabel = fieldLabel("Folder zastępczy (śmietnik)");
        HBox folderRow = duplicatesFolder = new FolderField(duplicatesField, "Wskaż folder, do którego trafią duplikaty",
                "Folder zastępczy (śmietnik) na duplikaty");
        grid.addRow(1, folderLabel, folderRow);
        var moving = duplicateAction.valueProperty().isEqualTo(PhoneDuplicateAction.MOVE_TO_FOLDER);
        for (Node n : List.of(folderLabel, folderRow)) {
            n.visibleProperty().bind(moving);
            n.managedProperty().bind(moving);
        }

        Label info = infoBanner("Duplikat to plik z folderu archiwizowanego, który już jest w archiwum (identyczna "
                + "zawartość). Po archiwizacji zobaczysz ich listę i potwierdzisz operację. Przeniesione duplikaty "
                + "zachowują strukturę podfolderów folderu archiwizowanego; kopie w archiwum zostają.");
        return new FormSection(MaterialDesignC.CONTENT_DUPLICATE,
                "Duplikaty",
                "Pliki, które już są w archiwum, nie są do niego ponownie dodawane.",
                grid, info);
    }

    private Node buildThumbnailsSection() {
        setupSpinner(thumbnailSide);
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Dłuższy bok miniatury"), withSuffix(thumbnailSide, "px",
                "Szerokość zdjęć poziomych i wysokość pionowych. Mniejsze zdjęcia nie są powiększane."));
        grid.addRow(1, fieldLabel("Folder na miniatury"),
                thumbnailFolder = new FolderField(thumbnailField, "Wskaż folder, w którym zostaną zapisane miniatury",
                        "Folder na miniatury"));

        Label layout = infoBanner("Miniatury trafiają do podfolderu z dzisiejszą datą (jak w archiwum) i mają "
                + "te same nazwy plików co zarchiwizowane zdjęcia. Tworzone są tylko dla zdjęć przeniesionych "
                + "w tej archiwizacji — nie dla duplikatów, które już były w archiwum.");
        Label quality = infoBanner("Jak w zakładce „Miniaturyzacja”: skalowanie filtrem Lanczos-3, JPEG w jakości "
                + ThumbnailMaker.JPEG_QUALITY_PERCENT + "% (4:4:4). Zachowane metadane oryginału: EXIF, IPTC, XMP, "
                + "profil ICC i data modyfikacji; do XMP dopisywana jest suma SHA-256 oryginału ("
                + XmpHash.PREFIX + ":" + XmpHash.PROPERTY + "). Miniatury powstają tylko ze zdjęć JPEG.");

        VBox options = new VBox(12, grid, layout, quality);
        options.getStyleClass().add("thumbnail-options");
        options.managedProperty().bind(makeThumbnails.selectedProperty());
        options.visibleProperty().bind(makeThumbnails.selectedProperty());
        makeThumbnails.setWrapText(true);

        return new FormSection(MaterialDesignI.IMAGE_MULTIPLE_OUTLINE,
                "Miniatury",
                "Opcjonalnie: pomniejszone kopie zarchiwizowanych zdjęć.",
                makeThumbnails, options);
    }

    private Node buildRulesSection() {
        Label media = infoBanner("Archiwizowane są tylko zdjęcia i filmy (JPG, PNG, HEIC, WEBP, RAW, MP4, MOV, "
                + "MKV, 3GP…). Pozostałe pliki w folderze archiwizowanym są pomijane.");
        Label duplicates = infoBanner("Przed przeniesieniem każdy plik jest porównywany (suma SHA-256) z plikami "
                + "w folderach archiwum o nazwach RRRR-MM-DD. Pliki, których tam nie ma, trafiają do nowego folderu "
                + "z dzisiejszą datą. Pliki, które już są w archiwum, zostają na miejscu — na końcu zapytamy, "
                + "czy je usunąć albo przenieść (sekcja „Duplikaty”).");
        Label protectedFolders = infoBanner("Pliki z folderów, których nazwa zaczyna się od „__”, są kopiowane "
                + "zamiast przenoszone, a ich duplikaty nie są usuwane ani przenoszone.");
        return new FormSection(MaterialDesignI.INFORMATION_OUTLINE,
                "Zasady",
                "Jak działa archiwizacja.",
                media, duplicates, protectedFolders);
    }



    private Node buildActionBar() {
        summary.getStyleClass().add("action-summary");
        feedback.getStyleClass().add("action-feedback");
        feedback.setWrapText(true);
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty());
        feedback.visibleProperty().bind(feedback.managedProperty());
        VBox texts = new VBox(2, summary, feedback);
        HBox.setHgrow(texts, Priority.ALWAYS);

        openTargetButton.setVisible(false);
        openTargetButton.managedProperty().bind(openTargetButton.visibleProperty());
        openDuplicatesButton.setVisible(false);
        openDuplicatesButton.managedProperty().bind(openDuplicatesButton.visibleProperty());

        startButton.getStyleClass().add("button-primary");
        startButton.setDefaultButton(true);
        startButton.setOnAction(e -> start());

        Button reset = new Button("Przywróć domyślne", new FontIcon(MaterialDesignR.RESTORE));
        reset.setOnAction(e -> applyDefaults());

        HBox bar = new HBox(12, texts, openDuplicatesButton, openTargetButton, reset, startButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("action-bar");
        BorderPane.setMargin(bar, new Insets(14, 24, 0, 0));
        return bar;
    }

    private void refreshSummary() {
        Integer side = thumbnailSide.getValue();
        summary.setText("Nowe pliki trafią do folderu " + today.get().format(PhoneArchiver.DAY_FORMAT)
                + (makeThumbnails.isSelected() ? "  •  miniatury " + (side == null ? "?" : side) + " px" : ""));
    }

    private void start() {
        PhoneArchiveOptions options = options();
        if (!sourceField.getText().isBlank() && options.source() == null
                || !targetField.getText().isBlank() && options.target() == null
                || options.makeThumbnails() && !thumbnailField.getText().isBlank() && options.thumbnailTarget() == null
                || options.movesDuplicates() && !duplicatesField.getText().isBlank() && options.duplicatesTarget() == null) {
            showStatus("Niepoprawna ścieżka folderu.", true);
            return;
        }
        String problem = options.validate();
        if (problem != null) {
            showStatus(problem, true);
            return;
        }
        showStatus("", false);
        onStart.accept(options);
    }

}
