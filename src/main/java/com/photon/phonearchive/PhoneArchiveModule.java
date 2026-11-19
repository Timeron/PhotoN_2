package com.photon.phonearchive;

import com.photon.AppPaths;
import com.photon.JsonFileStore;
import com.photon.duplicates.ScanProgressView;
import com.photon.duplicates.scan.HashCache;
import com.photon.settings.SettingsService;
import com.photon.ui.Desktops;
import com.photon.ui.Dialogs;
import com.photon.ui.Texts;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Moduł „Archiwizacja telefonu”: formularz ↔ postęp archiwizacji ↔ pytanie o usunięcie duplikatów.
 * Ostatnio użyte ustawienia są zapamiętywane w {@code phone-archive.json}.
 */
public class PhoneArchiveModule extends StackPane {

    private final SettingsService settings;
    private final JsonFileStore<PhoneArchiveOptions> store;
    private final Supplier<LocalDate> today;
    private final PhoneArchiveView form;
    private Task<?> running;

    public PhoneArchiveModule(PhoneArchiveProfileStore profiles, SettingsService settings) {
        this(profiles, settings, new JsonFileStore<>(AppPaths.dataDir().resolve("phone-archive.json"),
                PhoneArchiveOptions.class, PhoneArchiveOptions.defaults()), LocalDate::now);
    }

    public PhoneArchiveModule(PhoneArchiveProfileStore profiles, SettingsService settings,
                              JsonFileStore<PhoneArchiveOptions> store, Supplier<LocalDate> today) {
        this.settings = settings;
        this.store = store;
        this.today = today;
        this.form = new PhoneArchiveView(profiles, store.load(), today);
        form.setOnStart(this::start);
        getStyleClass().add("phone-archive-module");
        show(form);
    }

    public PhoneArchiveView form() {
        return form;
    }

    public void showForm() {
        if (!isRunning()) {
            show(form);
        }
    }

    public boolean isRunning() {
        return running != null && running.isRunning();
    }

    public void start(PhoneArchiveOptions options) {
        if (isRunning()) {
            return;
        }
        boolean hadDateFolders = form.hasDateFolders();
        if (!hadDateFolders) {
            boolean proceed = Dialogs.confirm(window(), "Brak folderów z datą w archiwum",
                    "W folderze archiwum nie ma żadnego folderu o nazwie w formacie RRRR-MM-DD (np. 2016-02-23), "
                            + "więc nie da się sprawdzić, czy zdjęcia i filmy były już archiwizowane. "
                            + "Wszystkie zostaną przeniesione do nowego folderu "
                            + today.get().format(PhoneArchiver.DAY_FORMAT) + ".\n\nCzy mimo to archiwizować?",
                    "Archiwizuj", false);
            if (!proceed) {
                form.showStatus("Archiwizacja anulowana.", false);
                return;
            }
        }
        try {
            store.save(options);
        } catch (IOException e) {
            System.err.println("Nie udało się zapamiętać ustawień archiwizacji: " + e.getMessage());
        }

        LocalDate day = today.get();
        Path cacheDir = settings.get().effectiveCacheDir();
        Task<PhoneArchiver.Summary> task = new Task<>() {
            @Override
            protected PhoneArchiver.Summary call() {
                HashCache cache = HashCache.inCacheDir(cacheDir).load();
                try {
                    return new PhoneArchiver(options, day, cache)
                            .expectingDateFolders(hadDateFolders)
                            .run((message, done, total) -> {
                        updateMessage(message);
                        if (total > 0 && done >= 0) {
                            updateProgress(done, total);
                        } else {
                            updateProgress(-1, 1);
                        }
                    }, this::isCancelled);
                } finally {
                    try {
                        cache.save();
                    } catch (IOException e) {
                        System.err.println("Nie udało się zapisać pamięci podręcznej: " + e.getMessage());
                    }
                }
            }
        };
        task.setOnSucceeded(e -> finished(options, task.getValue()));
        task.setOnCancelled(e -> {
            show(form);
            form.refreshDateFolders();
            form.showStatus("Archiwizacja została przerwana. Przeniesione pliki pozostały w archiwum.", false);
        });
        task.setOnFailed(e -> {
            show(form);
            form.refreshDateFolders();
            Throwable error = task.getException();
            if (error instanceof PhoneArchiver.CancelledException) {
                form.showStatus("Archiwizacja została przerwana. Przeniesione pliki pozostały w archiwum.", false);
            } else if (error instanceof PhoneArchiver.InsufficientSpaceException) {
                form.showStatus("Archiwizacja przerwana — za mało miejsca na dysku.", true);
                Dialogs.error(window(), "Za mało miejsca", error.getMessage());
            } else if (error instanceof PhoneArchiver.ArchiveUnavailableException) {
                form.showStatus("Archiwizacja przerwana — archiwum jest niedostępne.", true);
                Dialogs.error(window(), "Archiwum niedostępne", error.getMessage()
                        + "\n\nPliki, których nie zarchiwizowano, zostały w folderze archiwizowanym. "
                        + "Sprawdź połączenie z dyskiem i uruchom archiwizację ponownie.");
            } else {
                form.showStatus("Archiwizacja nie powiodła się.", true);
                Dialogs.error(window(), "Błąd", error == null ? "Nieznany błąd" : String.valueOf(error.getMessage()));
            }
        });

        running = task;
        show(new ScanProgressView(task, "Trwa archiwizacja", task::cancel));
        Thread thread = new Thread(task, "photon-phone-archive");
        thread.setDaemon(true);
        if (settings.get().lowPriorityScan()) {
            thread.setPriority(Thread.MIN_PRIORITY);
        }
        thread.start();
    }

