package com.photon.duplicates;

import com.photon.duplicates.scan.DuplicateReport;
import com.photon.duplicates.scan.DuplicateScanner;
import com.photon.duplicates.scan.HashCache;
import com.photon.duplicates.scan.ScanResult;
import com.photon.settings.AppSettings;
import com.photon.settings.SettingsService;
import com.photon.ui.Dialogs;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.nio.file.Path;
import com.photon.CancelledException;
import com.photon.ui.Background;

/**
 * Moduł „Szukaj duplikatów”: przełącza między formularzem konfiguracji,
 * postępem skanowania i wynikami.
 */
public class DuplicatesModule extends StackPane {

    /** Wynik zadania skanowania wraz z informacją o zapisanym raporcie. */
    private record Outcome(ScanResult result, Path report, String reportError) {
    }

    private final SettingsService settings;
    private final DuplicateSearchView form;
    private Task<Outcome> running;
    private DuplicateResultsView results;

    public DuplicatesModule(DuplicateProfileStore profileStore, SettingsService settings) {
        this.settings = settings;
        this.form = new DuplicateSearchView(profileStore, () -> settings.get().effectiveThreads());
        form.setOnStartScan(this::startScan);
        getStyleClass().add("duplicates-module");
        show(form);
    }

    public DuplicateSearchView form() {
        return form;
    }

    public boolean isScanning() {
        return running != null && running.isRunning();
    }

    /** Ostatnie wyniki (null, gdy jeszcze nie skanowano). */
    public DuplicateResultsView results() {
        return results;
    }

    /** Pokazuje formularz (np. przy otwieraniu profilu). W trakcie skanowania nic nie robi. */
    public void showForm() {
        if (!isScanning()) {
            show(form);
        }
    }

    public void startScan(DuplicateSearchOptions options) {
        if (isScanning()) {
            return;
        }
        AppSettings s = settings.get();
        HashCache cache = options.useHashCache() ? HashCache.inCacheDir(s.effectiveCacheDir()) : null;
        Path reportsDir = s.effectiveReportsDir();

        Task<Outcome> task = new Task<>() {
            @Override
            protected Outcome call() {
                updateMessage("Przygotowanie…");
                updateProgress(-1, 1);
                if (cache != null) {
                    cache.load();
                }
                ScanResult result = new DuplicateScanner(options, cache).scan((message, done, total) -> {
                    updateMessage(message);
                    if (total > 0) {
                        updateProgress(done, total);
                    } else {
                        updateProgress(-1, 1);
                    }
                }, this::isCancelled);

                Path report = null;
                String reportError = null;
                if (options.saveReport()) {
                    report = reportsDir.resolve(DuplicateReport.defaultFileName(result));
                    try {
                        DuplicateReport.write(result.groups(), report);
                    } catch (IOException e) {
                        reportError = e.getMessage();
                        report = null;
                    }
                }
                return new Outcome(result, report, reportError);
            }
        };

        task.setOnSucceeded(e -> showResults(task.getValue()));
        task.setOnCancelled(e -> {
            show(form);
            form.showStatus("Skanowanie zostało przerwane.", false);
        });
        task.setOnFailed(e -> {
            show(form);
            Throwable error = task.getException();
            if (error instanceof CancelledException) {
                form.showStatus("Skanowanie zostało przerwane.", false);
            } else {
                form.showStatus("Skanowanie nie powiodło się.", true);
                Dialogs.error(getScene() == null ? null : getScene().getWindow(), "Błąd skanowania",
                        error == null ? "Nieznany błąd" : String.valueOf(error.getMessage()));
            }
        });

        running = task;
        show(new ScanProgressView(task, "Trwa wyszukiwanie duplikatów", task::cancel));

        Background.start(task, "photon-duplicate-scan", s.lowPriorityScan());
    }

    private void showResults(Outcome outcome) {
        results = new DuplicateResultsView(outcome.result(), settings);
        results.setOnBack(() -> show(form));
        results.setOnRescan(() -> startScan(outcome.result().options()));
        if (outcome.report() != null) {
            results.addNotice("Raport zapisano w pliku: " + outcome.report());
        } else if (outcome.reportError() != null) {
            results.addNotice("Nie udało się zapisać raportu: " + outcome.reportError());
        }
        DuplicateResultsView shown = results;
        form.setOnShowLastResults(() -> show(shown));
        show(results);
        form.showStatus("", false);
    }

    private void show(Node page) {
        getChildren().setAll(page);
    }
}
