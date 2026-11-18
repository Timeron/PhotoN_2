package com.photon.phonearchive;

import com.photon.thumbnails.ThumbnailOptions;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Konfiguracja archiwizacji telefonu (zapamiętywana między uruchomieniami i w profilach).
 *
 * @param source            folder archiwizowany (np. kopia DCIM z telefonu)
 * @param target            folder archiwum z podfolderami {@code RRRR-MM-DD}
 * @param includeSubfolders czy przeglądać podfoldery folderu źródłowego
 * @param makeThumbnails    czy dla zarchiwizowanych zdjęć tworzyć miniatury
 * @param thumbnailSide     dłuższy bok miniatury w pikselach
 * @param thumbnailTarget   folder na miniatury (w nim powstają podfoldery {@code RRRR-MM-DD} jak w archiwum)
 * @param duplicateAction   co zrobić z plikami, które już są w archiwum
 * @param duplicatesTarget  folder na duplikaty (tylko {@link PhoneDuplicateAction#MOVE_TO_FOLDER})
 */
public record PhoneArchiveOptions(Path source, Path target, boolean includeSubfolders,
                                  boolean makeThumbnails, int thumbnailSide, Path thumbnailTarget,
                                  PhoneDuplicateAction duplicateAction, Path duplicatesTarget) {

    public PhoneArchiveOptions {
        // Profile zapisane przed dodaniem miniatur / akcji na duplikatach nie mają tych pól —
        // zachowują się jak wtedy (duplikaty usuwane po potwierdzeniu). Nowe ustawienia: patrz defaults().
        if (thumbnailSide <= 0) {
            thumbnailSide = ThumbnailOptions.DEFAULT_SIDE;
        }
        if (duplicateAction == null) {
            duplicateAction = PhoneDuplicateAction.REMOVE;
        }
    }

    public PhoneArchiveOptions(Path source, Path target, boolean includeSubfolders,
                               boolean makeThumbnails, int thumbnailSide, Path thumbnailTarget) {
        this(source, target, includeSubfolders, makeThumbnails, thumbnailSide, thumbnailTarget,
                PhoneDuplicateAction.REMOVE, null);
    }

    public PhoneArchiveOptions(Path source, Path target, boolean includeSubfolders) {
        this(source, target, includeSubfolders, false, ThumbnailOptions.DEFAULT_SIDE, null);
    }

    public static PhoneArchiveOptions defaults() {
        return new PhoneArchiveOptions(null, null, true, false, ThumbnailOptions.DEFAULT_SIDE, null,
                PhoneDuplicateAction.MOVE_TO_FOLDER, null);
    }

    public boolean movesDuplicates() {
        return duplicateAction == PhoneDuplicateAction.MOVE_TO_FOLDER;
    }

    /** Komunikat o błędzie konfiguracji albo {@code null}, gdy można zaczynać. */
    public String validate() {
        if (source == null) {
            return "Wybierz folder do archiwizacji.";
        }
        if (!Files.isDirectory(source)) {
            return "Folder do archiwizacji nie istnieje: " + source;
        }
        if (target == null) {
            return "Wybierz folder archiwum.";
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            return "Ścieżka archiwum wskazuje plik, a nie folder: " + target;
        }
        Path s = source.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        if (s.equals(t)) {
            return "Folder archiwum musi być inny niż folder archiwizowany.";
        }
        if (s.startsWith(t)) {
            return "Folder archiwizowany nie może leżeć wewnątrz folderu archiwum.";
        }
        if (makeThumbnails) {
            if (thumbnailTarget == null) {
                return "Wybierz folder na miniatury.";
            }
            if (thumbnailSide < ThumbnailOptions.MIN_SIDE || thumbnailSide > ThumbnailOptions.MAX_SIDE) {
                return "Długość boku miniatury musi mieścić się w zakresie " + ThumbnailOptions.MIN_SIDE + "–"
                        + ThumbnailOptions.MAX_SIDE + " px.";
            }
            Path m = thumbnailTarget.toAbsolutePath().normalize();
            if (Files.exists(m) && !Files.isDirectory(m)) {
                return "Ścieżka miniatur wskazuje plik, a nie folder: " + m;
            }
            if (m.equals(t)) {
                return "Folder na miniatury musi być inny niż folder archiwum — miniatury mają te same nazwy "
                        + "i zastąpiłyby zarchiwizowane oryginały.";
            }
            if (m.startsWith(s)) {
                return "Folder na miniatury nie może leżeć w folderze archiwizowanym — przy następnej "
                        + "archiwizacji miniatury trafiłyby do archiwum.";
            }
            if (m.startsWith(t) && PhoneArchiver.isDateFolderName(t.relativize(m).getName(0).toString())) {
                return "Folder na miniatury nie może leżeć w folderze archiwum z datą ("
                        + t.relativize(m).getName(0) + ") — miniatury byłyby brane za zarchiwizowane zdjęcia.";
            }
        }
        if (movesDuplicates()) {
            if (duplicatesTarget == null) {
                return "Wybierz folder zastępczy (śmietnik) na duplikaty.";
            }
            Path d = duplicatesTarget.toAbsolutePath().normalize();
            if (Files.exists(d) && !Files.isDirectory(d)) {
                return "Ścieżka folderu na duplikaty wskazuje plik, a nie folder: " + d;
            }
            if (d.startsWith(s)) {
                return "Folder na duplikaty nie może leżeć w folderze archiwizowanym — przy następnej "
                        + "archiwizacji duplikaty byłyby sprawdzane ponownie.";
            }
            if (d.equals(t) || d.startsWith(t) && PhoneArchiver.isDateFolderName(t.relativize(d).getName(0).toString())) {
                return "Folder na duplikaty nie może być folderem archiwum ani leżeć w jego folderze z datą.";
            }
        }
        return null;
    }
}