    private void finished(PhoneArchiveOptions options, PhoneArchiver.Summary summary) {
        show(form);
        form.refreshDateFolders();
        String text = summaryText(summary);
        form.showStatus(text, !summary.errors().isEmpty());

        form.setOnOpenDuplicates(null);
        Path dayFolder = summary.dayFolder();
        form.setOnOpenTarget(Files.isDirectory(dayFolder) ? () -> Desktops.openFolder(dayFolder,
                message -> Dialogs.error(window(), "Błąd", message)) : null);

        if (!summary.errors().isEmpty()) {
            List<String> errors = summary.errors();
            Dialogs.error(window(), "Nie wszystkie pliki zostały zarchiwizowane",
                    Texts.count(errors.size(), "błąd", "błędy", "błędów") + ":\n"
                            + String.join("\n", errors.subList(0, Math.min(10, errors.size())))
                            + (errors.size() > 10 ? "\n…" : ""));
        }
        if (!summary.duplicates().isEmpty()) {
            askAboutDuplicates(options, summary.duplicates(), text);
        }
    }

    static String summaryText(PhoneArchiver.Summary summary) {
        StringBuilder text = new StringBuilder();
        int archived = summary.moved().size() + summary.copied().size();
        text.append("Zarchiwizowano ").append(Texts.count(archived, "plik", "pliki", "plików"));
        if (archived > 0) {
            text.append(" (").append(Texts.formatBytes(summary.archivedBytes())).append(") do folderu ")
                    .append(summary.dayFolder().getFileName());
        }
        if (!summary.copied().isEmpty()) {
            text.append(", w tym skopiowano z chronionych folderów: ").append(summary.copied().size());
        }
        text.append(".");
        PhoneArchiver.ThumbnailStats thumbs = summary.thumbnails();
        if (thumbs.folder() != null) {
            text.append(" Miniatury: ").append(thumbs.created());
            if (thumbs.existing() > 0) {
                text.append(", pominięte istniejące: ").append(thumbs.existing());
            }
            if (thumbs.unsupported() > 0) {
                text.append(", bez miniatury (format inny niż JPEG): ").append(thumbs.unsupported());
            }
            text.append(".");
        }
        if (!summary.duplicates().isEmpty()) {
            text.append(" Już w archiwum: ").append(summary.duplicates().size()).append(".");
        }
        if (!summary.incomplete().isEmpty()) {
            text.append(" Pominięto niekompletne pliki: ").append(summary.incomplete().size())
                    .append(" (przesyłanie trwa albo zostało przerwane — zostały w folderze archiwizowanym).");
        }
        if (!summary.errors().isEmpty()) {
            text.append(" Błędy: ").append(summary.errors().size()).append(".");
        }
        text.append(" Czas: ").append(Math.max(0, summary.elapsed().toSeconds())).append(" s.");
        return text.toString();
    }

