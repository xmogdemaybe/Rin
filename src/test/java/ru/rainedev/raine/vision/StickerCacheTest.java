package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.prompt.Prompts;

/**
 * Описания стикеров живут в вечной папке, а всё остальное — под сроком уборки.
 * <p>
 * Разбор картинки стоит денег и минут, а стикер смотрят один раз и пользуются
 * им годами. Если его описание лежит рядом с фото, уборка сносит его через
 * две недели по времени записи — и полка снова платит зрением за то, что
 * уже знала.
 */
class StickerCacheTest {

    /** Один взгляд — один ответ; счёт нужен, чтобы ловить платный повтор. */
    private static final class OnceLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        int looks;

        OnceLlm willSee(String description) {
            answers.add(description);
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }

        @Override
        public String describeImage(String model, String systemPrompt, String context, byte[] image) {
            looks++;
            return answers.isEmpty() ? "" : answers.poll();
        }
    }

    private static Vision vision(OnceLlm llm, Path cache, Path stickers) {
        return new Vision(llm, new Prompts(Path.of("prompts"),
                new Config.Character("Мира", "@mira_tyan", "MiraDev")),
                cache, stickers, "основная", "дешёвая", "Мира");
    }

    private static Path file(Path dir, String name, int bytes) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, new byte[bytes]);
        return file;
    }

    @Test
    void stickerDescriptionLivesInTheEternalFolder(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        OnceLlm llm = new OnceLlm().willSee("- CommunicativeMeaning: машет рукой");
        Path sticker = file(cache, "kittycat.webp", 2048);

        vision(llm, cache, stickers).describe(sticker, Vision.Kind.STICKER, List.of());

        assertTrue(Files.exists(stickers.resolve("kittycat.webp.md")),
                "описание стикера должно лежать в вечной папке");
        assertFalse(Files.exists(cache.resolve("kittycat.webp.md")),
                "в папке под сроком описания стикера быть не должно: уборка снесла бы его через две недели");
    }

    @Test
    void photoDescriptionStaysUnderRetention(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        OnceLlm llm = new OnceLlm().willSee("человек с котом");
        Path photo = file(cache, "holiday.jpg", 2048);

        vision(llm, cache, stickers).describe(photo, Vision.Kind.PHOTO, List.of());

        assertTrue(Files.exists(cache.resolve("holiday.jpg.md")), "фото — под сроком, так и было");
        assertFalse(Files.exists(stickers.resolve("holiday.jpg.md")),
                "вечная папка для стикеров: чужие фото делают её свалкой, которую никто не чистит");
    }

    @Test
    void oldStickerDescriptionMovesOnFirstRead(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        Files.createDirectories(cache);
        Files.createDirectories(stickers);
        Path sticker = file(cache, "kittycat.webp", 2048);
        Files.writeString(cache.resolve("kittycat.webp.md"), "- CommunicativeMeaning: машет рукой");
        OnceLlm llm = new OnceLlm();

        String seen = vision(llm, cache, stickers).describe(sticker, Vision.Kind.STICKER, List.of());

        assertTrue(seen.contains("машет рукой"), "готовое описание читается как готовое");
        assertEquals(0, llm.looks, "перенос не имеет права платить зрением за уже разгляденное");
        assertTrue(Files.exists(stickers.resolve("kittycat.webp.md")));
        assertFalse(Files.exists(cache.resolve("kittycat.webp.md")),
                "прежнее место освобождается: иначе через две недели копия кончится, а её нечем заменить");
    }

    @Test
    void knownAndReadOfflineAgreeOnBothFolders(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        Files.createDirectories(cache);
        Files.createDirectories(stickers);
        Path moved = file(cache, "fresh.webp", 2048);
        Files.writeString(stickers.resolve("fresh.webp.md"), "уже в вечной папке");
        Path aside = file(cache, "aside.webp", 2048);
        Files.writeString(cache.resolve("aside.webp.md"), "ещё под сроком");
        OnceLlm llm = new OnceLlm();
        Vision vision = vision(llm, cache, stickers);

        assertTrue(vision.known(moved));
        assertTrue(vision.known(aside));
        assertEquals("уже в вечной папке", vision.cachedDescription(moved));
        assertEquals("ещё под сроком", vision.cachedDescription(aside));
        assertEquals("", vision.cachedDescription(file(cache, "absent.webp", 2048)));
        assertEquals(0, llm.looks, "полка пересобирается посреди разговора и не имеет права ждать модель");
    }

    @Test
    void unreadableAndRecentlyFailedFilesAreNotWorthALook(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        Path tiny = file(cache, "tiny.webp", 8);
        Path fine = file(cache, "fine.webp", 2048);
        Path broken = file(cache, "broken.webp", 2048);
        Vision vision = vision(new OnceLlm(), cache, stickers);

        assertFalse(vision.unusable(fine), "скачанный и целый файл смотреть можно");
        assertTrue(vision.unusable(tiny), "недокачанный файл — пропуск, а не отказ зрения");
        assertTrue(vision.unusable(cache.resolve("nowhere.webp")), "нет файла — смотреть нечем");

        Files.writeString(cache.resolve("broken.webp.failed"), Long.toString(System.currentTimeMillis()));
        assertTrue(vision.unusable(broken), "час после неудачи — тот же промах за те же деньги");
        Files.writeString(cache.resolve("broken.webp.failed"),
                Long.toString(System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis()));
        assertFalse(vision.unusable(broken), "память о неудаче истекает: файл мог докачаться");
    }

    @Test
    void successClearsTheFailureMarker(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        Path sticker = file(cache, "kittycat.webp", 2048);
        // просроченный маркер: свежий просто не пустил бы к модели, и проверять
        // было бы нечего
        Files.writeString(cache.resolve("kittycat.webp.failed"),
                Long.toString(System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis()));
        OnceLlm llm = new OnceLlm().willSee("- CommunicativeMeaning: кивает");

        vision(llm, cache, stickers).describe(sticker, Vision.Kind.STICKER, List.of());

        assertEquals(1, llm.looks);
        assertFalse(Files.exists(cache.resolve("kittycat.webp.failed")),
                "описание есть — память о неудаче больше не о чём, а в вечную папку её не переносят и уборка до неё не дотянется");
    }

    @Test
    void startupMoveTakesOnlyStickerDescriptions(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("one.webp.md"), "первый");
        Files.writeString(cache.resolve("two.webp.md"), "второй");
        Files.writeString(cache.resolve("photo.jpg.md"), "фото");
        Files.writeString(cache.resolve("clip.mp4.md"), "ролик");
        Files.writeString(cache.resolve("bad.webp.failed"), "1");

        int moved = Vision.moveStickerCache(cache, stickers);

        assertEquals(2, moved);
        assertEquals("первый", Files.readString(stickers.resolve("one.webp.md")));
        assertEquals("второй", Files.readString(stickers.resolve("two.webp.md")));
        assertTrue(Files.exists(cache.resolve("photo.jpg.md")), "фото под сроком остаться должно");
        assertTrue(Files.exists(cache.resolve("clip.mp4.md")), "ролик под сроком остаться должен");
        assertTrue(Files.exists(cache.resolve("bad.webp.failed")),
                "память о неудаче живёт коротко и чистится уборкой: в вечной папке она бы накопилась за годы");
        assertEquals(0, Vision.moveStickerCache(cache, stickers), "второй заход ничего не переносит");
    }

    @Test
    void startupMoveKeepsTheNewerEternalCopy(@TempDir Path root) throws IOException {
        Path cache = root.resolve("cache/images");
        Path stickers = root.resolve("data/stickers");
        Files.createDirectories(cache);
        Files.createDirectories(stickers);
        Files.writeString(cache.resolve("one.webp.md"), "прежнее");
        Files.writeString(stickers.resolve("one.webp.md"), "вечное");

        assertEquals(0, Vision.moveStickerCache(cache, stickers));
        assertEquals("вечное", Files.readString(stickers.resolve("one.webp.md")));
        assertTrue(Files.exists(cache.resolve("one.webp.md")),
                "то, что уже лежит в вечной папке, не вытирается переносом");
    }
}
