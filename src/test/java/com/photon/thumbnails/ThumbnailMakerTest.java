package com.photon.thumbnails;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.icc.IccDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThumbnailMakerTest {

    private static final String DATE = "2023:07:15 12:34:56";

    @TempDir
    Path dir;

    @Test
    void resizesKeepingMetadataAndAddsOriginalHashToXmp() throws Exception {
        Path source = dir.resolve("zdjecia/IMG_0001.jpg");
        writePhoto(source, 3000, 2000, true);
        FileTime modified = FileTime.from(Instant.parse("2023-07-15T10:34:56Z"));
        Files.setLastModifiedTime(source, modified);
        Path target = dir.resolve("miniatury/IMG_0001.jpg");

        assertEquals(ThumbnailMaker.Result.RESIZED, ThumbnailMaker.make(source, target, 600));

        BufferedImage thumb = ImageIO.read(target.toFile());
        assertEquals(600, thumb.getWidth());
        assertEquals(400, thumb.getHeight());

        Metadata metadata = ImageMetadataReader.readMetadata(target.toFile());
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertEquals("PhotoN Test", ifd0.getString(ExifIFD0Directory.TAG_MAKE));
        assertEquals(6, ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION), "orientacja zachowana");
        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        assertEquals(DATE, exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
        assertEquals(600, exif.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH), "wymiary w EXIF poprawione");
        assertEquals(400, exif.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT));

        XmpDirectory xmp = metadata.getFirstDirectoryOfType(XmpDirectory.class);
        assertEquals(sha256(source), xmp.getXMPMeta().getPropertyString(XmpHash.NAMESPACE, XmpHash.PROPERTY));
        assertEquals("5", xmp.getXMPMeta().getPropertyString("http://ns.adobe.com/xap/1.0/", "Rating"),
                "dotychczasowe XMP zachowane");
        assertNotNull(metadata.getFirstDirectoryOfType(IccDirectory.class), "profil ICC zachowany");

        assertEquals(modified, Files.getLastModifiedTime(target), "data modyfikacji jak w oryginale");
        assertTrue(Files.size(target) < Files.size(source));
    }

    @Test
    void keepsColoursAndDetail() throws Exception {
        Path source = dir.resolve("kolory.jpg");
        writePhoto(source, 2400, 1600, false);
        Path target = dir.resolve("out/kolory.jpg");
        ThumbnailMaker.make(source, target, 1200);

        BufferedImage thumb = ImageIO.read(target.toFile());
        assertColor(new Color(200, 30, 40), new Color(thumb.getRGB(150, 400)));   // lewa połowa: czerwień
        assertColor(new Color(30, 60, 200), new Color(thumb.getRGB(1050, 400)));  // prawa połowa: niebieski
    }

    @Test
    void smallerPhotoIsNotReencoded() throws Exception {
        Path source = dir.resolve("male.jpg");
        writePhoto(source, 800, 600, true);
        Path target = dir.resolve("out/male.jpg");

        assertEquals(ThumbnailMaker.Result.KEPT_SIZE, ThumbnailMaker.make(source, target, 1920));

        byte[] originalImage = JpegSegments.parse(Files.readAllBytes(source)).imageData();
        byte[] copiedImage = JpegSegments.parse(Files.readAllBytes(target)).imageData();
        assertArrayEquals(originalImage, copiedImage, "dane obrazu bez ponownej kompresji");

        Metadata metadata = ImageMetadataReader.readMetadata(target.toFile());
        assertEquals(sha256(source), metadata.getFirstDirectoryOfType(XmpDirectory.class).getXMPMeta()
                .getPropertyString(XmpHash.NAMESPACE, XmpHash.PROPERTY));
        assertEquals(800, metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class)
                .getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH));
    }

    @Test
    void photoWithoutMetadataGetsNewXmpPacket() throws Exception {
        Path source = dir.resolve("bez-metadanych.jpg");
        BufferedImage image = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", source.toFile());
        Path target = dir.resolve("out/bez-metadanych.jpg");

        ThumbnailMaker.make(source, target, 200);

        Metadata metadata = ImageMetadataReader.readMetadata(target.toFile());
        assertEquals(sha256(source), metadata.getFirstDirectoryOfType(XmpDirectory.class).getXMPMeta()
                .getPropertyString(XmpHash.NAMESPACE, XmpHash.PROPERTY));
        assertEquals(200, ImageIO.read(target.toFile()).getWidth());
    }

    @Test
    void thumbnailOfThumbnailReplacesHashInsteadOfDuplicatingIt() {
        String once = XmpHash.withHash(XmpHash.newPacket("aaa"), "bbb");
        String twice = XmpHash.withHash(once, "ccc");
        assertEquals(1, count(twice, XmpHash.PREFIX + ":" + XmpHash.PROPERTY + "="));
        assertEquals(1, count(twice, "xmlns:" + XmpHash.PREFIX + "="));
        assertTrue(twice.contains("\"ccc\""));
    }

    @Test
    void grayscalePhotoStaysGrayscale() throws Exception {
        Path source = dir.resolve("szary.jpg");
        BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, 1600, 1200);
        g.dispose();
        ImageIO.write(image, "jpg", source.toFile());
        Path target = dir.resolve("out/szary.jpg");

        ThumbnailMaker.make(source, target, 400);
        BufferedImage thumb = ImageIO.read(target.toFile());
        assertEquals(1, thumb.getRaster().getNumBands());
        assertEquals(300, thumb.getHeight());
    }

    // =====================================================================
    // Pomocnicze: zdjęcie z EXIF, XMP i profilem ICC
    // =====================================================================

    public static void writePhoto(Path file, int width, int height, boolean withMetadata) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 30, 40));
        g.fillRect(0, 0, width / 2, height);
        g.setColor(new Color(30, 60, 200));
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", bytes);

        JpegSegments.Parsed parsed = JpegSegments.parse(bytes.toByteArray());
        List<JpegSegments.Segment> segments = new ArrayList<>();
        if (withMetadata) {
            segments.add(new JpegSegments.Segment(JpegSegments.APP1, concat(JpegSegments.EXIF_HEADER,
                    exif(width, height))));
            segments.add(JpegSegments.xmpSegment("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                    + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                    + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmp:Rating=\"5\"/>"
                    + "</rdf:RDF></x:xmpmeta>" + " ".repeat(2000) + "<?xpacket end=\"w\"?>"));
            byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
            segments.add(new JpegSegments.Segment(JpegSegments.APP2,
                    concat("ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1), new byte[]{1, 1}, icc)));
        }
        // Tablice kwantyzacji, nagłówek klatki itd. z enkodera (bez JFIF).
        parsed.segments().stream().filter(s -> !s.isApp()).forEach(segments::add);
        Files.createDirectories(file.getParent());
        Files.write(file, JpegSegments.write(segments, parsed.imageData()));
    }

    /** Minimalny EXIF (big-endian): Make, Orientation=6, DateTimeOriginal, PixelX/YDimension. */
    private static byte[] exif(int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(120);
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        // IFD0 @8: 3 wpisy → koniec @50, dane Make @50
        b.putShort((short) 3);
        b.putShort((short) 0x010F).putShort((short) 2).putInt(12).putInt(50);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        b.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(62);
        b.putInt(0);
        b.put("PhotoN Test\0".getBytes(StandardCharsets.US_ASCII)); // 12 B → @62
        // Exif IFD @62: 3 wpisy → koniec @104, data @104
        b.position(62);
        b.putShort((short) 3);
        b.putShort((short) 0x9003).putShort((short) 2).putInt(20).putInt(104);
        b.putShort((short) 0xA002).putShort((short) 4).putInt(1).putInt(width);
        b.putShort((short) 0xA003).putShort((short) 4).putInt(1).putInt(height);
        b.putInt(0);
        byte[] date = (DATE + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] result = Arrays.copyOf(b.array(), 104 + date.length);
        System.arraycopy(date, 0, result, 104, date.length);
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) {
            length += p.length;
        }
        byte[] out = new byte[length];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    public static String sha256(Path file) throws IOException {
        return ThumbnailMaker.sha256(Files.readAllBytes(file));
    }

    private static void assertColor(Color expected, Color actual) {
        int diff = Math.abs(expected.getRed() - actual.getRed()) + Math.abs(expected.getGreen() - actual.getGreen())
                + Math.abs(expected.getBlue() - actual.getBlue());
        assertTrue(diff <= 12, "Oczekiwano " + expected + ", jest " + actual);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
