package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilenameSafetyTest {

    private static boolean plain(String name) throws Exception {
        Method method = TelegramTools.class.getDeclaredMethod("isPlainFilename", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, name);
    }

    @Test
    void acceptsOrdinaryFilenames() throws Exception {
        assertTrue(plain("1784651739.webp"));
        assertTrue(plain("1784651739.ogg"));
    }

    @Test
    void refusesPathsLeadingOutOfTheFolder() throws Exception {
        // имя файла приходит от модели: без проверки это дорога к любому файлу на диске
        assertFalse(plain("../../etc/passwd"));
        assertFalse(plain("..\\windows\\system32"));
        assertFalse(plain("data/gallery/photo.webp"));
        assertFalse(plain("/etc/shadow"));
        assertFalse(plain(".."));
    }

    @Test
    void refusesEmptyName() throws Exception {
        assertFalse(plain(""));
    }

    @Test
    void aPhotoThatIsThereIsFound(@TempDir Path gallery) throws Exception {
        Path photo = gallery.resolve("1784651739.webp");
        Files.writeString(photo, "снимок");

        assertEquals(photo, TelegramTools.galleryPhoto(gallery, "1784651739.webp"));
    }

    @Test
    void aPhotoThatIsNotThereIsNotFound(@TempDir Path gallery) {
        assertNull(TelegramTools.galleryPhoto(gallery, "missing.webp"));
    }

    @Test
    void aNameLeadingOutOfTheGalleryIsNotFound(@TempDir Path gallery) throws Exception {
        // проверка имени ловит точки, а эта ловит сам путь: собрать его можно
        // и без них — на Windows достаточно «C:photo.png»
        Files.writeString(gallery.getParent().resolve("outside.webp"), "чужое");

        assertNull(TelegramTools.galleryPhoto(gallery, "../outside.webp"));
    }
}
