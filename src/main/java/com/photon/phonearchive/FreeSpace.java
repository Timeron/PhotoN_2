package com.photon.phonearchive;

import com.photon.remote.Locations;
import com.photon.ui.Texts;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Kontrola wolnego miejsca na dysku archiwum lub miniatur (tylko lokalnym — serwer nie podaje wolnego miejsca).
 * Gdy po zapisaniu pliku zostałoby mniej niż zapas, archiwizacja zatrzymuje się, zamiast zbierać błędy plik po pliku.
 */
final class FreeSpace {

    private final Path folder;
    private final String what;
    private final long minFree;
    private FileStore store;
    private boolean unknown;

    /**
     * @param what    nazwa dysku w komunikacie, np. „archiwum”
     * @param minFree ile miejsca musi zostać wolne
     */
    FreeSpace(Path folder, String what, long minFree) {
        this.folder = folder;
        this.what = what;
        this.minFree = minFree;
    }

    /**
     * @param bytes ile miejsca zajmie zapisywany plik
     * @throws PhoneArchiver.InsufficientSpaceException gdy miejsca jest za mało
     */
    void require(long bytes) {
        if (unknown || Locations.isRemote(folder)) {
            return;
        }
        try {
            if (store == null) {
                Path existing = SafeTransfer.existingAncestor(folder);
                if (existing == null) {
                    unknown = true;
                    return;
                }
                store = Files.getFileStore(existing);
            }
            long free = store.getUsableSpace();
            if (free - bytes < minFree) {
                throw new PhoneArchiver.InsufficientSpaceException("Za mało miejsca na dysku " + what + " ("
                        + folder.getRoot() + "): wolne " + Texts.formatBytes(free) + ", potrzeba "
                        + Texts.formatBytes(bytes) + " i zapasu " + Texts.formatBytes(minFree) + ". Zwolnij miejsce — "
                        + "archiwizacja wznowi się sama, pliki zostały w folderze archiwizowanym.");
            }
        } catch (IOException e) {
            unknown = true; // nie da się sprawdzić — nie blokujemy
        }
    }
}
