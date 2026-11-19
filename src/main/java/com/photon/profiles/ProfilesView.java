package com.photon.profiles;

import com.photon.ui.Dialogs;
import com.photon.ui.Texts;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignO;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.scene.control.ContentDisplay;

/**
 * Lista wszystkich zapisanych profili ze wszystkich modułów.
 */
public class ProfilesView extends BorderPane {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
                    .withZone(ZoneId.systemDefault());

    private final List<ProfileSource> sources;
    private final ObservableList<ProfileItem> items = FXCollections.observableArrayList();
    private final FilteredList<ProfileItem> filtered = new FilteredList<>(items);
    private final ListView<ProfileItem> list = new ListView<>(filtered);
    private final TextField search = new TextField();
    private final Label countLabel = new Label();
    private final Label placeholderTitle = new Label();
    private final Label placeholderHint = new Label();

    private Consumer<ProfileItem> onOpen = item -> { };

    public ProfilesView(List<ProfileSource> sources) {
        this.sources = List.copyOf(sources);
        getStyleClass().add("profiles-view");

        setTop(buildToolbar());
        setCenter(buildList());
    }

    /** Akcja „Otwórz” — przejście do modułu i wczytanie profilu. */
    public void setOnOpen(Consumer<ProfileItem> handler) {
        this.onOpen = handler;
    }

    /** Wczytuje profile ponownie z dysku. */
    public void refresh() {
        List<ProfileItem> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ProfileSource source : sources) {
            try {
                loaded.addAll(source.loadAll());
            } catch (IOException e) {
                errors.add(source.module().getTitle() + ": " + e.getMessage());
            }
        }
        loaded.sort(Comparator.comparing((ProfileItem p) -> p.module().ordinal())
                .thenComparing(p -> p.name().toLowerCase(Locale.ROOT)));
        items.setAll(loaded);
        updateCount();

