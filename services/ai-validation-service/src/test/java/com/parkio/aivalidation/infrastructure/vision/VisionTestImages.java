package com.parkio.aivalidation.infrastructure.vision;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Shared JPEG fixtures for vision unit tests (no real photos). */
public final class VisionTestImages {

    private VisionTestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(40, 90, 40));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(200, 200, 200));
            g.fillRect(width / 8, height / 3, width * 3 / 4, height / 3);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpeg", out)) {
                throw new IllegalStateException("JPEG writer unavailable");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
