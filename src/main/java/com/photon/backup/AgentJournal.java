package com.photon.backup;

import java.io.PrintStream;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wyjście agenta: dziennik ({@code kopia-zapasowa.log}, opcjonalnie konsola) i obserwator (ikona w zasobniku).
 * Błąd obserwatora nigdy nie przerywa pracy agenta. Przy kilku profilach wpisy dotyczące profilu mają
 * przedrostek z jego nazwą ({@link #forProfile}).
 */
final class AgentJournal {

    private final AgentFiles files;
    private final Clock clock;
    private final PrintStream console;
    private final AtomicReference<AgentLoop.Observer> observer;
    private final String prefix;

    /** @param console gdzie dodatkowo wypisywać dziennik albo null */
    AgentJournal(AgentFiles files, Clock clock, PrintStream console) {
        this(files, clock, console, new AtomicReference<>(), "");
    }

    private AgentJournal(AgentFiles files, Clock clock, PrintStream console,
                         AtomicReference<AgentLoop.Observer> observer, String prefix) {
        this.files = files;
        this.clock = clock;
        this.console = console;
        this.observer = observer;
        this.prefix = prefix;
    }

    /** Dziennik dla jednego profilu: ten sam plik i obserwator, wpisy z przedrostkiem {@code [Nazwa]}. */
    AgentJournal forProfile(String name, boolean withPrefix) {
        return new AgentJournal(files, clock, console, observer, withPrefix ? "[" + name + "] " : "");
    }

    void setObserver(AgentLoop.Observer observer) {
        this.observer.set(observer);
    }

    void log(String text) {
        BackupLog.append(files.logFile(), clock.instant(), prefix + text);
        if (console != null) {
            console.println(prefix + text);
        }
    }

    /** Zapisuje w dzienniku i pokazuje powiadomienie. */
    void logAndNotify(String title, String text, boolean warning) {
        log(text);
        notify(title, text, warning);
    }

    void notify(String title, String text, boolean warning) {
        AgentLoop.Observer current = observer.get();
        if (current == null) {
            return;
        }
        try {
            current.notify(title, prefix + text, warning);
        } catch (RuntimeException e) {
            System.err.println("Błąd powiadomienia: " + e.getMessage());
        }
    }

    void statusChanged(AgentStatus status) {
        AgentLoop.Observer current = observer.get();
        if (current == null) {
            return;
        }
        try {
            current.statusChanged(status);
        } catch (RuntimeException e) {
            System.err.println("Błąd ikony w zasobniku: " + e.getMessage());
        }
    }
}
