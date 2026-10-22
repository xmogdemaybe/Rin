package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class DiaryMemoryTest {

    /** Отдаёт заранее заданный вектор — поиск проверяем без обращения к сети. */
    private record FixedEmbedding(double[] vector) implements LlmClient {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return vector;
        }
    }

    /**
     * Близость нормирована: совпадение — единица, перпендикуляр — половина,
     * противоположность — ноль. Поэтому «не относящееся к делу» задаётся
     * противоположным вектором, а не перпендикулярным.
     */
    private static Diary diaryWith(Path dir, String... bodies) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < bodies.length; i++) {
            diary.save(bodies[i], i == 0 ? new double[] {1, 0, 0} : new double[] {-1, 0, 0});
        }
        return diary;
    }

    private static List<Message> context(String text) {
        return List.of(Message.user(text));
    }

    @Test
    void recallsRelatedEntry(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "мы ездили на Байкал", "разговор про работу");
        Memory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 4000);

        String recalled = memory.recall(context("помнишь поездку?"));

        assertTrue(recalled.contains("мы ездили на Байкал"), recalled);
        assertFalse(recalled.contains("разговор про работу"), "не относящееся к делу подмешивать незачем");
    }

    @Test
    void sameEntryIsNotRecalledTwice(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "мы ездили на Байкал");
        Memory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 4000);

        assertFalse(memory.recall(context("помнишь поездку?")).isEmpty());
        assertTrue(memory.recall(context("помнишь поездку?")).isEmpty(),
                "запись уже в контексте — второй раз её тянуть некуда");
    }

    /** Записывает, о чём её спросили, — так видно, из чего построен запрос к памяти. */
    private static final class RecordingEmbedding implements LlmClient {
        private final double[] vector;
        final List<String> topics = new java.util.ArrayList<>();

        RecordingEmbedding(double[] vector) {
            this.vector = vector;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            topics.add(input);
            return vector;
        }
    }

    @Test
    void nextRecallIsNotBuiltFromThePreviousOne(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "мы ездили на Байкал");
        RecordingEmbedding embedding = new RecordingEmbedding(new double[] {1, 0, 0});
        DiaryMemory memory = new DiaryMemory(diary, embedding, 4000);

        String recalled = memory.recall(context("помнишь поездку?"));
        assertFalse(recalled.isEmpty());

        // так воспоминания встают в контекст: перед последним сообщением
        memory.recall(List.of(Message.user(recalled + "помнишь поездку?")));

        assertEquals(2, embedding.topics.size(), embedding.topics.toString());
        assertFalse(embedding.topics.getLast().contains("Байкал"),
                "иначе она вспоминает то, что относится к уже вспомненному: " + embedding.topics.getLast());
        assertTrue(embedding.topics.getLast().contains("помнишь поездку?"), embedding.topics.getLast());
    }

    @Test
    void recalledEntryStaysInTheDiary(@TempDir Path dir) {
        // подмешанная запись не исчезает из памяти: новый разговор вспомнит её
        // снова, а не уткнётся в пустой дневник до самой ночи
        Diary diary = diaryWith(dir, "мы ездили на Байкал");
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 4000);

        assertFalse(memory.recall(context("помнишь поездку?")).isEmpty());

        assertEquals(1, diary.size(), "воспоминание не убирает запись из дневника");
        memory.forget();
        assertFalse(memory.recall(context("помнишь поездку?")).isEmpty(), "новый разговор помнит то же");
    }

    @Test
    void thresholdDropsWhenNothingMatches(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "совсем про другое");
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {-1, 0, 0}), 4000);

        assertTrue(memory.recall(context("вопрос")).isEmpty());
        assertTrue(memory.threshold() < 0.5, "планка завышена — её надо опустить: " + memory.threshold());
    }

    @Test
    void thresholdRisesWhenTooMuchIsRecalled(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < 5; i++) {
            diary.save("длинная запись номер " + i + " ".repeat(200), new double[] {1, 0, 0});
        }
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 300);

        memory.recall(context("вопрос"));

        assertTrue(memory.threshold() >= 0.5, "набралось с избытком — впредь строже: " + memory.threshold());
    }

    @Test
    void respectsLengthLimit(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < 10; i++) {
            diary.save("запись " + i + " ".repeat(100), new double[] {1, 0, 0});
        }
        Memory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 250);

        String recalled = memory.recall(context("вопрос"));

        assertTrue(recalled.length() < 700, "объём подмешанного должен быть ограничен: " + recalled.length());
    }

    @Test
    void thresholdNeverFallsBelowTheFloor(@TempDir Path dir) {
        // без нижней границы разрозненный дневник утягивает планку в ноль,
        // и в разговор начинает лезть что попало
        Diary diary = diaryWith(dir, "совсем про другое");
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {-1, 0, 0}), 4000, 0.8);

        assertTrue(memory.recall(context("вопрос")).isEmpty());
        assertTrue(memory.threshold() >= 0.8, "порог просел ниже границы: " + memory.threshold());
    }

    @Test
    void emptyDiaryRecallsNothing(@TempDir Path dir) {
        Memory memory = new DiaryMemory(new Diary(dir), new FixedEmbedding(new double[] {1, 0, 0}), 4000);

        assertEquals("", memory.recall(context("вопрос")));
    }

    @Test
    void failedEmbeddingDoesNotBreakConversation(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "мы ездили на Байкал");
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

        // без воспоминаний разговор беднее, но продолжается
        assertEquals("", new DiaryMemory(diary, broken, 4000).recall(context("вопрос")));
    }
}
