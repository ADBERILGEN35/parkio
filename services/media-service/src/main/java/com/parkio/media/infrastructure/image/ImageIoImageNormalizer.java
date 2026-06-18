package com.parkio.media.infrastructure.image;

import com.parkio.media.application.MediaUploadConstraints;
import com.parkio.media.application.port.ImageNormalizationException;
import com.parkio.media.application.port.ImageNormalizer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Normalizes user-uploaded images into server-generated JPEG bytes. Re-encoding from
 * pixels strips EXIF/GPS/device metadata and avoids storing the original structure.
 */
@Component
public class ImageIoImageNormalizer implements ImageNormalizer {

    static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    private final MediaUploadConstraints constraints;

    public ImageIoImageNormalizer(MediaUploadConstraints constraints) {
        this.constraints = constraints;
    }

    @Override
    public NormalizedImage normalize(byte[] content, String detectedContentType) {
        BufferedImage decoded = decode(content, detectedContentType);
        int orientation = "image/jpeg".equals(detectedContentType) ? JpegExifOrientationReader.readOrientation(content) : 1;
        BufferedImage oriented = applyOrientation(decoded, orientation);
        BufferedImage rgb = toRgb(oriented);
        return new NormalizedImage(writeJpeg(rgb), OUTPUT_CONTENT_TYPE);
    }

    private BufferedImage decode(byte[] content, String detectedContentType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new ImageNormalizationException("Unable to create image input stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(detectedContentType);
            if (!readers.hasNext()) {
                throw new ImageNormalizationException("No ImageIO reader for " + detectedContentType);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (constraints.exceedsImageLimits(width, height)) {
                    throw new ImageNormalizationException("Image dimensions exceed configured limits");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new ImageNormalizationException("ImageIO returned no image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ImageNormalizationException normalizationException) {
                throw normalizationException;
            }
            throw new ImageNormalizationException("Unable to decode image", ex);
        }
    }

    private static BufferedImage toRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(OUTPUT_CONTENT_TYPE);
        if (!writers.hasNext()) {
            throw new ImageNormalizationException("No ImageIO writer for " + OUTPUT_CONTENT_TYPE);
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.90f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            imageOut.flush();
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ImageNormalizationException("Unable to encode normalized JPEG", ex);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        int width = source.getWidth();
        int height = source.getHeight();
        AffineTransform transform = new AffineTransform();
        int outputWidth = width;
        int outputHeight = height;

        switch (orientation) {
            case 2 -> {
                transform.scale(-1, 1);
                transform.translate(-width, 0);
            }
            case 3 -> {
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case 4 -> {
                transform.scale(1, -1);
                transform.translate(0, -height);
            }
            case 5 -> {
                transform.rotate(Math.PI / 2);
                transform.scale(1, -1);
                outputWidth = height;
                outputHeight = width;
            }
            case 6 -> {
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
                outputWidth = height;
                outputHeight = width;
            }
            case 7 -> {
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
                transform.scale(-1, 1);
                outputWidth = height;
                outputHeight = width;
            }
            case 8 -> {
                transform.translate(0, width);
                transform.rotate(-Math.PI / 2);
                outputWidth = height;
                outputHeight = width;
            }
            default -> {
                return source;
            }
        }

        BufferedImage output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }
}
