package com.photon.thumbnails;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Aktualizuje wymiary obrazu zapisane w EXIF (PixelXDimension/PixelYDimension, a jeśli są —
 * ImageWidth/ImageLength), aby po zmniejszeniu zdjęcia metadane nie podawały starych wymiarów.
 * Pozostałe dane EXIF (w tym data wykonania, aparat, GPS, orientacja) zostają bez zmian.
 */
final class ExifDimensions {

    private static final int TAG_IMAGE_WIDTH = 0x0100;
    private static final int TAG_IMAGE_LENGTH = 0x0101;
    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_PIXEL_X = 0xA002;
    private static final int TAG_PIXEL_Y = 0xA003;
    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;

    private ExifDimensions() {
    }

    /**
     * @return kopia segmentu z nowymi wymiarami; przy nieczytelnym EXIF — segment bez zmian
     */
    static JpegSegments.Segment withDimensions(JpegSegments.Segment exif, int width, int height) {
        byte[] payload = exif.payload().clone();
        try {
            int base = JpegSegments.EXIF_HEADER.length;
            ByteBuffer tiff = ByteBuffer.wrap(payload, base, payload.length - base).slice();
            if (tiff.get(0) == 'I' && tiff.get(1) == 'I') {
                tiff.order(ByteOrder.LITTLE_ENDIAN);
            } else if (tiff.get(0) == 'M' && tiff.get(1) == 'M') {
                tiff.order(ByteOrder.BIG_ENDIAN);
            } else {
                return exif;
            }
            int ifd0 = tiff.getInt(4);
            patchIfd(tiff, ifd0, TAG_IMAGE_WIDTH, TAG_IMAGE_LENGTH, width, height);
            int exifIfd = findLong(tiff, ifd0, TAG_EXIF_IFD);
            if (exifIfd > 0) {
                patchIfd(tiff, exifIfd, TAG_PIXEL_X, TAG_PIXEL_Y, width, height);
            }
            return new JpegSegments.Segment(exif.marker(), payload);
        } catch (IndexOutOfBoundsException e) {
            return exif; // uszkodzony EXIF — przenosimy bez zmian
        }
    }

    private static void patchIfd(ByteBuffer tiff, int ifd, int tagX, int tagY, int width, int height) {
        int count = tiff.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            int tag = tiff.getShort(entry) & 0xFFFF;
            if (tag == tagX || tag == tagY) {
                int value = tag == tagX ? width : height;
                int type = tiff.getShort(entry + 2) & 0xFFFF;
                if (type == TYPE_SHORT && value <= 0xFFFF) {
                    tiff.putShort(entry + 8, (short) value);
                } else if (type == TYPE_LONG) {
                    tiff.putInt(entry + 8, value);
                }
            }
        }
    }

    private static int findLong(ByteBuffer tiff, int ifd, int wanted) {
        int count = tiff.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if ((tiff.getShort(entry) & 0xFFFF) == wanted) {
                return tiff.getInt(entry + 8);
            }
        }
        return -1;
    }
}
