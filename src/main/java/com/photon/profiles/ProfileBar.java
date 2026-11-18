package com.photon.profiles;

import com.photon.ui.Dialogs;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignU;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.photon.ui.Forms.iconButton;
import static com.photon.ui.Forms.spacer;

/**
 * Pasek profili formularza: wybór profilu, „niezapisane zmiany”, zapisz, zapisz jako, cofnij,
 * zmień nazwę i usuń. Wspólny dla wszystkich modułów — moduł dostarcza tylko odczyt
 * i wczytanie ustawień formularza.
 *
 * @param <O> typ ustawień modułu (porównywany przez {@code equals} do wykrycia zmian)
 */
public class ProfileBar<O> extends HBox {

    /** Komunikat dla użytkownika: tekst i czy to błąd. */
    public interface Feedback {
        void show(String text, boolean error);
    }

    private static final PseudoClass PROMPT = PseudoClass.getPseudoClass("prompt");

    private final ProfileStore<O> store;
    private final Supplier<O> currentOptions;
    private final Consumer<O> applyOptions;
    private final Feedback feedback;
    private final String what;

    private final ObservableList<Profile<O>> profiles = FXCollections.observableArrayList();
    private final ComboBox<Profile<O>> combo = new ComboBox<>(profiles);
    private final Label unsavedPill = new Label("Niezapisane zmiany");
    private final Button saveProfile = new Button("Zapisz", new FontIcon(MaterialDesignC.CONTENT_SAVE_OUTLINE));
    private final Button saveProfileAs = new Button("Zapisz jako…", new FontIcon(MaterialDesignC.CONTENT_SAVE_EDIT_OUTLINE));
    private final Button revertProfile = iconButton(MaterialDesignU.UNDO, "Cofnij niezapisane zmiany");
    private final Button renameProfile = iconButton(MaterialDesignP.PENCIL_OUTLINE, "Zmień nazwę profilu");
    private final Button deleteProfile = iconButton(MaterialDesignD.DELETE_OUTLINE, "Usuń profil");

    private boolean switching;

