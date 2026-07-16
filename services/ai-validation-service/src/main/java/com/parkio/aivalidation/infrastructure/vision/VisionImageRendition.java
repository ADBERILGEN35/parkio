package com.parkio.aivalidation.infrastructure.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Vision-only image rendition: EXIF orientation, longest-edge downscale, JPEG encode.
 * Does not mutate the stored original in media-service.
 */
public final class VisionImageRendition {

    public static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    private VisionImageRendition() {
    }

    public record Result(byte[] bytes, String contentType, int width, int height,
                         int originalWidth, int originalHeight, int originalBytes) {
    }

    public static Result prepare(byte[] source, String contentType, long maxDecodedPixels,
                                 int maxSourceEdge, int targetLongestEdge, float jpegQuality,
                                 long maxOutputBytes) {
        if (source == null || source.length == 0) {
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE, "empty image");
        }
        BufferedImage decoded = decode(source, contentType, maxDecodedPixels, maxSourceEdge);
        int orientation = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("jpeg")
                ? readJpegOrientation(source) : 1;
        BufferedImage oriented = applyOrientation(decoded, orientation);
        BufferedImage scaled = scaleToLongestEdge(oriented, targetLongestEdge);
        byte[] jpeg = writeJpeg(scaled, jpegQuality);
        if (jpeg.length > maxOutputBytes) {
            jpeg = writeJpeg(scaled, Math.max(0.50f, jpegQuality - 0.20f));
        }
        if (jpeg.length > maxOutputBytes) {
            throw new MediaContentException(MediaContentException.Reason.TOO_LARGE,
                    "vision rendition exceeds cap (" + jpeg.length + " bytes)");
        }
        return new Result(jpeg, OUTPUT_CONTENT_TYPE, scaled.getWidth(), scaled.getHeight(),
                oriented.getWidth(), oriented.getHeight(), source.length);
    }

    private static BufferedImage decode(byte[] content, String contentType,
                                        long maxDecodedPixels, int maxSourceEdge) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                        "unable to open image stream");
            }
            Iterator<ImageReader> readers = contentType == null || contentType.isBlank()
                    ? ImageIO.getImageReaders(input)
                    : ImageIO.getImageReadersByMIMEType(contentType);
            if (!readers.hasNext()) {
                readers = ImageIO.getImageReaders(input);
            }
            if (!readers.hasNext()) {
                throw new MediaContentException(MediaContentException.Reason.UNSUPPORTED_TYPE,
                        "no image reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                            "invalid image dimensions");
                }
                if (width > maxSourceEdge || height > maxSourceEdge
                        || (long) width * (long) height > maxDecodedPixels) {
                    throw new MediaContentException(MediaContentException.Reason.TOO_LARGE,
                            "image dimensions exceed vision decode limits");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                            "image decode returned null");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (MediaContentException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                    "unable to decode image", ex);
        }
    }

    static int readJpegOrientation(byte[] jpeg) {
        try {
            if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int offset = 2;
            while (offset + 4 < jpeg.length) {
                if ((jpeg[offset] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = jpeg[offset + 1] & 0xFF;
                int len = ((jpeg[offset + 2] & 0xFF) << 8) | (jpeg[offset + 3] & 0xFF);
                if (marker == 0xE1 && offset + 4 + len <= jpeg.length) {
                    int app1 = offset + 4;
                    if (app1 + 6 < jpeg.length
                            && jpeg[app1] == 'E' && jpeg[app1 + 1] == 'x'
                            && jpeg[app1 + 2] == 'i' && jpeg[app1 + 3] == 'f') {
                        return parseExifOrientation(jpeg, app1 + 6, offset + 2 + len);
                    }
                }
                if (len < 2) {
                    return 1;
                }
                offset += 2 + len;
                if (marker == 0xDA) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            return 1;
        }
        return 1;
    }

    private static int parseExifOrientation(byte[] data, int tiffStart, int end) {
        if (tiffStart + 8 >= end) {
            return 1;
        }
        boolean little = data[tiffStart] == 'I' && data[tiffStart + 1] == 'I';
        if (!little && !(data[tiffStart] == 'M' && data[tiffStart + 1] == 'M')) {
            return 1;
        }
        int ifdOffset = read32(data, tiffStart + 4, little);
        int ifd = tiffStart + ifdOffset;
        if (ifd + 2 > end) {
            return 1;
        }
        int entries = read16(data, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                break;
            }
            int tag = read16(data, entry, little);
            if (tag == 0x0112) {
                return read16(data, entry + 8, little);
            }
        }
        return 1;
    }

    private static int read16(byte[] d, int off, boolean le) {
        return le ? ((d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8))
                : (((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF));
    }

    private static int read32(byte[] d, int off, boolean le) {
        return le ? (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24)
                : ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        int w = source.getWidth();
        int h = source.getHeight();
        AffineTransform tx = new AffineTransform();
        int outW = w;
        int outH = h;
        switch (orientation) {
            case 3 -> {
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 6 -> {
                outW = h;
                outH = w;
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
            }
            case 8 -> {
                outW = h;
                outH = w;
                tx.translate(0, w);
                tx.rotate(-Math.PI / 2);
            }
            default -> {
                return source;
            }
        }
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, tx, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static BufferedImage scaleToLongestEdge(BufferedImage source, int targetLongestEdge) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= targetLongestEdge) {
            if (source.getType() == BufferedImage.TYPE_INT_RGB) {
                return source;
            }
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(source, 0, 0, null);
            } finally {
                g.dispose();
            }
            return rgb;
        }
        double scale = (double) targetLongestEdge / (double) longest;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(OUTPUT_CONTENT_TYPE);
        if (!writers.hasNext()) {
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE, "no jpeg writer");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.min(1f, Math.max(0.1f, quality)));
            }
            writer.write(null, new IIOImage(image, null, null), param);
            writer.dispose();
            return out.toByteArray();
        } catch (IOException ex) {
            writer.dispose();
            throw new MediaContentException(MediaContentException.Reason.UNAVAILABLE,
                    "jpeg encode failed", ex);
        }
    }
}
