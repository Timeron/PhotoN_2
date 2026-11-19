package com.photon.thumbnails;

import com.photon.profiles.ProfileBar;
import com.photon.ui.FormSection;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.photon.ui.Forms.commitSpinner;
import static com.photon.ui.Forms.fieldLabel;
import static com.photon.ui.Forms.infoBanner;
import static com.photon.ui.Forms.formGrid;
import static com.photon.ui.Forms.setupSpinner;
import static com.photon.ui.Forms.withSuffix;

/**
 * Formularz „Miniaturyzacja”: skąd brać zdjęcia, gdzie zapisać miniatury i jaki ma być dłuższy bok.
 */
public class ThumbnailsView extends BorderPane {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final TextField sourceField = new TextField();
    private final TextField targetField = new TextField();
    private FolderField sourceFolder;
    private FolderField targetFolder;
    private final Spinner<Integer> maxSide =
            new Spinner<>(ThumbnailOptions.MIN_SIDE, ThumbnailOptions.MAX_SIDE, ThumbnailOptions.DEFAULT_SIDE, 10);
    private final CheckBox includeSubfolders =
            new CheckBox("Uwzględnij podfoldery (struktura folderów zostanie odtworzona w folderze miniatur)");
    private final CheckBox overwrite = new CheckBox("Zastępuj istniejące miniatury o tej samej nazwie");

    private final Label summary = new Label();
    private final Label feedback = new Label();
    private final Button openTargetButton = new Button("Otwórz folder miniatur",
            new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
    private final Button startButton = new Button("Utwórz miniatury", new FontIcon(MaterialDesignI.IMAGE_MULTIPLE_OUTLINE));

    private final ProfileBar<ThumbnailOptions> profileBar;
    /** Blokuje odświeżanie stanu podczas masowego ustawiania pól formularza. */
    private boolean suppressChanges;

    private Consumer<ThumbnailOptions> onStart = options -> { };

    public ThumbnailsView(ThumbnailProfileStore profileStore, ThumbnailOptions initial) {
        getStyleClass().add("thumbnails-view");
        profileBar = new ProfileBar<>(profileStore, this::options, this::setOptions,
                this::showStatus, "miniaturyzacji");
        BorderPane.setMargin(profileBar, new Insets(0, 24, 14, 0));

        VBox form = new VBox(18, buildFoldersSection(), buildSizeSection(), buildMetadataSection());
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
        maxSide.valueProperty().addListener((obs, old, value) -> onFormChanged());
        includeSubfolders.selectedProperty().addListener((obs, old, value) -> onFormChanged());
        overwrite.selectedProperty().addListener((obs, old, value) -> onFormChanged());

        setOptions(initial);
        profileBar.reloadProfiles();
    }

    /** Wczytuje listę profili z dysku (mogły zostać zmienione w sekcji „Profile”). */
    public void reloadProfiles() {
        profileBar.reloadProfiles();
    }

    /** Wybiera profil o podanej nazwie i wczytuje go do formularza. */
    public void openProfile(String name) {
        profileBar.openProfile(name);
    }

    public ProfileBar<ThumbnailOptions> profileBar() {
        return profileBar;
    }

    public void setOnStart(Consumer<ThumbnailOptions> handler) {
        this.onStart = handler;
    }

    public void setOptions(ThumbnailOptions options) {
        suppressChanges = true;
        try {
            sourceFolder.setPath(options.source());
            targetFolder.setPath(options.target());
            maxSide.getValueFactory().setValue(options.maxSide());
            includeSubfolders.setSelected(options.includeSubfolders());
            overwrite.setSelected(options.overwrite());
        } finally {
            suppressChanges = false;
        }
        onFormChanged();
    }

    private void onFormChanged() {
        if (suppressChanges) {
            return;
        }
        refreshSummary();
        profileBar.refreshState();
    }

    /** Domyślne ustawienia — foldery zostają, bo ich wskazanie jest najbardziej pracochłonne. */
    private void applyDefaults() {
        ThumbnailOptions current = options();
        ThumbnailOptions defaults = ThumbnailOptions.defaults();
        setOptions(new ThumbnailOptions(current.source(), current.target(), defaults.maxSide(),
                defaults.includeSubfolders(), defaults.overwrite()));
        showStatus("", false);
    }

    /** Bieżące wartości formularza (ścieżki mogą być null, gdy pole jest puste lub niepoprawne). */
    public ThumbnailOptions options() {
        commitSpinner(maxSide);
        return new ThumbnailOptions(sourceFolder.getPath(), targetFolder.getPath(), maxSide.getValue(),
                includeSubfolders.isSelected(), overwrite.isSelected());
    }

    public void showStatus(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
    }

    /** Pokazuje przycisk otwierający folder z miniaturami (null = ukryj). */
    public void setOnOpenTarget(Runnable handler) {
        openTargetButton.setOnAction(handler == null ? null : e -> handler.run());
        openTargetButton.setVisible(handler != null);
    }

    // =====================================================================
    // Budowa formularza
    // =====================================================================

    private Node buildFoldersSection() {
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Folder ze zdjęciami"),
                sourceFolder = new FolderField(sourceField, "Wskaż folder, z którego zdjęć powstaną miniatury",
                        "Folder ze zdjęciami"));
        grid.addRow(1, fieldLabel("Folder na miniatury"),
                targetFolder = new FolderField(targetField, "Wskaż folder, w którym zostaną zapisane miniatury",
                        "Folder na miniatury"));
        includeSubfolders.setWrapText(true);
        return new FormSection(MaterialDesignF.FOLDER_IMAGE,
                "Foldery",
                "Miniatury dostaną te same nazwy plików co oryginały. Oryginały nie są zmieniane.",
                grid, includeSubfolders);
    }

