package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilenameSafetyTest {

    private static boolean plain(String name) {
        return TelegramTools.isPlainFilename(name);
    }

    @Test
    void acceptsOrdinaryFilenames() {
        assertTrue(plain("1784651739.webp"));
        assertTrue(plain("1784651739.ogg"));
    }

    @Test
    void refusesPathsLeadingOutOfTheFolder() {
        // имя файла приходит от модели: без проверки это дорога к любому файлу на диске
        assertFalse(plain("../../etc/passwd"));
        assertFalse(plain("..\\windows\\system32"));
        assertFalse(plain("data/gallery/photo.webp"));
        assertFalse(plain("/etc/shadow"));
        assertFalse(plain(".."));
    }

    @Test
    void refusesADriveRelativeName() {
        // на Windows это тоже путь: ни точек, ни косых черт в нём нет, и проверка
        // «не выпуская за папку» его бы пропустила — двоеточие режем на имени
        assertFalse(plain("C:photo.png"));
        assertFalse(plain("D:photo.png"));
    }

    @Test
    void refusesEmptyName() {
        assertFalse(plain(""));
    }

    @Test
    void aPhotoThatIsThereIsFound(@TempDir Path gallery) throws Exception {
        Path photo = gallery.resolve("1784651739.webp");
        Files.writeString(photo, "снимок");

        assertEquals(photo, TelegramTools.fileInside(gallery, "1784651739.webp"));
    }

    @Test
    void aPhotoThatIsNotThereIsNotFound(@TempDir Path gallery) {
        assertNull(TelegramTools.fileInside(gallery, "missing.webp"));
    }

    @Test
    void aNameLeadingOutOfTheGalleryIsNotFound(@TempDir Path gallery) throws Exception {
        // проверка имени ловит точки, а эта ловит сам путь: собрать его можно
        // и без них — на Windows достаточно «C:photo.png»
        Files.writeString(gallery.getParent().resolve("outside.webp"), "чужое");

        assertNull(TelegramTools.fileInside(gallery, "../outside.webp"));
    }

    @Test
    void aFolderIsNotAPhoto(@TempDir Path gallery) throws Exception {
        // «.» разворачивается в саму папку: отправить её нельзя, и жаловаться
        // на несуществующий снимок честнее, чем тащить каталог в Telegram
        Files.createDirectory(gallery.resolve("sub"));

        assertNull(TelegramTools.fileInside(gallery, "sub"));
        assertNull(TelegramTools.fileInside(gallery, "."));
    }
}
