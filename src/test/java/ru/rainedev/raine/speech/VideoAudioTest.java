package ru.rainedev.raine.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Образец рядом — двухсекундный ролик с тоном 440 Гц (AAC, 44,1 кГц, стерео),
 * то есть ровно та связка, которая приходит из переписки. Проверяется весь путь
 * целиком: разбор MP4, декодирование AAC, сведение каналов и смена частоты —
 * и всё это без единой внешней программы.
 */
class VideoAudioTest {

    /** На эту частоту дорожка пересчитывается. */
    private static final int RATE = 16_000;

    /** Столько секунд дорожки просят: лимит приходит снаружи, а не живёт здесь. */
    private static final int BUDGET = 90;

    private static Path fixture() throws IOException {
        Path video = Files.createTempFile("raine-test-video-", ".mp4");
        try (InputStream source = VideoAudioTest.class.getResourceAsStream("/tone.mp4")) {
            assertTrue(source != null, "образец видео не найден");
            Files.write(video, source.readAllBytes());
        }
        return video;
    }

    @Test
    void theSoundItselfSurvivesTheWholeWay() throws Exception {
        Path video = fixture();
        try {
            Sound sound = VideoAudio.sound(video, BUDGET).orElseThrow();
            short[] samples = sound.samples();

            int loudest = 0;
            int crossings = 0;
            for (int i = 1; i < samples.length; i++) {
                loudest = Math.max(loudest, Math.abs(samples[i]));
                if (samples[i - 1] < 0 && samples[i] >= 0) {
                    crossings++;
                }
            }
            double seconds = samples.length / (double) sound.rate();
            assertEquals(2, seconds, 0.2, "дорожка должна быть длиной с ролик");
            assertFalse(sound.cut(), "короткую дорожку не надо помечать обрезанной");
            assertTrue(loudest > 1000, "вместо звука вышла тишина, громкость: " + loudest);
            assertEquals(440, crossings / seconds, 15, "тон уехал — где-то перепутана частота");
        } finally {
            Files.deleteIfExists(video);
        }
    }

    /**
     * Лимит теперь спрашивают до тяжёлой работы, а не после: раньше дорожку
     * декодировали целиком, а выбрасывал её уже слух. Двухсекундный ролик играет
     * роль длинного: с маленьким бюджетом он оборван, а не потерян.
     */
    @Test
    void aTrackLongerThanTheBudgetIsCutNotThrownAway() throws IOException {
        Path video = fixture();
        try {
            Sound sound = VideoAudio.sound(video, 1).orElseThrow();

            assertTrue(sound.cut(), "дорожка оборвана по лимиту — модель должна об этом знать");
            assertEquals(1, sound.samples().length / (double) sound.rate(), 0.1,
                    "взято ровно столько, сколько просят");
        } finally {
            Files.deleteIfExists(video);
        }
    }

    /**
     * До первого раскодированного кадра частоты нет, и срез, посчитанный по
     * нулевой частоте, срабатывал на любом числе отсчётов: цикл выходил после
     * первого же чанка, а в лог писалось «дорожка длиннее лимита».
     */
    @Test
    void theBudgetIsCountedOnlyAfterTheRateIsKnown() {
        assertFalse(VideoAudio.reachedLimit(100_000, 0, BUDGET), "без частоты отсчёты ещё не секунды");
        assertTrue(VideoAudio.reachedLimit(1024, 1024, 1));
        assertFalse(VideoAudio.reachedLimit(1023, 1024, 1), "недобранной секунды превышением не считают");
    }

    @Test
    void trackIsOneChannelAtSpeechRate() throws IOException {
        // модель принимает только звук одного канала речевой частоты: стерео и
        // 44,1 кГц ушли бы в запрос вдвое большим количеством байт
        Path video = fixture();
        try {
            Sound sound = VideoAudio.sound(video, BUDGET).orElseThrow();

            assertEquals(RATE, sound.rate(), "частота должна быть речевой");
            assertEquals(2, sound.samples().length / (double) sound.rate(), 0.2,
                    "отсчётов ровно на один канал: их было вдвое больше, будь дорожка стерео");
        } finally {
            Files.deleteIfExists(video);
        }
    }

    @Test
    void nonsenseFileIsNotAnError() throws IOException {
        Path file = Files.createTempFile("raine-test-", ".mp4");
        try {
            Files.write(file, "это не видео".getBytes(StandardCharsets.UTF_8));

            assertFalse(VideoAudio.sound(file, BUDGET).isPresent(), "из мусора не должно получиться дорожки");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileIsNotAnError() {
        assertFalse(VideoAudio.sound(Path.of("/net/takogo/video.mp4"), BUDGET).isPresent());
    }

    @Test
    void silenceIsNotTakenForSpeech() {
        // распознавание речи на тишине не молчит: оно выдумывает субтитры,
        // и они ушли бы в разговор как услышанное
        assertTrue(VideoAudio.silent(new short[] {0, 1, -1, 255, -255}), "шум квантования — ещё не речь");
        assertTrue(VideoAudio.silent(new short[0]), "в пустой дорожке говорить нечему");
    }

    @Test
    void soundIsNotTakenForSilence() {
        assertFalse(VideoAudio.silent(new short[] {0, 0, 300, 0}), "тихий, но звук");
        // крайние отсчёты: модуль у них не помещается в short, но звуком быть не перестают
        assertFalse(VideoAudio.silent(new short[] {0, Short.MIN_VALUE}));
        assertFalse(VideoAudio.silent(new short[] {Short.MAX_VALUE, 0}));
    }
}
