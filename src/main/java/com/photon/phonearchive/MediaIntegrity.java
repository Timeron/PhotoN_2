package com.photon.phonearchive;

import com.photon.duplicates.scan.ScannedFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.io.EOFException;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Czy zdjęcie lub film jest kompletny — na podstawie budowy pliku, a nie tylko tego, że przestał rosnąć.
 * Chroni przed archiwizacją pliku, którego przesyłanie (np. z telefonu przez wolny internet) zostało
 * przerwane albo wstrzymane na dłużej.
 * <ul>
 *   <li>JPEG — po początku danych obrazu (SOS) musi wystąpić znacznik końca obrazu (EOI); dane dopisane
 *       za nim (np. film w „zdjęciach ruchomych”) są dozwolone,</li>
 *   <li>PNG — kończy się blokiem IEND; GIF — bajtem końca (0x3B),</li>
 *   <li>WebP, AVI — nagłówek RIFF podaje długość pliku,</li>
 *   <li>MP4, MOV, 3GP, HEIC, HEIF, AVIF — bloki najwyższego poziomu muszą dokładnie wypełniać plik, a film
 *       musi mieć blok {@code moov} (zdjęcie HEIC — {@code meta}).</li>
 * </ul>
 * Pozostałe formaty (RAW, MKV…) zwracają {@link Result#UNKNOWN} — wtedy decyduje sam brak zmian pliku.
 */
public final class MediaIntegrity {

    public enum Result {
        COMPLETE,
        INCOMPLETE,
        /** Formatu nie da się tak sprawdzić (albo budowa jest nietypowa) — decyduje brak zmian pliku. */
        UNKNOWN
    }

    private static final Set<String> BMFF_VIDEO = Set.of("mp4", "m4v", "mov", "3gp", "3g2");
    private static final Set<String> BMFF_IMAGE = Set.of("heic", "heif", "avif");

    private MediaIntegrity() {
    }

    public static Result check(Path file) {
        String ext = ScannedFile.extensionOf(file.getFileName().toString()).toLowerCase(Locale.ROOT);
        try {
            long size = Files.size(file);
            if (size == 0) {
                return Result.INCOMPLETE;
            }
            return switch (ext) {
                case "jpg", "jpeg", "jpe" -> jpeg(file, size);
                case "png" -> startsWith(file, size, new byte[]{(byte) 0x89, 'P', 'N', 'G'})
                        ? endsWith(file, size, new byte[]{0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82})
                        : Result.UNKNOWN;
                case "gif" -> startsWith(file, size, new byte[]{'G', 'I', 'F', '8'})
                        ? endsWith(file, size, new byte[]{0x3B}) : Result.UNKNOWN;
                case "webp", "avi" -> riff(file, size);
                default -> BMFF_VIDEO.contains(ext) ? bmff(file, size, "moov")
                        : BMFF_IMAGE.contains(ext) ? bmff(file, size, "meta") : Result.UNKNOWN;
            };
        } catch (IOException | RuntimeException e) {
            return Result.UNKNOWN; // nie da się przeczytać — o pliku zdecyduje dalsza obsługa (np. błąd odczytu)
        }
    }


    // =====================================================================
    // JPEG
    // =====================================================================

    /** Wynik przejścia przez nagłówek JPEG (segmenty od SOI do SOS). */
    private enum JpegHeader { SCAN_FOLLOWS, TRUNCATED, NOT_JPEG }

    private static Result jpeg(Path file, long size) throws IOException {
        if (!startsWith(file, size, new byte[]{(byte) 0xFF, (byte) 0xD8})) {
            return Result.UNKNOWN; // to nie jest JPEG (np. inny format z rozszerzeniem .jpg)
        }
        // Najczęściej plik kończy się znacznikiem EOI — wtedy wystarczy sprawdzić, że nagłówek jest cały.
        boolean endsWithEoi = endsWith(file, size, new byte[]{(byte) 0xFF, (byte) 0xD9}) == Result.COMPLETE;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            switch (readHeader(in)) {
                case TRUNCATED:
                    return Result.INCOMPLETE;
                case NOT_JPEG:
                    return Result.UNKNOWN;
                default:
                    break;
            }
            if (endsWithEoi) {
                return Result.COMPLETE;
            }
            // W danych obrazu bajt 0xFF jest zawsze „zabezpieczony” (FF 00) albo jest znacznikiem restartu,
            // więc FF D9 oznacza koniec obrazu (za nim mogą być dopisane dane, np. film „zdjęcia ruchomego”).
            int previous = -1;
            int b;
            while ((b = in.read()) >= 0) {
                if (previous == 0xFF && b == 0xD9) {
                    return Result.COMPLETE;
                }
                previous = b;
            }
            return Result.INCOMPLETE;
        }
    }

    /** Przechodzi segmenty nagłówka (każdy ma długość) aż do początku danych obrazu (SOS). */
    private static JpegHeader readHeader(InputStream in) throws IOException {
        try {
            if (in.read() != 0xFF || in.read() != 0xD8) {
                return JpegHeader.NOT_JPEG;
            }
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return JpegHeader.TRUNCATED;
                }
                if (b != 0xFF) {
                    return JpegHeader.NOT_JPEG;
                }
                int marker = in.read();
                while (marker == 0xFF) {
                    marker = in.read();
                }
                if (marker < 0) {
                    return JpegHeader.TRUNCATED;
                }
                if (marker == 0xD9) {
                    return JpegHeader.NOT_JPEG; // EOI bez obrazu — nietypowe
                }
                if (marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) {
                    continue; // znaczniki bez długości
                }
                int hi = in.read();
                int lo = in.read();
                if (lo < 0) {
                    return JpegHeader.TRUNCATED;
                }
                long length = ((hi << 8) | lo) - 2L;
                if (length < 0) {
                    return JpegHeader.NOT_JPEG;
                }
                in.skipNBytes(length); // za krótki plik → EOFException
                if (marker == 0xDA) {
                    return JpegHeader.SCAN_FOLLOWS;
                }
            }
        } catch (EOFException e) {
            return JpegHeader.TRUNCATED;
        }
    }

    // =====================================================================
    // Pozostałe formaty
    // =====================================================================

    private static Result endsWith(Path file, long size, byte[] tail) throws IOException {
        if (size < tail.length) {
            return Result.INCOMPLETE;
        }
        byte[] actual = readAt(file, size - tail.length, tail.length);
        return Arrays.equals(actual, tail) ? Result.COMPLETE : Result.INCOMPLETE;
    }

    private static Result riff(Path file, long size) throws IOException {
        if (size < 12) {
            return Result.UNKNOWN; // za mało, by rozpoznać format (prawdziwy plik tak krótki nie jest)
        }
        byte[] header = readAt(file, 0, 12);
        if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
            return Result.UNKNOWN;
        }
        long declared = Integer.toUnsignedLong(ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()) + 8;
        if (declared > size) {
            return Result.INCOMPLETE;
        }
        return Result.COMPLETE; // dłuższy plik (np. dopisane dane) też jest kompletny
    }

    /** ISO BMFF (MP4/MOV/HEIC): bloki najwyższego poziomu dokładnie wypełniają plik i jest wymagany blok. */
    private static Result bmff(Path file, long size, String required) throws IOException {
        Set<String> boxes = new HashSet<>();
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long position = 0;
            ByteBuffer header = ByteBuffer.allocate(16);
            while (position < size) {
                if (size - position < 8) {
                    return boxes.isEmpty() ? Result.UNKNOWN : Result.INCOMPLETE;
                }
                header.clear().limit(8);
                channel.position(position);
                readFully(channel, header);
                long boxSize = Integer.toUnsignedLong(header.getInt(0));
                String type = new String(header.array(), 4, 4, StandardCharsets.ISO_8859_1);
                if (boxes.isEmpty() && !"ftyp".equals(type) && !"wide".equals(type) && !"free".equals(type)
                        && !"mdat".equals(type) && !"moov".equals(type) && !"skip".equals(type)) {
                    return Result.UNKNOWN; // nie wygląda na MP4/MOV/HEIC
                }
                if (boxSize == 1) {
                    if (size - position < 16) {
                        return Result.INCOMPLETE;
                    }
                    header.clear().limit(8);
                    readFully(channel, header);
                    boxSize = header.getLong(0);
                } else if (boxSize == 0) {
                    return Result.UNKNOWN; // blok „do końca pliku” — długości nie da się sprawdzić
                }
                if (boxSize < 8) {
                    return Result.UNKNOWN;
                }
                boxes.add(type);
                position += boxSize;
            }
            if (position > size) {
                return Result.INCOMPLETE; // ostatni blok wychodzi poza koniec pliku — ucięty
            }
        }
        return boxes.contains(required) ? Result.COMPLETE : Result.INCOMPLETE;
    }

    private static boolean startsWith(Path file, long size, byte[] head) throws IOException {
        return size >= head.length && Arrays.equals(readAt(file, 0, head.length), head);
    }

    private static byte[] readAt(Path file, long offset, int length) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            readFully(channel, buffer);
            return buffer.array();
        }
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new EOFException();
            }
        }
    }
}
