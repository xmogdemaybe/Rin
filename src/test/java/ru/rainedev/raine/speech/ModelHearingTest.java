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
import ru.rainedev.raine.speech.ModelHearing.Track;

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
        return new Config.Hearing(true, voiceNotes, videoNotes, true, cache);
    }

    private static ModelHearing with(Ears ears, Path cache, boolean voiceNotes, boolean videoNotes) {
        return new ModelHearing(ears, new Prompts(Path.of("prompts"),
                new Config.Character("Raine", "@raine_tyan", "RaineDev")),
                "модель", cache, hearing(voiceNotes, videoNotes, cache));
    }

    @Test
    void theMasterSwitchSilencesTheEars() {
        // тумблер «слух выключен» раньше ничего не значил для голосовых: он
        // относился к отдельному распознавателю, которого больше нет
        ModelHearing deaf = new ModelHearing(new Ears(), new Prompts(Path.of("prompts"),
                new Config.Character("Raine", "@raine_tyan", "RaineDev")),
                "модель", Path.of("cache"), new Config.Hearing(false, true, true, true, Path.of("cache")));

        assertFalse(deaf.hearsVoiceNotes(), "выключено — значит не слушает");
        assertFalse(deaf.hearsVideoNotes(), "выключено — значит не слушает");
        assertFalse(deaf.hearsVideoAudio(), "выключено — значит не слушает");
    }

    @Test
    void aVideoTrackIsHeardByTheChatModel() {
        // дорожка ролика идёт тем же ухом, что голосовое: отдельного адреса под
        // распознавание больше нет, и без него бот делал бы вид, что слушает
        assertTrue(with(new Ears(), Path.of("cache"), true, true).hearsVideoAudio());
    }

    /**
     * Голосовое, каким его отдаёт Telegram: OGG/Opus на 48 кГц, а не абстрактные
     * байты — слух учится раскодировать именно такую упаковку.
     */
    private static Path voiceNote(Path dir) throws Exception {
        return voiceNote(dir, "voice-1.ogg");
    }

    private static Path voiceNote(Path dir, String name) throws Exception {
        int rate = 48_000;
        short[] tone = new short[rate];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000);
        }
        Path file = dir.resolve(name);
        Files.write(file, OggOpus.encode(tone, rate, 24_000));
        return file;
    }

    @Test
    void aVoiceNoteIsHeardAndRemembered(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears().willHear("привет, папик");
        Path voice = voiceNote(dir);

        assertEquals(Optional.of("привет, папик"), with(ears, cache, true, true).listen(voice, Track.VOICE));
        // голосовое доходит распакованным: эндпоинт принимает только wav и mp3,
        // а ogg развернул бы запрос сороковым ответом
        assertEquals(List.of("wav"), ears.formats);
        assertEquals("RIFF", new String(ears.sounds.get(0), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(ears.sounds.get(0), 8, 4, StandardCharsets.US_ASCII));

        // то же голосовое спрашивают не раз: чат открывают заново
        assertEquals(Optional.of("привет, папик"), with(ears, cache, true, true).listen(voice, Track.VOICE));
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

        assertEquals(Optional.of("тут говорят про встречу"), with(ears, cache, true, true).listen(circle, Track.CIRCLE));

        // кружок — это MP4, а видео в запрос не кладут: до модели доходит дорожка
        String head = new String(ears.sounds.get(0), 0, 4, StandardCharsets.US_ASCII);
        assertEquals("RIFF", head, "модель должна получить звук, а не контейнер видео");
        assertEquals(List.of("wav"), ears.formats);
        // и подсказка другая: это дорожка ролика, а не голосовое — картинку
        // пересказывать не нужно, её смотрит зрение
        assertTrue(ears.hints.get(0).contains("sound track of a video"), ears.hints.get(0));
        assertTrue(ears.hints.get(0).contains("do not describe the picture"), ears.hints.get(0));

        // дорожка вынимается заново каждый раз, а услышанное помнится по кружку
        assertEquals(Optional.of("тут говорят про встречу"), with(ears, cache, true, true).listen(circle, Track.CIRCLE));
        assertEquals(1, ears.sounds.size(), "второй раз слушать незачем");
    }

    /**
     * Снятый тумблер «слушать кружки» обходился дорогою через зрение: кружок
     * отдавали разбору дорожки ролика, а там спрашивают другое разрешение.
     * Решение живёт там, где слушают, — в обход его не провести.
     */
    @Test
    void aCircleIgnoredBySwitchIsNotHeardAtAll(@TempDir Path dir) throws IOException {
        Path circle = dir.resolve("circle-2.mp4");
        try (InputStream sample = ModelHearingTest.class.getResourceAsStream("/tone.mp4")) {
            assertTrue(sample != null, "образец видео не найден");
            Files.write(circle, sample.readAllBytes());
        }
        Ears ears = new Ears().willHear("тут говорят про встречу");

        assertEquals(Optional.empty(), with(ears, dir.resolve("cache"), true, false).listen(circle, Track.CIRCLE),
                "кружок под снятым тумблером не слушают");
        assertTrue(ears.sounds.isEmpty(), "до эндпоинта дело не должно дойти");

        // это касается только кружков: дорожка обычного ролика слушается и тогда,
        // когда кружки сняты
        assertEquals(Optional.of("тут говорят про встречу"),
                with(ears, dir.resolve("cache2"), true, false).listen(circle, Track.VIDEO));
    }

    @Test
    void silenceIsNotSpeech(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears().willHear("Silence.");

        assertEquals(Optional.empty(), with(ears, cache, true, true).listen(voiceNote(dir), Track.VOICE));
        assertTrue(Files.notExists(cache.resolve("voice-1.ogg.heard.md")),
                "молчание не должно осесть в кэше как услышанное");

        assertTrue(ModelHearing.silence("Silence."));
        assertTrue(ModelHearing.silence("silence"));
        assertFalse(ModelHearing.silence("Silence in the room"), "это уже сказанное");
        assertFalse(ModelHearing.silence("привет"));
    }

    @Test
    void aRefusalIsNotRemembered(@TempDir Path dir) throws Exception {
        // извинение вместо услышанного непустое, и осев в кэше, оно стало бы
        // вечной расшифровкой: вложение больше никто не переспросил бы
        Path cache = dir.resolve("cache");
        Ears ears = new Ears()
                .willHear("I'm sorry, but I can't hear any speech here.")
                .willHear("привет");
        Path voice = voiceNote(dir);

        assertEquals(Optional.empty(), with(ears, cache, true, true).listen(voice, Track.VOICE));
        assertTrue(Files.notExists(cache.resolve("voice-1.ogg.heard.md")), "извинение — не услышанное");

        // то же голосовое переспрашивают, и на этот раз доходит
        assertEquals(Optional.of("привет"), with(ears, cache, true, true).listen(voice, Track.VOICE));
    }

    /**
     * Длинную запись обрезают до бюджета, а не выбрасывают: начало лучше
     * молчания. Молча отдать его значило бы выдать обрыв за целое, и модель
     * стала бы досказывать речь сама.
     * <p>
     * Целая запись пометки не получает — это видно по остальным проверкам, где
     * услышанное ожидается ровным текстом без приписки.
     */
    @Test
    void aCutRecordSaysItWasNotHeardToTheEnd() {
        assertEquals("привет", ModelHearing.withNote("привет", false), "целую запись нечем помечать");

        String heard = ModelHearing.withNote("привет", true);

        assertTrue(heard.startsWith("привет\n"), heard);
        assertTrue(heard.contains("90"), "в пометке сказано, до скольких секунд дослушали: " + heard);
        assertTrue(heard.contains("beginning"), heard);
    }

    /**
     * Промпт просит называть тишину одним словом, но модель часто формулирует
     * иначе — «No speech detected.». Такой ответ не проходил ни как тишина, ни
     * как отказ: он ложился в кэш и становился вечной «расшифровкой».
     */
    @Test
    void otherWordsForNoSpeechAreNotSpeechEither(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears().willHear("No speech detected.");

        assertEquals(Optional.empty(), with(ears, cache, true, true).listen(voiceNote(dir), Track.VOICE));
        assertTrue(Files.notExists(cache.resolve("voice-1.ogg.heard.md")),
                "«нет речи» не должно осесть в кэше как услышанное");

        assertTrue(ModelHearing.silence("No speech detected."));
        assertTrue(ModelHearing.silence("no speech"));
        assertFalse(ModelHearing.silence("There is no speech, but he says привет"), "это уже сказанное");
    }

    /**
     * Промпт просит ставить скобки там, где слово не разобралось, — но ответ,
     * целиком закрытый в скобки, это уже описание записи, а не речь: оно не
     * должно становиться услышанным и оседать в кэше.
     */
    @Test
    void aWholeAnswerInBracketsIsNotTranscription(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears().willHear("[голоса на фоне]").willHear("привет [...] папик");
        ModelHearing hearing = with(ears, cache, true, true);

        assertEquals(Optional.empty(), hearing.listen(voiceNote(dir, "voice-5.ogg"), Track.VOICE));
        assertTrue(Files.notExists(cache.resolve("voice-5.ogg.heard.md")), "описание записи — не услышанное");

        // скобка внутри слов ничего не ломает: так помечается неразобранное место
        assertEquals(Optional.of("привет [...] папик"),
                hearing.listen(voiceNote(dir, "voice-6.ogg"), Track.VOICE));
    }

    /**
     * Файл кэша пишется не атомарно: посреди записи его можно застать пустым или
     * оборванным. Принятая за услышанное пустота ставила бы автору отметку
     * «прослушано» и отдавала бы в разговор пустую расшифровку.
     */
    @Test
    void anEmptyCacheFileIsNotAnAnswer(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Files.createDirectories(cache);
        Path voice = voiceNote(dir);
        Files.writeString(cache.resolve("voice-1.ogg.heard.md"), "");
        Ears ears = new Ears().willHear("привет");

        assertEquals(Optional.of("привет"), with(ears, cache, true, true).listen(voice, Track.VOICE),
                "пустой кэш — промах, а не услышанное");
        assertEquals(1, ears.sounds.size(), "прослушивание должно состояться, а не отразиться пустотой");
    }

    /**
     * Одно и то же голосовое прилетает в разговор дважды за ход: его видно и в
     * уведомлении, и в истории открываемого чата. Неудачу кэш не запоминает,
     * так что без паузы за каждую повторную перерисовку пришлось бы платить.
     */
    @Test
    void aFailedAttemptIsNotPaidForTwiceRightAway(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Ears ears = new Ears()
                .willHear("привет")
                .willHear("и это тоже");
        ModelHearing hearing = with(ears, cache, true, true);
        Path voice = voiceNote(dir);

        assertEquals(Optional.of("привет"), hearing.listen(voice, Track.VOICE));
        // услышанное легло в кэш, и второй заход берёт его оттуда, не платя
        assertEquals(Optional.of("привет"), hearing.listen(voice, Track.VOICE));
        assertEquals(1, ears.sounds.size());

        // а вот неудачу второй раз hearing не повторяет: тот же файл, тот же ответ
        Ears moody = new Ears().willHear("");
        ModelHearing second = with(moody, dir.resolve("cache2"), true, true);
        Path other = voiceNote(dir, "voice-3.ogg");
        assertEquals(Optional.empty(), second.listen(other, Track.VOICE));
        assertEquals(Optional.empty(), second.listen(other, Track.VOICE));
        assertEquals(1, moody.sounds.size(), "прослушанное только что не переспрашивают");
    }

    /** Файл мог прийти недокачанным: слушать пустоту незачем. */
    @Test
    void anUndersizedFileIsNotListenedTo(@TempDir Path dir) throws IOException {
        Path tiny = dir.resolve("voice-2.ogg");
        Files.write(tiny, new byte[16]);
        Ears ears = new Ears().willHear("что-то");

        assertEquals(Optional.empty(), with(ears, dir.resolve("cache"), true, true).listen(tiny, Track.VOICE));
        assertTrue(ears.sounds.isEmpty(), "эндпоинт не должен вызываться ради пустяка");
    }

    /**
     * Занять слот под прослушивание значит заплатить за него, а до эндпоинта дело
     * может и не дойти: Telegram отдаёт ещё не докачанный файл. Если пауза
     * поставлена на таком проходе, докачанное голосовое остаётся неслушанным
     * четверть часа — ровно тогда, когда слушать уже есть что.
     */
    @Test
    void aVoiceNoteStillDownloadingDoesNotBurnTheSlot(@TempDir Path dir) throws Exception {
        Path voice = dir.resolve("voice-9.ogg");
        Files.write(voice, new byte[16]);
        Ears ears = new Ears().willHear("докачалось");
        ModelHearing hearing = with(ears, dir.resolve("cache"), true, true);

        assertEquals(Optional.empty(), hearing.listen(voice, Track.VOICE), "в недокачанном файле слушать нечего");
        assertTrue(ears.sounds.isEmpty(), "ради пустяка эндпоинт не зовут");

        voiceNote(dir, "voice-9.ogg");

        assertEquals(Optional.of("докачалось"), hearing.listen(voice, Track.VOICE),
                "голосовое, за которое эндпоинт так и не спросили, не должно платить паузой");
        assertEquals(1, ears.sounds.size());
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
