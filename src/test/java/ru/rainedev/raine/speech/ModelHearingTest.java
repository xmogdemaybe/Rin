package ru.rainedev.raine.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.prompt.Prompts;

/**
 * Слух самой модели проверяется без эндпоинта: заглушка записывает, что ей
 * принесли, и отдаёт заготовленный ответ.
 */
class ModelHearingTest {

    /** Модель, которая слышит: видно, какой звук и в какой упаковке ей дошёл. */
    private static final class Ears implements LlmClient {
        final List<byte[]> sounds = new ArrayList<>();
        final List<String> formats = new ArrayList<>();
        final List<String> hints = new ArrayList<>();
        private final Deque<String> answers = new ArrayDeque<>();
        private boolean canHear = true;

        Ears willHear(String... said) {
            answers.addAll(List.of(said));
            return this;
        }

        Ears deaf() {
            canHear = false;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hears() {
            return canHear;
        }

        @Override
        public String hear(String model, String systemPrompt, String context, byte[] audio, String format) {
            sounds.add(audio);
            hints.add(context);
            formats.add(format);
            String answer = answers.poll();
            return answer == null ? "" : answer;
        }
    }

    private static Config.Hearing hearing(boolean voiceNotes, boolean videoNotes, Path cache) {
        return new Config.Hearing(false, "", "", "", "ru", voiceNotes, videoNotes, true, cache);
    }

    private static ModelHearing with(Ears ears, Path cache, boolean voiceNotes, boolean videoNotes) {
        return new ModelHearing(ears, new Prompts(Path.of("prompts"),
                new Config.Character("Raine", "@raine_tyan", "RaineDev")),
                "модель", cache, hearing(voiceNotes, videoNotes, cache));
    }

    /** Голосовое, какое отдаёт Telegram: OGG/Opus на несколько килобайт. */
    private static Path voiceNote(Path dir) throws IOException {
        Path file = dir.resolve("voice-1.ogg");
        Files.write(file, new byte[4096]);
        return file;
    }

    @Test
    void aVoiceNoteIsHeardAndRemembered(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears().willHear("привет, папик");
        Path voice = voiceNote(dir);

        assertEquals(Optional.of("привет, папик"), with(ears, cache, true, true).listen(voice, false));
        // упаковка та же, что у присланного файла: эндпоинт по ней решает, как
        // её распаковать, и видео ему не положить
        assertEquals(List.of("ogg"), ears.formats);

        // то же голосовое спрашивают не раз: чат открывают заново
        assertEquals(Optional.of("привет, папик"), with(ears, cache, true, true).listen(voice, false));
        assertEquals(1, ears.sounds.size(), "второй раз прослушивать незачем");
    }

    @Test
    void theCircleIsListenedThroughItsSoundTrack(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path circle = dir.resolve("circle-1.mp4");
        try (InputStream sample = ModelHearingTest.class.getResourceAsStream("/tone.mp4")) {
            assertTrue(sample != null, "образец видео не найден");
            Files.write(circle, sample.readAllBytes());
        }
        Ears ears = new Ears().willHear("тут говорят про встречу");

        assertEquals(Optional.of("тут говорят про встречу"), with(ears, cache, true, true).listen(circle, true));

        // кружок — это MP4, а видео в запрос не кладут: до модели доходит дорожка
        String head = new String(ears.sounds.get(0), 0, 4, StandardCharsets.US_ASCII);
        assertEquals("OggS", head, "модель должна получить звук, а не контейнер видео");
        assertEquals(List.of("ogg"), ears.formats);
        // и подсказка другая: в кружке есть картинка, её пересказ не нужен
        assertTrue(ears.hints.get(0).contains("circle"), ears.hints.get(0));

        // дорожка вынимается заново каждый раз, а услышанное помнится по кружку
        assertEquals(Optional.of("тут говорят про встречу"), with(ears, cache, true, true).listen(circle, true));
        assertEquals(1, ears.sounds.size(), "второй раз слушать незачем");
    }

    @Test
    void silenceIsNotSpeech(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears().willHear("Silence.");

        assertEquals(Optional.empty(), with(ears, cache, true, true).listen(voiceNote(dir), false));
        assertTrue(Files.notExists(cache.resolve("voice-1.ogg.heard.md")),
                "молчание не должно осесть в кэше как услышанное");

        assertTrue(ModelHearing.silence("Silence."));
        assertTrue(ModelHearing.silence("silence"));
        assertFalse(ModelHearing.silence("Silence in the room"), "это уже сказанное");
        assertFalse(ModelHearing.silence("привет"));
    }

    @Test
    void aRefusalIsNotRemembered(@TempDir Path dir) throws IOException {
        // извинение вместо услышанного непустое, и осев в кэше, оно стало бы
        // вечной расшифровкой: вложение больше никто не переспросил бы
        Path cache = dir.resolve("cache");
        Ears ears = new Ears()
                .willHear("I'm sorry, but I can't hear any speech here.")
                .willHear("привет");
        ModelHearing hearing = with(ears, cache, true, true);
        Path voice = voiceNote(dir);

        assertEquals(Optional.empty(), hearing.listen(voice, false));
        assertTrue(Files.notExists(cache.resolve("voice-1.ogg.heard.md")), "извинение — не услышанное");

        // то же голосовое переспрашивают, и на этот раз доходит
        assertEquals(Optional.of("привет"), hearing.listen(voice, false));
    }

    /** Файл мог прийти недокачанным: слушать пустоту незачем. */
    @Test
    void anUndersizedFileIsNotListenedTo(@TempDir Path dir) throws IOException {
        Path tiny = dir.resolve("voice-2.ogg");
        Files.write(tiny, new byte[16]);
        Ears ears = new Ears().willHear("что-то");

        assertEquals(Optional.empty(), with(ears, dir.resolve("cache"), true, true).listen(tiny, false));
        assertTrue(ears.sounds.isEmpty(), "эндпоинт не должен вызываться ради пустяка");
    }

    @Test
    void switchesChooseWhatMayBeHeard(@TempDir Path dir) {
        Path cache = dir.resolve("cache");
        ModelHearing both = with(new Ears(), cache, true, true);
        assertTrue(both.hearsVoiceNotes());
        assertTrue(both.hearsVideoNotes());

        ModelHearing mute = with(new Ears(), cache, false, false);
        assertFalse(mute.hearsVoiceNotes());
        assertFalse(mute.hearsVideoNotes());

        // тумблер владельца не всесилен: если у модели слуха нет, слушать нечем
        ModelHearing deaf = with(new Ears().deaf(), cache, true, true);
        assertFalse(deaf.hearsVoiceNotes());
        assertFalse(deaf.hearsVideoNotes());
    }
}
