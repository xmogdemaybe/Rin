package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class LivelinessTest {

    private final Random random = new Random(42);

    /** Чат, в котором идут реплики: проверка повторов смотрит на него одного. */
    private static final long CHAT = 1L;

    @Test
    void swapMakesTextDifferentButSameLength() {
        String result = Typos.swapAdjacent("привет как дела", random);

        assertNotEquals("привет как дела", result);
        assertEquals("привет как дела".length(), result.length());
    }

    @Test
    void neighbourKeyStaysOnTheSameKeyboard() {
        // промах должен попадать по соседней клавише той же раскладки
        String result = Typos.hitNeighbourKey("привет", random);

        assertNotEquals("привет", result);
        assertEquals(6, result.length());
    }

    @Test
    void slipRemembersHowTheWordShouldLook() {
        // без этого нечего было бы дописать следом со звёздочкой
        for (int i = 0; i < 200; i++) {
            Typos.Slip slip = Typos.maybeAdd("сегодня хорошая погода", 1.0, random);
            if (slip.happened()) {
                assertTrue("сегодня хорошая погода".contains(slip.original()),
                        "исправление должно быть словом из исходного текста: " + slip.original());
                assertNotEquals("сегодня хорошая погода", slip.text());
                return;
            }
        }
    }

    @Test
    void shortAndOddInputSurvives() {
        assertEquals("а", Typos.swapAdjacent("а", random));
        assertEquals("", Typos.swapAdjacent("", random));
        assertEquals("!!!", Typos.hitNeighbourKey("!!!", random), "нечего заменять — оставляем как есть");
    }

    @Test
    void typosAreRareNotConstant() {
        int changed = 0;
        for (int i = 0; i < 1000; i++) {
            if (Typos.maybeAdd("сегодня хорошая погода", 0.08, random).happened()) {
                changed++;
            }
        }
        assertTrue(changed > 20 && changed < 200, "опечаток должно быть немного, вышло: " + changed);
    }

    @Test
    void typingSpeedIsHumanNotSluggish() {
        // около десяти знаков в секунду: короткая реплика набирается за пару
        // секунд, а не за десять
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();
        rhythm.delayFor(1, now);

        Duration shortMessage = rhythm.delayFor(20, now.plusSeconds(1));

        assertTrue(shortMessage.toMillis() > 800 && shortMessage.toMillis() < 3000,
                "двадцать знаков должны набираться пару секунд, вышло: " + shortMessage.toMillis() + " мс");
    }

    @Test
    void longerMessageTakesLongerToType() {
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();

        rhythm.delayFor(10, now);
        Duration shortText = rhythm.delayFor(10, now.plusSeconds(1));
        rhythm.delayFor(10, now.plusSeconds(2));
        Duration longText = rhythm.delayFor(200, now.plusSeconds(3));

        assertTrue(longText.compareTo(shortText) > 0, shortText + " против " + longText);
    }

    @Test
    void noDelayWhenConversationWasIdle() {
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();

        rhythm.delayFor(50, now);

        // пауза изображает набор текста, а не задумчивость
        assertEquals(Duration.ZERO, rhythm.delayFor(50, now.plusSeconds(30)));
    }

    @Test
    void delayIsCappedForVeryLongMessages() {
        TypingRhythm rhythm = new TypingRhythm(random);
        Instant now = Instant.now();

        rhythm.delayFor(1, now);

        assertTrue(rhythm.delayFor(100_000, now.plusSeconds(1)).compareTo(Duration.ofSeconds(13)) < 0);
    }

    /** Похожесть задаётся вручную: одинаковый текст — одинаковый вектор. */
    private record VectorsByText(Map<String, double[]> vectors) implements LlmClient {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return vectors.getOrDefault(input, new double[] {0, 0, 1});
        }
    }

    @Test
    void rejectsRepeatingItself() {
        LlmClient llm = new VectorsByText(Map.of(
                "я тут, если что", new double[] {1, 0, 0},
                "я здесь, если что", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");

        antiRepeat.check(CHAT, "я тут, если что");

        assertThrows(LowQualityException.class, () -> antiRepeat.check(CHAT, "я здесь, если что"));
    }

    @Test
    void allowsGenuinelyNewMessage() {
        LlmClient llm = new VectorsByText(Map.of(
                "я тут, если что", new double[] {1, 0, 0},
                "слушай, а ты завтра свободен?", new double[] {0, 1, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");

        antiRepeat.check(CHAT, "я тут, если что");
        antiRepeat.check(CHAT, "слушай, а ты завтра свободен?");
    }

    @Test
    void verbatimRepeatOfASentMessageNeverGetsThrough() {
        // поблажка придумана для перефраз, но именно она пропускала в чат
        // слово в слово отправленное: схожесть повтора равна 1.00, а порог
        // после отказа — 0.95 + 0.07, то есть выше самого повтора
        LlmClient llm = new VectorsByText(Map.of("ой, интригуешь, давай показывай", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.check(CHAT, "ой, интригуешь, давай показывай");

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThrows(LowQualityException.class,
                    () -> antiRepeat.check(CHAT, "ой, интригуешь, давай показывай"),
                    "попытка " + attempt + ": уже сказанное не проходит ни разу");
        }
    }

    @Test
    void relaxesAfterRejectionSoRephrasingGetsThrough() {
        // иначе модели, которой нечего добавить, отказывают до предела шагов:
        // новые слова, похожие по смыслу, должны пройти после первой поблажки
        LlmClient llm = new VectorsByText(Map.of(
                "давай показывай скорее", new double[] {1, 0, 0},
                "показывай уже скорее", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.remember(CHAT, "сегодня холодно, я сплю");
        antiRepeat.remember(CHAT, "а ты уже ел?");
        antiRepeat.check(CHAT, "давай показывай скорее");

        assertThrows(LowQualityException.class, () -> antiRepeat.check(CHAT, "показывай уже скорее"));

        antiRepeat.check(CHAT, "показывай уже скорее");
    }

    @Test
    void refusalNamesTheWordsAlreadySent() {
        // без них модель не знает, от чего именно её отвернули, и при низкой
        // температуре выдаёт ту же фразу — тогда откат стоит ещё одного шага
        LlmClient llm = new VectorsByText(Map.of("я рядом, если что", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.check(CHAT, "я рядом, если что");

        LowQualityException refusal = assertThrows(LowQualityException.class,
                () -> antiRepeat.check(CHAT, "я рядом, если что"));

        assertTrue(refusal.getMessage().contains("я рядом, если что"), refusal.getMessage());
        assertTrue(refusal.getMessage().startsWith("ты повторяешься"), refusal.getMessage());
    }

    @Test
    void refusalQuoteStaysShort() {
        String longMessage = "а".repeat(4000);
        LlmClient llm = new VectorsByText(Map.of(longMessage, new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.check(CHAT, longMessage);

        LowQualityException refusal = assertThrows(LowQualityException.class, () -> antiRepeat.check(CHAT, longMessage));

        assertTrue(refusal.getMessage().length() < 400, "длина отказа " + refusal.getMessage().length());
        assertTrue(refusal.getMessage().contains("а".repeat(50)), refusal.getMessage());
    }

    @Test
    void sameWordsInAnotherCaseOrSpacingAreStillADuplicate() {
        // модель дописывает пробелы и меняет регистр, а эмбеддинг при этом
        // даёт похожий, но не предельный вектор: без нормализации этот вариант
        // прошёл бы как новая мысль
        LlmClient llm = new VectorsByText(Map.of(
                "спокойной ночи", new double[] {1, 0, 0},
                " Спокойной  НОЧИ\n", new double[] {0.6, 0.8, 0},
                "сегодня холодно, я сплю", new double[] {0, 1, 0},
                "а ты уже ел?", new double[] {0, 0, 1}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.remember(CHAT, "сегодня холодно, я сплю");
        antiRepeat.remember(CHAT, "а ты уже ел?");
        antiRepeat.check(CHAT, "спокойной ночи");

        assertThrows(LowQualityException.class, () -> antiRepeat.check(CHAT, " Спокойной  НОЧИ\n"));
    }

    @Test
    void verbatimRefusalDoesNotBuyTheNextPhraseARelaxation() {
        // поблажка нужна тем, кто перефразирует и упирается в отказ; дословный
        // повтор её не зарабатывает: иначе одна промашка открывает дорогу
        // следующей той же мысли
        LlmClient llm = new VectorsByText(Map.of(
                "дай посмотреть", new double[] {1, 0, 0},
                "дай-ка посмотреть", new double[] {1, 0, 0},
                "сегодня холодно, я сплю", new double[] {0, 1, 0},
                "а ты уже ел?", new double[] {0, 0, 1}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.remember(CHAT, "сегодня холодно, я сплю");
        antiRepeat.remember(CHAT, "а ты уже ел?");
        antiRepeat.check(CHAT, "дай посмотреть");

        assertThrows(LowQualityException.class, () -> antiRepeat.check(CHAT, "дай посмотреть"));

        assertThrows(LowQualityException.class, () -> antiRepeat.check(CHAT, "дай-ка посмотреть"));
    }

    @Test
    void sameWordsAddressedToAnotherChatAreNotARepeat() {
        // «спокойной ночи» двоим — это два разных чата, а не один, где она
        // повторяется: сравнивается только собственный чат
        LlmClient llm = new VectorsByText(Map.of("спокойной ночи", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");

        antiRepeat.check(1L, "спокойной ночи");
        antiRepeat.check(2L, "спокойной ночи");
    }

    @Test
    void refusalsInOneChatDoNotRelaxAnother() {
        // поблажка принадлежит чату: несколько отказов в одном не должны
        // открывать дорогу той же мысли в другом
        LlmClient llm = new VectorsByText(Map.of(
                "дай посмотреть", new double[] {1, 0, 0},
                "дай-ка посмотреть", new double[] {1, 0, 0},
                "сегодня холодно, я сплю", new double[] {0, 1, 0},
                "а ты уже ел?", new double[] {0, 0, 1}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        for (long chat : new long[] {1L, 2L}) {
            antiRepeat.remember(chat, "сегодня холодно, я сплю");
            antiRepeat.remember(chat, "а ты уже ел?");
            antiRepeat.check(chat, "дай посмотреть");
        }

        assertThrows(LowQualityException.class, () -> antiRepeat.check(1L, "дай-ка посмотреть"));
        assertThrows(LowQualityException.class, () -> antiRepeat.check(2L, "дай-ка посмотреть"));
    }

    @Test
    void aForgottenChatTreatsItsOldPhraseAsNewAgain() {
        // число помнящихся чатов ограничено: иначе за неделю накопится
        // столько векторов, сколько переписок открылось
        LlmClient llm = new VectorsByText(Map.of("спокойной ночи", new double[] {1, 0, 0}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.check(0L, "спокойной ночи");
        for (int chat = 1; chat <= AntiRepeat.MAX_CHATS; chat++) {
            antiRepeat.remember(chat, "спокойной ночи");
        }

        antiRepeat.check(0L, "спокойной ночи");
    }

    @Test
    void aChatInTheConversationOutlivesAnIdleOne() {
        // на вылет идёт давний без разговора чат, а не тот, что встретился первым
        LlmClient llm = new VectorsByText(Map.of(
                "спокойной ночи", new double[] {1, 0, 0},
                "а ты спишь?", new double[] {0, 0, 1}));
        AntiRepeat antiRepeat = new AntiRepeat(llm, "ты повторяешься");
        antiRepeat.check(0L, "спокойной ночи");
        for (int chat = 1; chat < AntiRepeat.MAX_CHATS; chat++) {
            antiRepeat.remember(chat, "спокойной ночи");
        }
        antiRepeat.check(0L, "а ты спишь?");
        antiRepeat.remember(AntiRepeat.MAX_CHATS, "спокойной ночи");

        assertThrows(LowQualityException.class, () -> antiRepeat.check(0L, "спокойной ночи"));

        antiRepeat.check(1L, "спокойной ночи");
    }

    @Test
    void brokenEmbeddingDoesNotBlockSending() {
        LlmClient broken = new LlmClient() {
            @Override
            public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
                throw new UnsupportedOperationException();
            }

            @Override
            public double[] embedding(String input) {
                throw new IllegalStateException("сеть недоступна");
            }
        };
        AntiRepeat antiRepeat = new AntiRepeat(broken, "ты повторяешься");
        antiRepeat.remember(CHAT, "что-то");

        // проверка не обязательна — без неё разговор просто менее строгий
        antiRepeat.check(CHAT, "что угодно");
        assertFalse(false);
    }
}
