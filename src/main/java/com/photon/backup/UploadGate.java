package com.photon.backup;

import com.photon.remote.Locations;

import com.photon.phonearchive.PhoneArchiver;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Rozpoznaje pliki, które mogą być jeszcze przesyłane (np. z internetu) — takie pliki czekają.
 * Plik jest gotowy dopiero wtedy, gdy:
 * <ul>
 *   <li>nie jest pusty,</li>
 *   <li>jego rozmiar i data modyfikacji nie zmieniły się przez co najmniej {@code quietPeriod}
 *       (liczone od pierwszej obserwacji w tym stanie — nie od daty pliku, bo programy przesyłające
 *       potrafią ustawiać datę oryginału),</li>
 *   <li>żaden inny program nie trzyma go otwartego do zapisu (próba wyłącznej blokady).</li>
 * </ul>
 * Pliki zgłoszone jako duplikaty są pomijane, dopóki się nie zmienią.
 */
public class UploadGate implements PhoneArchiver.FileGate {

    /** Rozmiar + data modyfikacji. */
    record Signature(long size, long modified) {
    }

    private record Observation(Signature signature, Instant since) {
    }

    private final Duration quietPeriod;
    private final Clock clock;
    private Map<Path, Observation> observed = new HashMap<>();
    private Map<Path, Observation> seenThisCycle = new HashMap<>();
    private final Map<Path, Signature> ignored = new HashMap<>();
    private int changesThisCycle;

    public UploadGate(Duration quietPeriod, Clock clock) {
        this.quietPeriod = quietPeriod;
        this.clock = clock;
    }

    /** Wywoływane przed każdym przebiegiem archiwizacji. */
    public synchronized void startCycle() {
        seenThisCycle = new HashMap<>();
        changesThisCycle = 0;
    }

    /** Ile plików w tym przebiegu było nowych albo zmieniło się od poprzedniego (np. trwa przesyłanie). */
    public synchronized int changesThisCycle() {
        return changesThisCycle;
    }

    /** Wywoływane po przebiegu: zapominamy pliki, których już nie ma (np. przeniesione). */
    public synchronized void endCycle() {
        observed = seenThisCycle;
        ignored.keySet().removeIf(path -> !Files.exists(path));
    }

    /** Pomija plik (np. zgłoszony duplikat), dopóki się nie zmieni. */
    public synchronized void ignore(Path file) {
        try {
            ignored.put(file, signature(file));
        } catch (IOException e) {
            ignored.remove(file);
        }
    }

    public synchronized void forget(Path file) {
        ignored.remove(file);
    }

    @Override
    public synchronized PhoneArchiver.FileState check(Path file) throws IOException {
        Signature signature = signature(file);
        Instant now = clock.instant();
        Observation previous = observed.get(file);
        Observation current = previous != null && previous.signature().equals(signature)
                ? previous : new Observation(signature, now);
        if (current != previous) {
            changesThisCycle++;
        }
        seenThisCycle.put(file, current);

        if (signature.equals(ignored.get(file))) {
            return PhoneArchiver.FileState.IGNORE;
        }
        ignored.remove(file);
        if (signature.size() == 0) {
            return PhoneArchiver.FileState.WAIT;
        }
        if (Duration.between(current.since(), now).compareTo(quietPeriod) < 0) {
            return PhoneArchiver.FileState.WAIT;
        }
        return inUse(file) ? PhoneArchiver.FileState.WAIT : PhoneArchiver.FileState.READY;
    }

    static Signature signature(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new Signature(attrs.size(), attrs.lastModifiedTime().toMillis());
    }

    /**
     * Czy inny program trzyma plik otwarty. Otwarcie do zapisu (bez zapisywania) nie zmienia pliku ani jego daty;
     * w Windows nie uda się, gdy plik jest właśnie zapisywany przez inny proces.
     */
    static boolean inUse(Path file) {
        if (Locations.isRemote(file)) {
            return false; // na serwerze nie da się sprawdzić blokady — wystarczy, że rozmiar i data się nie zmieniają
        }
        if (!Files.isWritable(file)) {
            return false; // plik tylko do odczytu — nie da się sprawdzić blokadą, rozmiar i data muszą wystarczyć
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException | AccessDeniedException e) {
            return true;
        } catch (FileSystemException e) {
            return true; // np. „plik jest używany przez inny proces”
        } catch (IOException e) {
            return true;
        }
    }
}