    private void askAboutDuplicates(PhoneArchiveOptions options, List<PhoneArchiver.Duplicate> duplicates,
                                    String summaryText) {
        Path target = options.target();
        List<String> details = new ArrayList<>();
        long bytes = 0;
        for (PhoneArchiver.Duplicate d : duplicates) {
            bytes += d.size();
            String existing = target != null && d.existing().startsWith(target)
                    ? target.relativize(d.existing()).toString() : d.existing().toString();
            details.add(d.source() + "   →   " + existing);
        }
        boolean move = options.movesDuplicates();
        boolean toTrash = !move && PhoneArchiver.trashSupported();
        Path folder = options.duplicatesTarget();
        String intro = Texts.count(duplicates.size(), "plik", "pliki", "plików") + " ("
                + Texts.formatBytes(bytes) + ") z folderu archiwizowanego już jest w archiwum, więc nie "
                + (duplicates.size() == 1 ? "został zarchiwizowany" : "zostały zarchiwizowane") + ". ";
        String message = move
                ? intro + "Czy przenieść " + (duplicates.size() == 1 ? "go" : "je") + " do folderu zastępczego (śmietnika):\n" + folder
                + "\n\nStruktura podfolderów zostanie zachowana. Kopie w archiwum pozostaną."
                : intro + "Czy usunąć " + (duplicates.size() == 1 ? "go" : "je")
                + " z folderu archiwizowanego? Kopie w archiwum pozostaną.\n\n"
                + (toTrash ? "Pliki trafią do kosza systemowego." : "Pliki zostaną usunięte trwale.");
        boolean confirmed = Dialogs.confirmWithDetails(window(), "Duplikaty już są w archiwum", message, details,
                move ? "Przenieś" : (toTrash ? "Przenieś do kosza" : "Usuń trwale"), !move);
        if (!confirmed) {
            form.showStatus(summaryText + " Duplikaty pozostawiono w folderze archiwizowanym.", false);
            return;
        }

        Path source = options.source();
        Task<PhoneArchiver.RemovalOutcome> task = new Task<>() {
            @Override
            protected PhoneArchiver.RemovalOutcome call() {
                return move ? PhoneArchiver.moveDuplicates(duplicates, source, folder)
                        : PhoneArchiver.removeFromSource(duplicates, toTrash);
            }
        };
        task.setOnSucceeded(e -> {
            PhoneArchiver.RemovalOutcome outcome = task.getValue();
            StringBuilder text = new StringBuilder(summaryText).append(" ")
                    .append(move ? "Przeniesiono do śmietnika " : toTrash ? "Przeniesiono do kosza " : "Usunięto ")
                    .append(Texts.count(outcome.removed().size(), "duplikat", "duplikaty", "duplikatów"))
                    .append(".");
            if (outcome.skippedProtected() > 0) {
                text.append(" Pominięto w chronionych folderach „__”: ").append(outcome.skippedProtected()).append(".");
            }
            if (!outcome.errors().isEmpty()) {
                text.append(move ? " Błędy przenoszenia: " : " Błędy usuwania: ").append(outcome.errors().size()).append(".");
                Dialogs.error(window(), move ? "Nie wszystkie duplikaty zostały przeniesione"
                                : "Nie wszystkie duplikaty zostały usunięte",
                        String.join("\n", outcome.errors().subList(0, Math.min(10, outcome.errors().size()))));
            }
            form.showStatus(text.toString(), !outcome.errors().isEmpty());
            if (move && Files.isDirectory(folder)) {
                form.setOnOpenDuplicates(() -> Desktops.openFolder(folder,
                        msg -> Dialogs.error(window(), "Błąd", msg)));
            }
        });
        task.setOnFailed(e -> form.showStatus((move ? "Przenoszenie" : "Usuwanie") + " duplikatów nie powiodło się: "
                + task.getException().getMessage(), true));
        running = task;
        form.showStatus(move ? "Przenoszenie duplikatów…" : "Usuwanie duplikatów…", false);
        Thread thread = new Thread(task, "photon-phone-archive-duplicates");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Node page) {
        getChildren().setAll(page);
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
