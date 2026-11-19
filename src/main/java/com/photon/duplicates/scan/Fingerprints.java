package com.photon.duplicates.scan;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.png.PngDirectory;

import com.photon.remote.Locations;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * „Odciski” plików używane do rozpoznawania duplikatów.
 */
public final class Fingerprints {

    /** Rozszerzenia obrazów, które potrafi zdekodować Java (ImageIO) — dla porównania percepcyjnego. */
    public static final Set<String> DECODABLE_IMAGES = Arrays.stream(ImageIO.getReaderFileSuffixes())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** Bok obrazu po zmniejszeniu przy dekodowaniu — wystarczy do hasha 9×8, a oszczędza pamięć. */
    private static final int DECODE_TARGET = 256;

    private Fingerprints() {
    }

    // =====================================================================
    // SHA-256
    // =====================================================================

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[256 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // =====================================================================
    // Hash percepcyjny (dHash 64-bitowy)
    // =====================================================================

    /**
     * Hash różnicowy obrazu: obraz zmniejszony do 9×8 w skali szarości, każdy bit mówi,
     * czy piksel jest ciemniejszy od sąsiada po prawej. Odporny na skalowanie i kompresję.
     *
     * @throws IOException gdy formatu nie da się odczytać
     */
    public static long imageHash(Path file) throws IOException {
        BufferedImage image = readScaledDown(file);
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 9 || h < 8) {
            throw new IOException("Obraz jest zbyt mały do porównania (" + w + "×" + h + ")");
        }
        double[][] cells = new double[8][9];
        for (int cy = 0; cy < 8; cy++) {
            int y0 = cy * h / 8;
            int y1 = Math.max(y0 + 1, (cy + 1) * h / 8);
            for (int cx = 0; cx < 9; cx++) {
                int x0 = cx * w / 9;
                int x1 = Math.max(x0 + 1, (cx + 1) * w / 9);
                double sum = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int rgb = image.getRGB(x, y);
                        sum += 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                    }
                }
                cells[cy][cx] = sum / ((long) (y1 - y0) * (x1 - x0));
            }
        }
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (cells[y][x] < cells[y][x + 1]) {
                    hash |= 1;
                }
            }
        }
        return hash;
    }

    /** Podobieństwo dwóch hashy obrazu w procentach (100 = identyczne). */
    public static int similarity(long a, long b) {
        return (int) Math.round(100.0 * (64 - Long.bitCount(a ^ b)) / 64);
    }

    private static BufferedImage readScaledDown(Path file) throws IOException {
        // Plik z serwera zdalnego czytamy strumieniem (nie ma go na dysku jako java.io.File).
        try (InputStream source = Locations.isRemote(file) ? new BufferedInputStream(Files.newInputStream(file)) : null;
             ImageInputStream in = ImageIO.createImageInputStream(source != null ? source : file.toFile())) {
            if (in == null) {
                throw new IOException("Nie można otworzyć pliku");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Nieobsługiwany format obrazu");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int step = Math.max(1, Math.min(width, height) / DECODE_TARGET);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IOException("Nie udało się zdekodować obrazu");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    // =====================================================================
    // Metadane
    // =====================================================================

    /**
     * Klucz z metadanych: data i godzina wykonania + aparat + rozdzielczość.
     *
     * @return klucz albo {@code null}, gdy plik nie ma daty wykonania w metadanych
     */
    public static String metadataKey(Path file) throws IOException {
        Metadata metadata;
        try {
            if (Locations.isRemote(file)) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    metadata = ImageMetadataReader.readMetadata(in, Files.size(file));
                }
            } else {
                metadata = ImageMetadataReader.readMetadata(file.toFile());
            }
        } catch (com.drew.imaging.ImageProcessingException e) {
            return null;
        }

        String date = null;
        String camera = "";
        String size = "";

        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif != null) {
            date = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            String subSec = exif.getString(ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL);
            if (date != null && subSec != null) {
                date += "." + subSec.trim();
            }
            size = dimensions(exif, ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH, ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
        }
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 != null) {
            camera = (nullToEmpty(ifd0.getString(ExifIFD0Directory.TAG_MAKE)) + " "
                    + nullToEmpty(ifd0.getString(ExifIFD0Directory.TAG_MODEL))).trim();
            if (date == null) {
                date = ifd0.getString(ExifIFD0Directory.TAG_DATETIME);
            }
        }
        if (size.isEmpty()) {
            size = firstDimensions(metadata);
        }

        // Filmy: data utworzenia z kontenera QuickTime / MP4.
        if (date == null) {
            QuickTimeDirectory mov = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
            if (mov != null) {
                date = mov.getString(QuickTimeDirectory.TAG_CREATION_TIME);
            }
        }
        if (date == null) {
            Mp4Directory mp4 = metadata.getFirstDirectoryOfType(Mp4Directory.class);
            if (mp4 != null) {
                date = mp4.getString(Mp4Directory.TAG_CREATION_TIME);
            }
        }

        if (date == null || date.isBlank() || date.startsWith("0000")) {
            return null;
        }
        return date.trim() + "|" + camera + "|" + size;
    }

    private static String firstDimensions(Metadata metadata) {
        JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpeg != null) {
            String s = dimensions(jpeg, JpegDirectory.TAG_IMAGE_WIDTH, JpegDirectory.TAG_IMAGE_HEIGHT);
            if (!s.isEmpty()) {
                return s;
            }
        }
        PngDirectory png = metadata.getFirstDirectoryOfType(PngDirectory.class);
        if (png != null) {
            String s = dimensions(png, PngDirectory.TAG_IMAGE_WIDTH, PngDirectory.TAG_IMAGE_HEIGHT);
            if (!s.isEmpty()) {
                return s;
            }
        }
        QuickTimeVideoDirectory movVideo = metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class);
        if (movVideo != null) {
            String s = dimensions(movVideo, QuickTimeVideoDirectory.TAG_WIDTH, QuickTimeVideoDirectory.TAG_HEIGHT);
            if (!s.isEmpty()) {
                return s;
            }
        }
        Mp4VideoDirectory mp4Video = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
        if (mp4Video != null) {
            return dimensions(mp4Video, Mp4VideoDirectory.TAG_WIDTH, Mp4VideoDirectory.TAG_HEIGHT);
        }
        return "";
    }

    private static String dimensions(Directory dir, int widthTag, int heightTag) {
        Integer w = dir.getInteger(widthTag);
        Integer h = dir.getInteger(heightTag);
        return w == null || h == null ? "" : w + "x" + h;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
