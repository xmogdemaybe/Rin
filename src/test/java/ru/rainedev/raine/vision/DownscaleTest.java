package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Уменьшение перед зрением: фото получает больше точек, дешёвым видам хватает 672. */
class DownscaleTest {

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] argbPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void photosWithinTheirLimitKeepTheOriginalBytes() throws Exception {
        byte[] original = jpeg(720, 1280);

        byte[] fitted = Downscale.toFit(original, Vision.Kind.PHOTO.maxSide);

        assertArrayEquals(original, fitted, "фото 720×1280 уже помещается в 1280 — пережатия нет");
    }

    @Test
    void photosBeyondTheLimitAreCutTo1280() throws Exception {
        byte[] original = jpeg(1440, 2560);

        byte[] fitted = Downscale.toFit(original, Vision.Kind.PHOTO.maxSide);
        BufferedImage image = read(fitted);

        assertEquals(1280, Math.max(image.getWidth(), image.getHeight()));
        assertEquals(720, Math.min(image.getWidth(), image.getHeight()));
        assertNotEquals(original.length, fitted.length, "уменьшенный JPEG не обязан совпадать по размеру");
    }

    @Test
    void cheapKindsStillCutTo672() throws Exception {
        byte[] original = jpeg(720, 1280);

        BufferedImage image = read(Downscale.toFit(original, Vision.Kind.STICKER.maxSide));

        assertEquals(672, Math.max(image.getWidth(), image.getHeight()),
                "стикеры, аватарки и кадры видео остаются дешёвыми");
    }

    @Test
    void onlyPhotosGetTheBiggerLimit() {
        assertEquals(1280, Vision.Kind.PHOTO.maxSide);
        assertEquals(672, Vision.Kind.STICKER.maxSide);
        assertEquals(672, Vision.Kind.AVATAR.maxSide);
        assertEquals(672, Vision.Kind.VIDEO.maxSide);
    }

    @Test
    void anUnreadableImageComesBackAsIs() {
        byte[] broken = {1, 2, 3};

        assertArrayEquals(broken, Downscale.toFit(broken));
    }

    @Test
    void transparentPhotosGetAWhiteBackground() throws Exception {
        byte[] original = argbPng(1440, 2560);

        BufferedImage image = read(Downscale.toFit(original, Vision.Kind.PHOTO.maxSide));

        assertEquals(1280, Math.max(image.getWidth(), image.getHeight()));
        assertEquals(720, Math.min(image.getWidth(), image.getHeight()));
        Color corner = new Color(image.getRGB(0, 0));
        assertTrue(corner.getRed() > 200 && corner.getGreen() > 200 && corner.getBlue() > 200,
                "прозрачный фон не должен становиться чёрным: " + corner);
    }

    @Test
    void aNonPositiveLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Downscale.toFit(new byte[] {1, 2, 3}, 0));
        assertThrows(IllegalArgumentException.class, () -> Downscale.toFit(new byte[] {1, 2, 3}, -1));
    }
}
