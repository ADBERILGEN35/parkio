package com.parkio.media.testsupport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg() {
        return jpeg(16, 16, 31);
    }

    public static byte[] jpeg(int width, int height, int seed) {
        return write("jpeg", image(width, height, seed));
    }

    private static BufferedImage image(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(seed));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.drawLine(0, 0, width - 1, height - 1);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] write(String format, BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
