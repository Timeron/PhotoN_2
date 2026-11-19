package com.photon.ui;

import com.photon.remote.Locations;
import com.photon.remote.RemoteServers;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pole folderu: na tym komputerze albo na serwerze zdalnym. Lista po lewej wybiera miejsce
 * („Ten komputer” albo nazwa połączenia z Ustawień), pole tekstowe — folder, a „Wybierz…” otwiera
 * okno wyboru folderu na dysku lub na serwerze. Lista jest ukryta, dopóki nie zdefiniowano żadnego serwera.
 */
public class FolderField extends HBox {

    /** Pozycja listy oznaczająca dysk lokalny. */
    public static final String LOCAL = "Ten komputer";

    private final TextField field;
    private final ComboBox<String> location = new ComboBox<>();
    private final ObjectProperty<Path> path = new SimpleObjectProperty<>();
    private final Runnable serversListener = () -> Platform.runLater(this::refreshLocations);

    /**
     * @param field         pole tekstowe z folderem (przekazywane, by widok mógł nadal z niego korzystać)
     * @param prompt        podpowiedź w pustym polu
     * @param chooserTitle  tytuł okna wyboru folderu
     */
    public FolderField(TextField field, String prompt, String chooserTitle) {
        super(8);
        this.field = field;
        field.setPromptText(prompt);
        location.getStyleClass().add("folder-location");
        location.setTooltip(new Tooltip("Gdzie jest folder: na tym komputerze albo na serwerze (Ustawienia → Serwery zdalne)"));
        refreshLocations();
        location.setValue(LOCAL);
        location.managedProperty().bind(location.visibleProperty());

        Button browse = new Button("Wybierz…", new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
        browse.setOnAction(e -> browse(chooserTitle));

        field.textProperty().addListener((obs, old, text) -> update());
        location.valueProperty().addListener((obs, old, value) -> {
            updatePrompt(prompt);
            update();
        });
        RemoteServers.addListener(serversListener);
        // Wklejona pełna ścieżka „[Nazwa]/folder” — po opuszczeniu pola rozbijamy ją na miejsce i folder.
        field.focusedProperty().addListener((obs, was, focused) -> {
            Path current = getPath();
            if (!focused && current != null && field.getText().trim().startsWith("[")) {
                setPath(current);
            }
        });

        getChildren().addAll(location, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        setAlignment(Pos.CENTER_LEFT);
        update();
    }

    public TextField field() {
        return field;
    }

    /** Wskazany folder (null, gdy pole jest puste albo ścieżka jest niepoprawna). */
    public Path getPath() {
        return path.get();
    }

    /** Właściwość zmieniająca się przy każdej zmianie folderu lub miejsca. */
    public ObjectProperty<Path> pathProperty() {
        return path;
    }

    /** Czy coś wpisano (do odróżnienia pustego pola od niepoprawnej ścieżki). */
    public boolean isBlank() {
        return field.getText() == null || field.getText().isBlank();
    }

    public boolean isRemote() {
        String value = location.getValue();
        return value != null && !LOCAL.equals(value);
    }

    public void setPath(Path value) {
        if (value != null && Locations.isRemote(value)) {
            String server = Locations.serverName(value);
            if (!location.getItems().contains(server)) {
                location.getItems().add(server); // połączenie usunięte z Ustawień — pokazujemy, co jest w profilu
            }
            location.setValue(server);
            location.setVisible(true);
            field.setText(Locations.remoteFolder(value));
        } else {
            location.setValue(LOCAL);
            field.setText(value == null ? "" : value.toString());
        }
    }

    private void update() {
        path.set(parse());
    }

    private Path parse() {
        String text = field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.startsWith("[")) {
                return Locations.parse(text); // wklejona pełna ścieżka zdalna
            }
            if (isRemote()) {
                return Locations.remote(location.getValue(), text);
            }
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private void updatePrompt(String prompt) {
        field.setPromptText(isRemote() ? "Folder na serwerze, np. /zdjecia" : prompt);
    }

    private void refreshLocations() {
        String selected = location.getValue();
        List<String> items = new ArrayList<>();
        items.add(LOCAL);
        items.addAll(RemoteServers.names());
        if (selected != null && !items.contains(selected)) {
            items.add(selected);
        }
        location.getItems().setAll(items);
        location.setValue(selected == null ? LOCAL : selected);
        location.setVisible(items.size() > 1);
    }

    private void browse(String title) {
        if (isRemote()) {
            String start = isBlank() ? "/" : (getPath() != null && Locations.isRemote(getPath())
                    ? Locations.remoteFolder(getPath()) : "/");
            RemoteFolderChooser.choose(getScene().getWindow(), location.getValue(), start).ifPresent(field::setText);
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        Path current = getPath();
        if (current != null && !Locations.isRemote(current) && Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            field.setText(dir.getAbsolutePath());
        }
    }
}
