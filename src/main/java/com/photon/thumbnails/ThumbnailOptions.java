package com.photon.thumbnails;

import java.nio.file.Path;

/**
 * Konfiguracja tworzenia miniatur (zapamiętywana między uruchomieniami).
 *
 * @param source            folder ze zdjęciami
 * @param target            folder na miniatury
 * @param maxSide           długość dłuższego boku miniatury w pikselach
 * @param includeSubfolders czy przetwarzać podfoldery (struktura folderów jest odtwarzana w folderze docelowym)
 * @param overwrite         czy zastępować istniejące miniatury
 */
public record ThumbnailOptions(Path source, Path target, int maxSide, boolean includeSubfolders, boolean overwrite) {

    public static final int MIN_SIDE = 16;
    public static final int MAX_SIDE = 20_000;
    public static final int DEFAULT_SIDE = 800;

    public static ThumbnailOptions defaults() {
        return new ThumbnailOptions(null, null, DEFAULT_SIDE, true, false);
    }

    /** Komunikat o błędzie konfiguracji albo {@code null}, gdy można zaczynać. */
    public String validate() {
        if (source == null) {
            return "Wybierz folder ze zdjęciami.";
        }
        if (!java.nio.file.Files.isDirectory(source)) {
            return "Folder ze zdjęciami nie istnieje: " + source;
        }
        if (target == null) {
            return "Wybierz folder na miniatury.";
        }
        if (maxSide < MIN_SIDE || maxSide > MAX_SIDE) {
            return "Długość boku musi mieścić się w zakresie " + MIN_SIDE + "–" + MAX_SIDE + " px.";
        }
        Path s = source.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        if (s.equals(t)) {
            return "Folder na miniatury musi być inny niż folder ze zdjęciami — miniatury mają te same nazwy "
                    + "i zastąpiłyby oryginały.";
        }
        return null;
    }
}
