package com.photon.duplicates;

import java.util.List;
import java.util.Optional;

/**
 * Obsługiwane formaty zdjęć i filmów wraz z rozszerzeniami plików.
 */
public enum MediaFormat {

    // --- Zdjęcia ---
    JPEG(Kind.PHOTO, "JPG / JPEG", true, "jpg", "jpeg", "jpe", "jfif"),
    PNG(Kind.PHOTO, "PNG", true, "png"),
    HEIC(Kind.PHOTO, "HEIC / HEIF", true, "heic", "heif"),
    WEBP(Kind.PHOTO, "WEBP", true, "webp"),
    GIF(Kind.PHOTO, "GIF", false, "gif"),
    BMP(Kind.PHOTO, "BMP", false, "bmp"),
    TIFF(Kind.PHOTO, "TIFF", true, "tif", "tiff"),
    RAW(Kind.PHOTO, "RAW", true, "cr2", "cr3", "nef", "nrw", "arw", "dng", "orf", "rw2", "raf", "srw", "pef"),

    // --- Filmy ---
    MP4(Kind.VIDEO, "MP4", true, "mp4"),
    MOV(Kind.VIDEO, "MOV", true, "mov"),
    M4V(Kind.VIDEO, "M4V", true, "m4v"),
    AVI(Kind.VIDEO, "AVI", true, "avi"),
    MKV(Kind.VIDEO, "MKV", true, "mkv"),
    MTS(Kind.VIDEO, "MTS / M2TS", true, "mts", "m2ts"),
    THREE_GP(Kind.VIDEO, "3GP", false, "3gp", "3g2"),
    WMV(Kind.VIDEO, "WMV", false, "wmv"),
    MPEG(Kind.VIDEO, "MPG / MPEG", false, "mpg", "mpeg"),
    WEBM(Kind.VIDEO, "WEBM", false, "webm");

    public enum Kind { PHOTO, VIDEO }

    private final Kind kind;
    private final String label;
    private final boolean selectedByDefault;
    private final List<String> extensions;

    MediaFormat(Kind kind, String label, boolean selectedByDefault, String... extensions) {
        this.kind = kind;
        this.label = label;
        this.selectedByDefault = selectedByDefault;
        this.extensions = List.of(extensions);
    }

    public Kind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public boolean isSelectedByDefault() {
        return selectedByDefault;
    }

    public List<String> getExtensions() {
        return extensions;
    }

    /** Format dla rozszerzenia (małe litery, bez kropki). */
    public static Optional<MediaFormat> forExtension(String extension) {
        for (MediaFormat format : values()) {
            if (format.extensions.contains(extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