    private Node buildSizeSection() {
        setupSpinner(maxSide);
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Dłuższy bok zdjęcia"), withSuffix(maxSide, "px",
                "Szerokość zdjęć poziomych i wysokość pionowych. Mniejsze zdjęcia nie są powiększane."));

        Label quality = infoBanner("Najlepsza jakość: skalowanie filtrem Lanczos-3, zapis JPEG w jakości "
                + ThumbnailMaker.JPEG_QUALITY_PERCENT + "% bez podpróbkowania kolorów (4:4:4). "
                + "Zdjęcia, które już są mniejsze, są kopiowane bez ponownej kompresji.");
        return new FormSection(MaterialDesignI.IMAGE_SIZE_SELECT_LARGE,
                "Rozmiar i jakość",
                "Długość najdłuższego boku miniatury w pikselach.",
                grid, quality);
    }

    private Node buildMetadataSection() {
        Label metadata = infoBanner("Metadane oryginału zostają zachowane: EXIF (data wykonania, aparat, GPS, "
                + "orientacja), IPTC, XMP, profil kolorów ICC i data modyfikacji pliku. "
                + "Do XMP dopisywana jest suma SHA-256 oryginału (" + XmpHash.PREFIX + ":" + XmpHash.PROPERTY + ").");
        Label formats = infoBanner("Obsługiwane są zdjęcia JPEG. Pozostałe formaty (PNG, HEIC, RAW…) są pomijane "
                + "i liczone w podsumowaniu.");
        return new FormSection(MaterialDesignT.TAG_TEXT_OUTLINE,
                "Metadane",
                "Co trafia do miniatury poza samym obrazem.",
                metadata, formats, overwrite);
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

        startButton.getStyleClass().add("button-primary");
        startButton.setDefaultButton(true);
        startButton.setOnAction(e -> start());

        Button reset = new Button("Przywróć domyślne", new FontIcon(MaterialDesignR.RESTORE));
        reset.setOnAction(e -> applyDefaults());

        HBox bar = new HBox(12, texts, openTargetButton, reset, startButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("action-bar");
        BorderPane.setMargin(bar, new Insets(14, 24, 0, 0));
        return bar;
    }

    // =====================================================================
    // Logika
    // =====================================================================

    private void refreshSummary() {
        Integer side = maxSide.getValue();
        summary.setText("Miniatury JPEG o dłuższym boku " + (side == null ? "?" : side) + " px");
    }

    private void start() {
        ThumbnailOptions options = options();
        if (!sourceField.getText().isBlank() && options.source() == null
                || !targetField.getText().isBlank() && options.target() == null) {
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
