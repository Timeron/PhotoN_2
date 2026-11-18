package com.photon.thumbnails;

import com.photon.AppPaths;
import com.photon.JsonFileStore;
import com.photon.duplicates.ScanProgressView;
import com.photon.settings.SettingsService;
import com.photon.ui.Desktops;
import com.photon.ui.Dialogs;
import com.photon.ui.Texts;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Moduł „Miniaturyzacja”: formularz ↔ postęp tworzenia miniatur.
 * Ostatnio użyte ustawienia są zapamiętywane w {@code thumbnails.json}.
 */
public class ThumbnailsModule extends StackPane {

    private final SettingsService settings;
    private final JsonFileStore<ThumbnailOptions> store;
    private final ThumbnailsView form;
    private Task<ThumbnailGenerator.Summary> running;

    public ThumbnailsModule(ThumbnailProfileStore profiles, SettingsService settings) {
        this(profiles, settings, new JsonFileStore<>(AppPaths.dataDir().resolve("thumbnails.json"),
                ThumbnailOptions.class, ThumbnailOptions.defaults()));
    }

    /** @param store ostatnio użyte ustawienia (wczytywane przy otwarciu formularza) */
    public ThumbnailsModule(ThumbnailProfileStore profiles, SettingsService settings,
                            JsonFileStore<ThumbnailOptions> store) {
        this.settings = settings;
        this.store = store;
        this.form = new ThumbnailsView(profiles, store.load());
        form.setOnStart(this::start);
        getStyleClass().add("thumbnails-module");
        show(form);
    }

    public ThumbnailsView form() {
        return form;
    }

    /** Pokazuje formularz (np. przy otwieraniu profilu). W trakcie pracy nic nie robi. */
    public void showForm() {
        if (!isRunning()) {
            show(form);
        }
    }

    public boolean isRunning() {
        return running != null && running.isRunning();
    }

    public void start(ThumbnailOptions options) {
        if (isRunning()) {
            return;
        }
        try {
            store.save(options);
        } catch (IOException e) {
            System.err.println("Nie udało się zapamiętać ustawień miniatur: " + e.getMessage());
        }
        int threads = settings.get().effectiveThreads();
        Task<ThumbnailGenerator.Summary> task = new Task<>() {
            @Override
            protected ThumbnailGenerator.Summary call() {
                return new ThumbnailGenerator(options, threads).run((message, done, total) -> {
                    updateMessage(message);
                    if (total > 0) {
                        updateProgress(done, total);
                    } else {
                        updateProgress(-1, 1);
                    }
                }, this::isCancelled);
            }
        };
        task.setOnSucceeded(e -> finished(options, task.getValue()));
        task.setOnCancelled(e -> {
            show(form);
            form.showStatus("Tworzenie miniatur zostało przerwane. Gotowe miniatury pozostały w folderze.", false);
        });
        task.setOnFailed(e -> {
            show(form);
            Throwable error = task.getException();
            if (error instanceof ThumbnailGenerator.CancelledException) {
                form.showStatus("Tworzenie miniatur zostało przerwane.", false);
            } else {
                form.showStatus("Tworzenie miniatur nie powiodło się.", true);
                Dialogs.error(window(), "Błąd", error == null ? "Nieznany błąd" : String.valueOf(error.getMessage()));
            }
        });

        running = task;
        show(new ScanProgressView(task, "Trwa tworzenie miniatur", task::cancel));
        Thread thread = new Thread(task, "photon-thumbnails");
        thread.setDaemon(true);
        if (settings.get().lowPriorityScan()) {
            thread.setPriority(Thread.MIN_PRIORITY);
        }
        thread.start();
    }

    private void finished(ThumbnailOptions options, ThumbnailGenerator.Summary summary) {
        show(form);
        StringBuilder text = new StringBuilder();
        text.append("Utworzono ").append(Texts.count(summary.created(), "miniaturę", "miniatury", "miniatur"));
        if (summary.created() > 0) {
            text.append(" (").append(Texts.formatBytes(summary.sourceBytes())).append(" → ")
                    .append(Texts.formatBytes(summary.thumbnailBytes())).append(")");
        }
        if (summary.keptSize() > 0) {
            text.append(", w tym ").append(summary.keptSize()).append(" bez zmniejszania (już były mniejsze)");
        }
        text.append(".");
        if (summary.skippedExisting() > 0) {
            text.append(" Pominięto istniejące: ").append(summary.skippedExisting()).append(".");
        }
        if (summary.unsupported() > 0) {
            text.append(" Pominięto zdjęcia w innych formatach niż JPEG: ").append(summary.unsupported()).append(".");
        }
        if (!summary.errors().isEmpty()) {
            text.append(" Błędy: ").append(summary.errors().size()).append(".");
        }
        text.append(" Czas: ").append(Math.max(0, summary.elapsed().toSeconds())).append(" s.");
        form.showStatus(text.toString(), !summary.errors().isEmpty());

        Path target = options.target();
        form.setOnOpenTarget(() -> Desktops.openFolder(target,
                message -> Dialogs.error(window(), "Błąd", message)));

        if (!summary.errors().isEmpty()) {
            List<String> errors = summary.errors();
            Dialogs.error(window(), "Nie wszystkie miniatury zostały utworzone",
                    Texts.count(errors.size(), "błąd", "błędy", "błędów") + ":\n"
                            + String.join("\n", errors.subList(0, Math.min(10, errors.size())))
                            + (errors.size() > 10 ? "\n…" : ""));
        }
    }

    private void show(Node page) {
        getChildren().setAll(page);
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
