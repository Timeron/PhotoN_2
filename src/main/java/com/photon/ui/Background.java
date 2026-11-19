package com.photon.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;

import java.util.function.Consumer;

/**
 * Praca w tle, żeby okno nie przestawało odpowiadać (skanowanie, archiwizacja, dysk sieciowy, serwer zdalny).
 * Wątki są demonami — nie przytrzymują zamykanego programu. Wyniki i błędy wracają w wątku JavaFX.
 */
public final class Background {

    /** Działanie w tle bez wyniku. */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** Działanie w tle z wynikiem. */
    @FunctionalInterface
    public interface Work<T> {
        T get() throws Exception;
    }

    private Background() {
    }

    /**
     * Uruchamia zadanie JavaFX (z postępem i anulowaniem) w osobnym wątku.
     *
     * @param lowPriority najniższy priorytet wątku (ustawienie „niski priorytet skanowania”)
     */
    public static <T> Task<T> start(Task<T> task, String name, boolean lowPriority) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        if (lowPriority) {
            thread.setPriority(Thread.MIN_PRIORITY);
        }
        thread.start();
        return task;
    }

    /** Wykonuje działanie w tle; komunikat ewentualnego błędu trafia do {@code onError} w wątku JavaFX. */
    public static void run(String name, Action action, Consumer<String> onError) {
        thread(name, () -> {
            try {
                action.run();
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(Texts.errorMessage(e)));
            }
        });
    }

    /** Liczy wynik w tle; wynik albo błąd trafia do odpowiedniej funkcji w wątku JavaFX. */
    public static <T> void supply(String name, Work<T> work, Consumer<T> onResult, Consumer<Exception> onError) {
        thread(name, () -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onResult.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    private static void thread(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }
}
