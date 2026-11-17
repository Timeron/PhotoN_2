package com.photon.settings;

import com.photon.JsonFileStore;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Bieżące ustawienia aplikacji: odczyt przy starcie, zapis z formularza.
 * Moduły mogą nasłuchiwać {@link #settingsProperty()}, aby reagować na zmiany.
 */
public class SettingsService {

    private final JsonFileStore<AppSettings> store;
    private final ReadOnlyObjectWrapper<AppSettings> current = new ReadOnlyObjectWrapper<>();

    public SettingsService(Path file) {
        this.store = new JsonFileStore<>(file, AppSettings.class, AppSettings.defaults());
        current.set(store.load());
    }

    public AppSettings get() {
        return current.get();
    }

    public ReadOnlyObjectProperty<AppSettings> settingsProperty() {
        return current.getReadOnlyProperty();
    }

    public Path getFile() {
        return store.getFile();
    }

    public void save(AppSettings settings) throws IOException {
        store.save(settings);
        current.set(settings);
    }
}
