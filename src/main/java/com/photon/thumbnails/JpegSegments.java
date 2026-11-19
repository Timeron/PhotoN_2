package com.photon.thumbnails;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Niskopoziomowa obsługa pliku JPEG jako listy segmentów (markerów).
 * Pozwala przenieść metadane (EXIF, XMP, ICC, IPTC, komentarze) z oryginału do miniatury
 * bez dekodowania i ponownego kodowania obrazu.
 */
final class JpegSegments {

    static final int SOI = 0xD8;
    static final int EOI = 0xD9;
    static final int SOS = 0xDA;
    static final int APP0 = 0xE0;
    static final int APP1 = 0xE1;
    static final int APP2 = 0xE2;
    static final int APP14 = 0xEE;
    static final int APP15 = 0xEF;
    static final int COM = 0xFE;

    static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] MPF_HEADER = "MPF\0".getBytes(StandardCharsets.ISO_8859_1);
    /** Maksymalna długość danych segmentu (bez markera i pola długości). */
    static final int MAX_PAYLOAD = 65533;

    /** Segment: marker i dane (bez 0xFF, markera i 2 bajtów długości). */
    record Segment(int marker, byte[] payload) {

        boolean isApp() {
            return marker >= APP0 && marker <= APP15;
        }

        boolean startsWith(byte[] header) {
            return payload.length >= header.length
                    && Arrays.equals(payload, 0, header.length, header, 0, header.length);
        }

        boolean isExif() {
            return marker == APP1 && startsWith(EXIF_HEADER);
        }

        boolean isXmp() {
            return marker == APP1 && startsWith(XMP_HEADER);
        }

        /** Multi-Picture Format: przesunięcia wskazują na obrazy za EOI oryginału — nie da się go przenieść. */
        boolean isMpf() {
            return marker == APP2 && startsWith(MPF_HEADER);
        }

        /** Metadane, które przenosimy z oryginału do miniatury. */
        boolean isTransferableMetadata() {
            if (marker == COM) {
                return true;
            }
            // APP14 (Adobe) opisuje transformację kolorów zakodowanego obrazu — zostaje z enkodera.
            return isApp() && marker != APP14 && !isMpf();
        }
    }

    /** Plik rozbity na segmenty nagłówka i resztę (od SOS do końca) w postaci surowej. */
    record Parsed(List<Segment> segments, byte[] imageData) {

        Segment find(Predicate<Segment> test) {
            return segments.stream().filter(test).findFirst().orElse(null);
        }
    }

    private JpegSegments() {
    }

    static boolean isJpeg(byte[] data) {
        return data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == SOI;
    }

    static Parsed parse(byte[] data) throws IOException {
        if (!isJpeg(data)) {
            throw new IOException("To nie jest plik JPEG");
        }
        List<Segment> segments = new ArrayList<>();
        int pos = 2;
        while (pos < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                throw new IOException("Uszkodzona struktura JPEG (pozycja " + pos + ")");
            }
            int marker = data[pos + 1] & 0xFF;
            if (marker == 0xFF) { // bajty wypełnienia
                pos++;
                continue;
            }
            if (marker == SOS || marker == EOI) {
                return new Parsed(segments, Arrays.copyOfRange(data, pos, data.length));
            }
            if (marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) { // markery bez długości
                pos += 2;
                continue;
            }
            if (pos + 4 > data.length) {
                throw new IOException("Nieoczekiwany koniec pliku JPEG");
            }
            int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            if (length < 2 || pos + 2 + length > data.length) {
                throw new IOException("Nieprawidłowa długość segmentu JPEG");
            }
            segments.add(new Segment(marker, Arrays.copyOfRange(data, pos + 4, pos + 2 + length)));
            pos += 2 + length;
        }
        throw new IOException("Brak danych obrazu w pliku JPEG");
    }

    static byte[] write(List<Segment> segments, byte[] imageData) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(imageData.length + 64 * 1024);
        out.write(0xFF);
        out.write(SOI);
        for (Segment segment : segments) {
            if (segment.payload().length > MAX_PAYLOAD) {
                throw new IOException("Segment metadanych jest za duży (" + segment.payload().length + " B)");
            }
            int length = segment.payload().length + 2;
            out.write(0xFF);
            out.write(segment.marker());
            out.write(length >> 8);
            out.write(length & 0xFF);
            out.write(segment.payload());
        }
        out.write(imageData);
        return out.toByteArray();
    }

    /**
     * Składa miniaturę: metadane oryginału (w oryginalnej kolejności, z nowym XMP)
     * + tablice i dane obrazu z pliku zakodowanego przez enkoder.
     *
     * @param original  segmenty oryginału
     * @param encoded   segmenty nowo zakodowanej miniatury
     * @param xmp       nowy segment XMP (zastępuje stary albo trafia za EXIF)
     * @param exif      segment EXIF do użycia zamiast oryginalnego (np. z poprawionymi wymiarami), może być null
     */
    static List<Segment> merge(List<Segment> original, List<Segment> encoded, Segment xmp, Segment exif) {
        List<Segment> result = new ArrayList<>();
        boolean xmpPlaced = false;
        for (Segment segment : original) {
            if (!segment.isTransferableMetadata()) {
                continue;
            }
            if (segment.isXmp()) {
                if (!xmpPlaced) {
                    result.add(xmp);
                    xmpPlaced = true;
                }
                continue;
            }
            result.add(segment.isExif() && exif != null ? exif : segment);
        }
        if (!xmpPlaced) {
            result.add(xmpInsertIndex(result), xmp);
        }
        for (Segment segment : encoded) {
            // Z enkodera bierzemy wszystko poza jego własnymi metadanymi (JFIF, komentarze).
            if (segment.marker() == APP14 || !segment.isApp() && segment.marker() != COM) {
                result.add(segment);
            }
        }
        return result;
    }

    /** XMP wstawiamy zaraz za EXIF (a jeśli go nie ma — za JFIF albo na początku). */
    private static int xmpInsertIndex(List<Segment> segments) {
        int index = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.isExif() || s.marker() == APP0) {
                index = i + 1;
            }
        }
        return index;
    }

    static Segment xmpSegment(String packet) {
        byte[] body = packet.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[XMP_HEADER.length + body.length];
        System.arraycopy(XMP_HEADER, 0, payload, 0, XMP_HEADER.length);
        System.arraycopy(body, 0, payload, XMP_HEADER.length, body.length);
        return new Segment(APP1, payload);
    }

    static String xmpPacket(Segment segment) {
        byte[] p = segment.payload();
        return new String(p, XMP_HEADER.length, p.length - XMP_HEADER.length, StandardCharsets.UTF_8);
    }
}
