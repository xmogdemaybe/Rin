package ru.rainedev.raine.vision;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Уменьшает картинку перед отправкой модели.
 * <p>
 * Смотрит она всё равно на уменьшенное, а платится за каждый байт: снимок
 * с телефона на четыре тысячи точек по стороне в base64 — это мегабайты
 * впустую и заметно более медленный ответ.
 */
public final class Downscale {

    private static final Logger log = LoggerFactory.getLogger(Downscale.class);

    /** Сторона, до которой ужимается всё, что уходит смотреть. */
    public static final int MAX_SIDE = 672;

    /** Качество JPEG: ниже заметно на мелких деталях, выше — лишние байты. */
    private static final float JPEG_QUALITY = 0.88f;

    private Downscale() {}

    /** @return уменьшенный JPEG или исходные байты, если картинка не разобралась */
    public static byte[] toFit(byte[] image, int maxSide) {
        if (maxSide <= 0) {
            throw new IllegalArgumentException("maxSide must be positive");
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                return image;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (width <= maxSide && height <= maxSide) {
                return image;
            }
            double scale = Math.min(maxSide / (double) width, maxSide / (double) height);
            int newWidth = Math.max(1, (int) (width * scale));
            int newHeight = Math.max(1, (int) (height * scale));

            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            if (source.getColorModel().hasAlpha()) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, newWidth, newHeight);
            }
            graphics.drawImage(source, 0, 0, newWidth, newHeight, null);
            graphics.dispose();

            byte[] encoded = jpeg(resized);
            log.debug("Картинка ужата с {}×{} до {}×{}", width, height, newWidth, newHeight);
            return encoded;
        } catch (Exception e) {
            // формат может оказаться незнакомым — тогда отправляем как есть
            log.debug("Картинку не уменьшить: {}", e.getMessage());
            return image;
        }
    }

    public static byte[] toFit(byte[] image) {
        return toFit(image, MAX_SIDE);
    }

    /** JPEG заданного качества; без найденного writer — обычная запись ImageIO. */
    private static byte[] jpeg(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
