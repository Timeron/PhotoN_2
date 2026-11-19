package com.photon.phonearchive;

import com.photon.thumbnails.ThumbnailMakerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.photon.phonearchive.MediaIntegrity.Result.COMPLETE;
import static com.photon.phonearchive.MediaIntegrity.Result.INCOMPLETE;
import static com.photon.phonearchive.MediaIntegrity.Result.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rozpoznawanie uciętych zdjęć i filmów (przerwane przesyłanie). */
class MediaIntegrityTest {

    @TempDir
    Path dir;

    @Test
    void jpeg() throws IOException {
        Path photo = dir.resolve("a.jpg");
        ThumbnailMakerTest.writePhoto(photo, 400, 300, true);
        byte[] bytes = Files.readAllBytes(photo);
        assertEquals(COMPLETE, MediaIntegrity.check(photo));
        assertEquals(INCOMPLETE, check("cut.jpg", Arrays.copyOf(bytes, bytes.length - 50)));
        assertEquals(INCOMPLETE, check("half.jpg", Arrays.copyOf(bytes, bytes.length / 2)));
        assertEquals(INCOMPLETE, check("header.jpg", Arrays.copyOf(bytes, 30)), "ucięty w nagłówku");
        // „Zdjęcie ruchome”: za końcem obrazu dopisany film — plik jest kompletny.
        byte[] motion = concat(bytes, mp4(4000));
        assertEquals(COMPLETE, check("motion.jpg", motion));
        assertEquals(UNKNOWN, check("tekst.jpg", "to nie jest JPEG".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void png() throws IOException {
        BufferedImage image = new BufferedImage(50, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        assertEquals(COMPLETE, check("a.png", bytes));
        assertEquals(INCOMPLETE, check("cut.png", Arrays.copyOf(bytes, bytes.length - 5)));
        assertEquals(UNKNOWN, check("tekst.png", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void mp4AndMov() throws IOException {
        byte[] video = mp4(10_000);
        assertEquals(COMPLETE, check("a.mp4", video));
        assertEquals(COMPLETE, check("a.mov", video));
        assertEquals(INCOMPLETE, check("cut.mp4", Arrays.copyOf(video, 6000)), "ucięty w środku danych");
        assertEquals(INCOMPLETE, check("cut2.mp4", Arrays.copyOf(video, video.length - 3)), "ucięty w bloku moov");
        // Nagranie bez bloku moov (przesyłanie przerwane po danych, przed indeksem).
        byte[] noMoov = concat(box("ftyp", 16), box("mdat", 2000));
        assertEquals(INCOMPLETE, check("nomoov.mp4", noMoov));
        assertEquals(UNKNOWN, check("tekst.mp4", "film".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void webpAndOtherFormats() throws IOException {
        byte[] webp = new byte[100];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        ByteBuffer.wrap(webp, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(92);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
        assertEquals(COMPLETE, check("a.webp", webp));
        assertEquals(INCOMPLETE, check("cut.webp", Arrays.copyOf(webp, 60)));
        assertEquals(UNKNOWN, check("a.cr2", new byte[]{1, 2, 3}), "RAW — tylko brak zmian decyduje");
        assertEquals(INCOMPLETE, check("pusty.jpg", new byte[0]));
    }

    /** Minimalny „film” MP4: ftyp + mdat + moov. */
    static byte[] mp4(int dataSize) {
        return concat(concat(box("ftyp", 16), box("mdat", dataSize)), box("moov", 200));
    }

    private static byte[] box(String type, int size) {
        byte[] box = new byte[size];
        ByteBuffer.wrap(box).putInt(size).put(type.getBytes(StandardCharsets.US_ASCII));
        return box;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private MediaIntegrity.Result check(String name, byte[] content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content);
        return MediaIntegrity.check(file);
    }
}
