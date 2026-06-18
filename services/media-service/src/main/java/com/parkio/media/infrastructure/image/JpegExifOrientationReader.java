package com.parkio.media.infrastructure.image;

/** Minimal EXIF orientation reader for JPEG APP1 metadata. Invalid metadata is ignored. */
final class JpegExifOrientationReader {

    private JpegExifOrientationReader() {
    }

    static int readOrientation(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if ((jpeg[offset] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = jpeg[offset + 1] & 0xFF;
            offset += 2;
            if (marker == 0xDA || marker == 0xD9) {
                return 1;
            }
            if (offset + 2 > jpeg.length) {
                return 1;
            }
            int length = readU16(jpeg, offset, false);
            if (length < 2 || offset + length > jpeg.length) {
                return 1;
            }
            int segmentStart = offset + 2;
            int segmentLength = length - 2;
            if (marker == 0xE1 && segmentLength >= 14 && hasExifHeader(jpeg, segmentStart)) {
                return readTiffOrientation(jpeg, segmentStart + 6, segmentLength - 6);
            }
            offset += length;
        }
        return 1;
    }

    private static int readTiffOrientation(byte[] bytes, int tiffStart, int tiffLength) {
        if (tiffLength < 8) {
            return 1;
        }
        boolean littleEndian;
        int byteOrder = readU16(bytes, tiffStart, false);
        if (byteOrder == 0x4949) {
            littleEndian = true;
        } else if (byteOrder == 0x4D4D) {
            littleEndian = false;
        } else {
            return 1;
        }
        if (readU16(bytes, tiffStart + 2, littleEndian) != 42) {
            return 1;
        }
        long ifdOffset = readU32(bytes, tiffStart + 4, littleEndian);
        int ifd = tiffStart + checkedInt(ifdOffset);
        int tiffEnd = tiffStart + tiffLength;
        if (ifd < tiffStart || ifd + 2 > tiffEnd) {
            return 1;
        }
        int entries = readU16(bytes, ifd, littleEndian);
        int entryOffset = ifd + 2;
        for (int i = 0; i < entries; i++) {
            int entry = entryOffset + i * 12;
            if (entry + 12 > tiffEnd) {
                return 1;
            }
            int tag = readU16(bytes, entry, littleEndian);
            if (tag == 0x0112) {
                int value = readU16(bytes, entry + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static boolean hasExifHeader(byte[] bytes, int offset) {
        return bytes[offset] == 'E'
                && bytes[offset + 1] == 'x'
                && bytes[offset + 2] == 'i'
                && bytes[offset + 3] == 'f'
                && bytes[offset + 4] == 0
                && bytes[offset + 5] == 0;
    }

    private static int readU16(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
        }
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long readU32(byte[] bytes, int offset, boolean littleEndian) {
        if (littleEndian) {
            return ((long) bytes[offset] & 0xFF)
                    | (((long) bytes[offset + 1] & 0xFF) << 8)
                    | (((long) bytes[offset + 2] & 0xFF) << 16)
                    | (((long) bytes[offset + 3] & 0xFF) << 24);
        }
        return (((long) bytes[offset] & 0xFF) << 24)
                | (((long) bytes[offset + 1] & 0xFF) << 16)
                | (((long) bytes[offset + 2] & 0xFF) << 8)
                | ((long) bytes[offset + 3] & 0xFF);
    }

    private static int checkedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