    /**
     * @param currentOptions odczyt bieżących ustawień formularza
     * @param applyOptions   wczytanie ustawień profilu do formularza
     * @param what           czego dotyczą ustawienia — do komunikatów, np. „wyszukiwania”
     */
    public ProfileBar(ProfileStore<O> store, Supplier<O> currentOptions, Consumer<O> applyOptions,
                      Feedback feedback, String what) {
        super(10);
        this.store = store;
        this.currentOptions = currentOptions;
        this.applyOptions = applyOptions;
        this.feedback = feedback;
        this.what = what;

        FontIcon icon = new FontIcon(MaterialDesignB.BOOKMARK_OUTLINE);
        icon.getStyleClass().add("group-icon");
        Label label = new Label("Profil", icon);
        label.getStyleClass().add("field-label");
        label.setMinWidth(Region.USE_PREF_SIZE);

        combo.setPromptText("Bez profilu — ustawienia bieżące");
        combo.setPrefWidth(300);
        // Własna komórka: standardowa nie pokazuje promptText po wyczyszczeniu wyboru (setValue(null)).
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Profile<O> item, boolean empty) {
                super.updateItem(item, empty);
                boolean none = empty || item == null;
                setText(none ? combo.getPromptText() : item.name());
                pseudoClassStateChanged(PROMPT, none);
            }
        });
        combo.setTooltip(new Tooltip("Wybierz profil, aby wczytać jego ustawienia"));
        combo.valueProperty().addListener((obs, old, selected) -> onSelected(old, selected));

        unsavedPill.getStyleClass().addAll("pill", "pill-warning");
        unsavedPill.setMinWidth(Region.USE_PREF_SIZE);
        unsavedPill.managedProperty().bind(unsavedPill.visibleProperty());
        unsavedPill.setVisible(false);

        saveProfile.setTooltip(new Tooltip("Zapisz zmiany w wybranym profilu"));
        saveProfile.setOnAction(e -> saveCurrent());
        saveProfileAs.setTooltip(new Tooltip("Zapisz bieżące ustawienia jako nowy profil"));
        saveProfileAs.setOnAction(e -> saveAsNew());
        revertProfile.setOnAction(e -> {
            Profile<O> current = combo.getValue();
            if (current != null) {
                applyOptions.accept(current.options());
            }
        });
        renameProfile.setOnAction(e -> renameCurrent());
        deleteProfile.setOnAction(e -> deleteCurrent());

        getChildren().addAll(label, combo, unsavedPill, spacer(),
                revertProfile, saveProfile, saveProfileAs, renameProfile, deleteProfile);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("profile-bar");
        refreshState();
    }

    public ComboBox<Profile<O>> getCombo() {
        return combo;
    }

    /** Wybrany profil albo null. */
    public Profile<O> selected() {
        return combo.getValue();
    }

    /**
     * Wczytuje listę profili z dysku (mogły zostać zmienione np. w sekcji „Profile”).
     * Zachowuje wybrany profil, jeśli nadal istnieje; formularz pozostaje bez zmian.
     */
    public void reloadProfiles() {
        List<Profile<O>> loaded;
        try {
            loaded = store.loadAll();
        } catch (IOException e) {
            feedback.show("Nie udało się wczytać profili: " + e.getMessage(), true);
            return;
        }
        Profile<O> selected = combo.getValue();
        switching = true;
        try {
            profiles.setAll(loaded);
            combo.setValue(selected == null ? null : find(selected.name()).orElse(null));
        } finally {
            switching = false;
        }
        refreshState();
    }

    /**
     * Wybiera profil o podanej nazwie i wczytuje go do formularza
     * (z pytaniem o niezapisane zmiany, tak jak przy wyborze z listy).
     */
    public void openProfile(String name) {
        reloadProfiles();
        find(name).ifPresentOrElse(
                profile -> {
                    if (profile.equals(combo.getValue())) {
                        // Już wybrany — wczytaj ponownie tylko, gdy nie ma niezapisanych zmian do stracenia.
                        if (!isDirty(profile)) {
                            applyOptions.accept(profile.options());
                        }
                    } else {
                        combo.setValue(profile);
                    }
                },
                () -> feedback.show("Profil „" + name + "” nie istnieje.", true));
    }

    /** Odświeża stan przycisków i znacznik „Niezapisane zmiany” — wołać po każdej zmianie w formularzu. */
    public void refreshState() {
        Profile<O> current = combo.getValue();
        boolean hasProfile = current != null;
        boolean dirty = isDirty(current);

        unsavedPill.setVisible(dirty);
        saveProfile.setDisable(!dirty);
        revertProfile.setDisable(!dirty);
        renameProfile.setDisable(!hasProfile);
        deleteProfile.setDisable(!hasProfile);
    }

    // =====================================================================

    private Optional<Profile<O>> find(String name) {
        return profiles.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    private void onSelected(Profile<O> previous, Profile<O> selected) {
        if (switching) {
            return;
        }
        if (selected != null && previous != null && isDirty(previous)
                && !Dialogs.confirm(window(), "Niezapisane zmiany",
                "Profil „" + previous.name() + "” ma niezapisane zmiany. Wczytać profil „"
                        + selected.name() + "” i odrzucić te zmiany?",
                "Odrzuć i wczytaj", true)) {
            // Przywrócenie wyboru poza bieżącym zdarzeniem zmiany wartości.
            Platform.runLater(() -> selectSilently(previous));
            return;
        }
        if (selected != null) {
            applyOptions.accept(selected.options());
            feedback.show("Wczytano profil „" + selected.name() + "”.", false);
        }
        refreshState();
    }

    private void saveCurrent() {
        Profile<O> current = combo.getValue();
        if (current == null) {
            saveAsNew();
            return;
        }
        Profile<O> updated = new Profile<>(current.name(), Instant.now(), currentOptions.get());
        if (write(updated)) {
            replace(current, updated);
            feedback.show("Zapisano profil „" + updated.name() + "”.", false);
        }
    }

    private void saveAsNew() {
        Profile<O> current = combo.getValue();
        String suggestion = current == null ? "" : current.name() + " (kopia)";
        Dialogs.askText(window(), "Zapisz jako nowy profil",
                        "Podaj nazwę, pod którą zapisać bieżące ustawienia " + what + ".",
                        "Zapisz", suggestion, name -> validateName(name, null))
                .ifPresent(name -> {
                    Profile<O> created = new Profile<>(name, Instant.now(), currentOptions.get());
                    if (write(created)) {
                        profiles.add(created);
                        sort();
                        selectSilently(created);
                        feedback.show("Utworzono profil „" + name + "”.", false);
                    }
                });
    }

    private void renameCurrent() {
        Profile<O> current = combo.getValue();
        if (current == null) {
            return;
        }
        Dialogs.askText(window(), "Zmień nazwę profilu",
                        "Nowa nazwa profilu „" + current.name() + "”. Niezapisane zmiany ustawień nie zostaną zapisane.",
                        "Zmień nazwę", current.name(), name -> validateName(name, current))
                .filter(name -> !name.equals(current.name()))
                .ifPresent(name -> {
                    try {
                        Profile<O> renamed = store.rename(current, name);
                        replace(current, renamed);
                        sort();
                        selectSilently(renamed);
                        feedback.show("Zmieniono nazwę profilu na „" + name + "”.", false);
                    } catch (IOException e) {
                        Dialogs.error(window(), "Błąd zmiany nazwy",
                                "Nie udało się zmienić nazwy profilu: " + e.getMessage());
                    }
                });
    }

    private void deleteCurrent() {
        Profile<O> current = combo.getValue();
        if (current == null || !Dialogs.confirm(window(), "Usuń profil",
                "Czy na pewno usunąć profil „" + current.name() + "”? Tej operacji nie można cofnąć. "
                        + "Bieżące ustawienia formularza pozostaną bez zmian.",
                "Usuń", true)) {
            return;
        }
        try {
            store.delete(current);
            selectSilently(null);
            profiles.remove(current);
            feedback.show("Usunięto profil „" + current.name() + "”.", false);
        } catch (IOException e) {
            Dialogs.error(window(), "Błąd usuwania", "Nie udało się usunąć profilu: " + e.getMessage());
        }
    }

    private boolean write(Profile<O> profile) {
        try {
            store.save(profile);
            return true;
        } catch (IOException e) {
            Dialogs.error(window(), "Błąd zapisu",
                    "Nie udało się zapisać profilu w " + store.getDirectory() + ":\n" + e.getMessage());
            return false;
        }
    }

    /** Podmienia profil na liście, zachowując go jako wybrany. */
    private void replace(Profile<O> old, Profile<O> replacement) {
        switching = true;
        try {
            int index = profiles.indexOf(old);
            if (index >= 0) {
                profiles.set(index, replacement);
            } else {
                profiles.add(replacement);
            }
            combo.setValue(replacement);
        } finally {
            switching = false;
        }
        refreshState();
    }

    private void selectSilently(Profile<O> profile) {
        switching = true;
        try {
            combo.setValue(profile);
        } finally {
            switching = false;
        }
        refreshState();
    }

    private void sort() {
        Profile<O> selected = combo.getValue();
        switching = true;
        try {
            FXCollections.sort(profiles, Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
            combo.setValue(selected);
        } finally {
            switching = false;
        }
    }

    private String validateName(String name, Profile<O> renaming) {
        String problem = ProfileStore.validateName(name);
        if (problem != null) {
            return problem;
        }
        String trimmed = name.trim();
        boolean taken = profiles.stream()
                .filter(p -> p != renaming)
                .anyMatch(p -> p.name().equalsIgnoreCase(trimmed));
        return taken ? "Profil o tej nazwie już istnieje." : null;
    }

    private boolean isDirty(Profile<O> profile) {
        return profile != null && !Objects.equals(currentOptions.get(), profile.options());
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
