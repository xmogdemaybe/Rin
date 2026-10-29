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

        String recalled = memory.recall(context("помнишь поездку?"), Audience.STRANGER);

        assertTrue(recalled.contains("мы ездили на Байкал"), recalled);
        assertFalse(recalled.contains("разговор про работу"), "не относящееся к делу подмешивать незачем");
    }

    @Test
    void sameEntryIsNotRecalledTwice(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "мы ездили на Байкал");
        Memory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 4000);

        assertFalse(memory.recall(context("помнишь поездку?"), Audience.STRANGER).isEmpty());
        assertTrue(memory.recall(context("помнишь поездку?"), Audience.STRANGER).isEmpty(),
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

        String recalled = memory.recall(context("помнишь поездку?"), Audience.STRANGER);
        assertFalse(recalled.isEmpty());

        // так воспоминания встают в контекст: перед последним сообщением
        memory.recall(List.of(Message.user(recalled + "помнишь поездку?")), Audience.STRANGER);

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

        assertFalse(memory.recall(context("помнишь поездку?"), Audience.STRANGER).isEmpty());

        assertEquals(1, diary.size(), "воспоминание не убирает запись из дневника");
        memory.forget();
        assertFalse(memory.recall(context("помнишь поездку?"), Audience.STRANGER).isEmpty(),
                "новый разговор помнит то же");
    }

    @Test
    void thresholdDropsWhenNothingMatches(@TempDir Path dir) {
        Diary diary = diaryWith(dir, "совсем про другое");
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {-1, 0, 0}), 4000);

        assertTrue(memory.recall(context("вопрос"), Audience.STRANGER).isEmpty());
        assertTrue(memory.threshold() < 0.5, "планка завышена — её надо опустить: " + memory.threshold());
    }

    @Test
    void thresholdRisesWhenTooMuchIsRecalled(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < 5; i++) {
            diary.save("длинная запись номер " + i + " ".repeat(200), new double[] {1, 0, 0});
        }
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 300);

        memory.recall(context("вопрос"), Audience.STRANGER);

        assertTrue(memory.threshold() >= 0.5, "набралось с избытком — впредь строже: " + memory.threshold());
    }

    @Test
    void respectsLengthLimit(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < 10; i++) {
            diary.save("запись " + i + " ".repeat(100), new double[] {1, 0, 0});
        }
        Memory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 250);

        String recalled = memory.recall(context("вопрос"), Audience.STRANGER);

        assertTrue(recalled.length() < 700, "объём подмешанного должен быть ограничен: " + recalled.length());
    }

    @Test
    void thresholdNeverFallsBelowTheFloor(@TempDir Path dir) {
        // без нижней границы разрозненный дневник утягивает планку в ноль,
        // и в разговор начинает лезть что попало
        Diary diary = diaryWith(dir, "совсем про другое");
        DiaryMemory memory = new DiaryMemory(diary, new FixedEmbedding(new double[] {-1, 0, 0}), 4000, 0.8);

        assertTrue(memory.recall(context("вопрос"), Audience.STRANGER).isEmpty());
        assertTrue(memory.threshold() >= 0.8, "порог просел ниже границы: " + memory.threshold());
    }

    @Test
    void emptyDiaryRecallsNothing(@TempDir Path dir) {
        Memory memory = new DiaryMemory(new Diary(dir), new FixedEmbedding(new double[] {1, 0, 0}), 4000);

        assertEquals("", memory.recall(context("вопрос"), Audience.STRANGER));
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
        assertEquals("", new DiaryMemory(diary, broken, 4000).recall(context("вопрос"), Audience.STRANGER));
    }

    /** Зритель из конкретного разговора с заданным доверием. */
    private static Audience viewer(long chatId, int trust) {
        return new Audience(chatId, trust, false);
    }

    private static DiaryMemory memory(Diary diary) {
        return new DiaryMemory(diary, new FixedEmbedding(new double[] {1, 0, 0}), 4000);
    }

    @Test
    void aStrangerDoesNotGetAnotherPersonsSecrets(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Серёжа признался в чужой личке", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));

        String recalled = memory(diary).recall(context("помнишь Серёжу?"), viewer(222, 0));

        assertEquals("", recalled, "случайный собеседник не должен узнавать чужие лички");
    }

    @Test
    void aClosedEntryDoesNotGainExperience(@TempDir Path dir) {
        // запись отсеяна не потому, что не подошла, а потому, что её нельзя
        // показывать: начислять ей опыт за чужой вопрос — подтасовывать вес
        Diary diary = new Diary(dir);
        diary.save("Серёжа признался в чужой личке", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));

        memory(diary).recall(context("помнишь Серёжу?"), viewer(222, 0));

        Diary reopened = new Diary(dir);
        assertEquals(0, reopened.query(new double[] {1, 0, 0}).getFirst().entry().metadata().usageCount());
    }

    @Test
    void aChatSeesWhatWasToldInIt(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Серёжа признался в этом же разговоре", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));

        String recalled = memory(diary).recall(context("помнишь Серёжу?"), viewer(111, 0));

        assertTrue(recalled.contains("признался"), "то, что сказано в этом же чате, не секрет для него");
    }

    @Test
    void aFriendSeesOtherChatsButNotTheOwnersBunker(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("владелец поверил только мне", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(1, Source.OWNER_DM));
        diary.save("Серёжа признался в чужой личке", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));
        diary.save("общая новость из ленты", new double[] {1, 0, 0}, 0, List.of(), Origin.GENERAL);

        String recalled = memory(diary).recall(context("вопрос"), viewer(222, 3));

        assertTrue(recalled.contains("Серёжа"), recalled);
        assertTrue(recalled.contains("общая новость"), recalled);
        assertFalse(recalled.contains("поверил только мне"),
                "бункер владельца не открывается ни за какое доверие: " + recalled);
    }

    @Test
    void theOwnerSeesEverythingAndIsToldWhereItCameFrom(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("владелец поверил только мне", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(1, Source.OWNER_DM));
        diary.save("Серёжа признался в чужой личке", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));
        diary.save("обсудили в общей группе", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(5, Source.GROUP));
        diary.save("просто общая запись", new double[] {1, 0, 0});

        String recalled = memory(diary).recall(context("вопрос"), new Audience(222, 5, true));

        assertTrue(recalled.contains("поверил только мне"), recalled);
        assertTrue(recalled.contains("[from: your private chat with your person]"), recalled);
        assertTrue(recalled.contains("[from: a private chat with someone else]"), recalled);
        assertTrue(recalled.contains("[from: another chat]"), recalled);
        assertTrue(recalled.contains("просто общая запись"), recalled);
        assertEquals(3, recalled.split("\n\\[from: ", -1).length - 1,
                "атрибуция нужна трём записям, четвёртая и так общая: " + recalled);
    }

    @Test
    void attributionLivesInsideTheBlockAndDiesWithIt(@TempDir Path dir) {
        // внешний тег трогать нельзя: без него подмешанное не вырезается из
        // следующего запроса, и она начинает вспоминать собственные воспоминания
        Diary diary = new Diary(dir);
        diary.save("Серёжа признался в чужой личке", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));
        DiaryMemory memory = memory(diary);

        String recalled = memory.recall(context("помнишь Серёжу?"), viewer(111, 0));

        assertTrue(recalled.contains("[from: this private chat]"), recalled);
        String topic = DiaryMemory.topicOf(List.of(Message.user(recalled + "помнишь Серёжу?")));
        assertFalse(topic.contains("признался"), topic);
        assertFalse(topic.contains("[from:"), topic);
        assertTrue(topic.contains("помнишь Серёжу?"), topic);
    }
}