        if (!errors.isEmpty()) {
            Dialogs.error(getScene() == null ? null : getScene().getWindow(),
                    "Błąd odczytu profili", String.join("\n", errors));
        }
    }

    // =====================================================================
    // Budowa widoku
    // =====================================================================

    private Node buildToolbar() {
        FontIcon searchIcon = new FontIcon(MaterialDesignM.MAGNIFY);
        searchIcon.getStyleClass().add("search-icon");
        search.setPromptText("Szukaj po nazwie, module lub ustawieniach…");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, old, text) -> applyFilter(text));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
            }
        });
        StackPane searchBox = new StackPane(search, searchIcon);
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 12));
        searchBox.setMaxWidth(420);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        countLabel.getStyleClass().add("field-hint");
        countLabel.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);

        Button refresh = new Button("Odśwież", new FontIcon(MaterialDesignR.REFRESH));
        refresh.setOnAction(e -> refresh());

        HBox bar = new HBox(14, searchBox, countLabel, spacer, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        BorderPane.setMargin(bar, new Insets(0, 0, 16, 0));
        return bar;
    }

    private Node buildList() {
        list.getStyleClass().add("profile-list");
        list.setCellFactory(v -> new ProfileCell());
        list.setOnKeyPressed(e -> {
            ProfileItem selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            if (e.getCode() == KeyCode.ENTER) {
                onOpen.accept(selected);
            } else if (e.getCode() == KeyCode.DELETE) {
                delete(selected);
            }
        });

        FontIcon icon = new FontIcon(MaterialDesignB.BOOKMARK_OUTLINE);
        icon.getStyleClass().add("empty-state-icon");
        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("empty-state-badge");
        badge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        placeholderTitle.getStyleClass().add("empty-state-title");
        placeholderHint.getStyleClass().add("empty-state-hint");
        placeholderHint.setWrapText(true);
        placeholderHint.setMaxWidth(460);
        placeholderHint.setStyle("-fx-text-alignment: center;");
        VBox placeholder = new VBox(14, badge, placeholderTitle, placeholderHint);
        placeholder.setAlignment(Pos.CENTER);
        list.setPlaceholder(placeholder);

        return list;
    }

    // =====================================================================
    // Logika
    // =====================================================================

    private void applyFilter(String text) {
        String query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        filtered.setPredicate(query.isEmpty() ? null : item ->
                item.name().toLowerCase(Locale.ROOT).contains(query)
                        || item.module().getTitle().toLowerCase(Locale.ROOT).contains(query)
                        || (item.summary() != null && item.summary().toLowerCase(Locale.ROOT).contains(query)));
        updateCount();
    }

    private void updateCount() {
        int total = items.size();
        int shown = filtered.size();
        countLabel.setText(shown == total
                ? Texts.count(total, "profil", "profile", "profili")
                : shown + " z " + Texts.count(total, "profilu", "profili", "profili"));

        if (total == 0) {
            placeholderTitle.setText("Brak zapisanych profili");
            placeholderHint.setText("Profil utworzysz w module „Szukaj Duplikatów” przyciskiem „Zapisz jako…”.");
        } else {
            placeholderTitle.setText("Brak wyników");
            placeholderHint.setText("Żaden profil nie pasuje do „" + search.getText().trim() + "”.");
        }
    }

    private void delete(ProfileItem item) {
        if (!Dialogs.confirm(getScene().getWindow(), "Usuń profil",
                "Czy na pewno usunąć profil „" + item.name() + "” z modułu „" + item.module().getTitle()
                        + "”? Tej operacji nie można cofnąć.",
                "Usuń", true)) {
            return;
        }
        ProfileSource source = sources.stream().filter(s -> s.module() == item.module()).findFirst().orElse(null);
        if (source == null) {
            return;
        }
        try {
            source.delete(item);
        } catch (IOException e) {
            Dialogs.error(getScene().getWindow(), "Błąd usuwania",
                    "Nie udało się usunąć profilu: " + e.getMessage());
        }
        refresh();
    }

    // =====================================================================
    // Komórka listy
    // =====================================================================

    private final class ProfileCell extends ListCell<ProfileItem> {

        private final FontIcon moduleIcon = new FontIcon();
        private final Label name = new Label();
        private final Label summary = new Label();
        private final Label modulePill = new Label();
        private final Label updated = new Label();
        private final HBox row;

        ProfileCell() {
            moduleIcon.getStyleClass().add("form-section-icon");
            StackPane badge = new StackPane(moduleIcon);
            badge.getStyleClass().add("form-section-badge");
            badge.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

            name.getStyleClass().add("profile-name");
            summary.getStyleClass().add("profile-summary");
            VBox texts = new VBox(3, name, summary);
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);

            modulePill.getStyleClass().addAll("pill", "pill-module");
            updated.getStyleClass().add("field-hint");
            VBox meta = new VBox(6, modulePill, updated);
            meta.setAlignment(Pos.CENTER_RIGHT);
            meta.setMinWidth(Region.USE_PREF_SIZE);

            Button open = new Button("Otwórz", new FontIcon(MaterialDesignO.OPEN_IN_APP));
            open.setTooltip(new Tooltip("Przejdź do modułu i wczytaj ten profil"));
            open.setOnAction(e -> {
                if (getItem() != null) {
                    onOpen.accept(getItem());
                }
            });
            Button remove = new Button(null, new FontIcon(MaterialDesignD.DELETE_OUTLINE));
            remove.getStyleClass().add("button-icon");
            remove.setTooltip(new Tooltip("Usuń profil"));
            remove.setOnAction(e -> {
                if (getItem() != null) {
                    delete(getItem());
                }
            });
            HBox actions = new HBox(8, open, remove);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setMinWidth(Region.USE_PREF_SIZE);

            row = new HBox(16, badge, texts, meta, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("profile-row");

            // Karta ma wypełniać całą szerokość listy (ListCell domyślnie daje grafice jej preferowaną szerokość).
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPrefWidth(0);
            row.prefWidthProperty().bind(widthProperty().subtract(2));

            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    onOpen.accept(getItem());
                }
            });
        }

        @Override
        protected void updateItem(ProfileItem item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            moduleIcon.setIconCode(item.module().getIcon());
            name.setText(item.name());
            summary.setText(item.summary());
            modulePill.setText(item.module().getTitle());
            updated.setText(item.updatedAt() == null ? "" : "Zmieniono " + DATE_FORMAT.format(item.updatedAt()));
            setGraphic(row);
        }
    }
}
