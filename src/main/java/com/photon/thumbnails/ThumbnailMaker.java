package com.photon.thumbnails;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;

/**
 * Tworzy miniaturę jednego zdjęcia JPEG:
 * <ul>
 *   <li>zmniejsza obraz filtrem Lanczos-3 tak, by dłuższy bok miał zadaną długość (nigdy nie powiększa),</li>
 *   <li>koduje JPEG w jakości {@value #JPEG_QUALITY_PERCENT}% bez podpróbkowania kolorów (4:4:4),</li>
 *   <li>przenosi metadane oryginału (EXIF, XMP, IPTC, profil ICC, komentarze) — w EXIF poprawia wymiary,</li>
 *   <li>dopisuje do XMP sumę SHA-256 oryginalnego pliku ({@link XmpHash}),</li>
 *   <li>zachowuje datę modyfikacji oryginału.</li>
 * </ul>
 * Zdjęcie mniejsze niż zadany rozmiar nie jest kodowane ponownie — kopiujemy oryginalne dane obrazu
 * i tylko dopisujemy sumę do XMP.
 */
public final class ThumbnailMaker {

    public static final int JPEG_QUALITY_PERCENT = 95;

    /** Co zrobiono z plikiem. */
    public enum Result { RESIZED, KEPT_SIZE }

    private ThumbnailMaker() {
    }

    public static Result make(Path source, Path target, int maxSide) throws IOException {
        byte[] original = Files.readAllBytes(source);
        String sha256 = sha256(original);
        JpegSegments.Parsed parsed = JpegSegments.parse(original);

        JpegSegments.Segment oldXmp = parsed.find(JpegSegments.Segment::isXmp);
        JpegSegments.Segment xmp = JpegSegments.xmpSegment(oldXmp == null
                ? XmpHash.newPacket(sha256)
                : XmpHash.withHash(JpegSegments.xmpPacket(oldXmp), sha256));
        if (xmp.payload().length > JpegSegments.MAX_PAYLOAD) {
            throw new IOException("Metadane XMP oryginału są za duże, by dopisać do nich sumę kontrolną");
        }

        byte[] output;
        Result result;
        ImageReader reader = jpegReader();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            reader.setInput(in, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            int longest = Math.max(width, height);

            if (longest <= maxSide) {
                // Bez ponownego kodowania: oryginalne dane obrazu + metadane z nowym XMP.
                List<JpegSegments.Segment> segments =
                        JpegSegments.merge(parsed.segments(), parsed.segments(), xmp, null);
                output = JpegSegments.write(segments, parsed.imageData());
                result = Result.KEPT_SIZE;
            } else {
                double scale = (double) maxSide / longest;
                int newWidth = Math.max(1, (int) Math.round(width * scale));
                int newHeight = Math.max(1, (int) Math.round(height * scale));
                BufferedImage decoded = reader.read(0);
                int bands = decoded.getRaster().getNumBands();
                if (bands != 1 && bands != 3) {
                    throw new IOException("Nieobsługiwany model kolorów (" + bands + " kanały, np. CMYK)");
                }
                BufferedImage resized = Resampler.resize(decoded, newWidth, newHeight);
                decoded = null; // zwolnienie pamięci przed kodowaniem

                JpegSegments.Parsed encoded = JpegSegments.parse(encode(resized));
                JpegSegments.Segment exif = parsed.find(JpegSegments.Segment::isExif);
                JpegSegments.Segment newExif = exif == null ? null
                        : ExifDimensions.withDimensions(exif, newWidth, newHeight);
                List<JpegSegments.Segment> segments =
                        JpegSegments.merge(parsed.segments(), encoded.segments(), xmp, newExif);
                output = JpegSegments.write(segments, encoded.imageData());
                result = Result.RESIZED;
            }
        } finally {
            reader.dispose();
        }

        writeAtomically(target, output);
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
        return result;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ImageReader jpegReader() throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
        if (!readers.hasNext()) {
            throw new IOException("Brak dekodera JPEG");
        }
        return readers.next();
    }

    /** JPEG wysokiej jakości: {@value #JPEG_QUALITY_PERCENT}%, kolory bez podpróbkowania (4:4:4). */
    static byte[] encode(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("Brak kodera JPEG");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY_PERCENT / 100f);
            if (param instanceof JPEGImageWriteParam jpeg) {
                jpeg.setOptimizeHuffmanTables(true); // mniejszy plik bez straty jakości
            }

            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
            String format = "javax_imageio_jpeg_image_1.0";
            Node tree = metadata.getAsTree(format);
            NodeList components = ((Element) tree).getElementsByTagName("componentSpec");
            for (int i = 0; i < components.getLength(); i++) {
                Element component = (Element) components.item(i);
                component.setAttribute("HsamplingFactor", "1");
                component.setAttribute("VsamplingFactor", "1");
            }
            metadata.setFromTree(format, tree);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, metadata), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".photon-tmp");
        try {
            Files.write(temp, data);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
