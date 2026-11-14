package ru.rainedev.raine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryWriter;
import ru.rainedev.raine.memory.PendingConversations;

/**
 * Очередь отложенных разговоров: что пересказывается, а что должно убраться с
 * дороги. Один невозвратимый разговор стоил всем остальным памяти: он стоял
 * первым по давности, модель браковала его фильтр-политикой, и цикл выходил из
 * разбора очереди, так и не дойдя до следующих.
 */
class PendingQueueTest {

    @TempDir
    Path dir;

    /** Первый разговор забракован фильтр-политикой, второй пересказывается. */
    private static final class FilterThenAnswer implements LlmClient {
        final List<String> asked = new ArrayList<>();
        int calls;
        RuntimeException breaks;

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            calls++;
            asked.add(history.toString());
            if (breaks != null && calls == 1) {
                throw breaks;
            }
            String text = calls == 1 ? "" : "Вечером обсудили поездку в октябре и билеты.";
            String reason = calls == 1 ? "content_filter: PROHIBITED_CONTENT" : "stop";
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0,
                    new Message(Message.Role.ASSISTANT, text, List.of(), null, null), reason)),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            int hash = input.hashCode();
            return new double[] {(hash & 0xFF) / 255d, ((hash >> 8) & 0xFF) / 255d, ((hash >> 16) & 0xFF) / 255d};
        }
    }

    /** Дамп с явным именем: порядок очереди определяется давностью в префиксе. */
    private Path dump(String name, String words) throws IOException {
        Path folder = dir.resolve("pending");
        Files.createDirectories(folder);
        Path file = folder.resolve(name);
        Files.writeString(file, """
                {"source":"owner_dm","source_chat":5,
                 "conversation":[{"role":"user","content":"%s"}]}
                """.formatted(words));
        return file;
    }

    @Test
    void aRefusedConversationIsPutAsideAndTheQueueGoesOn() throws IOException {
        Path refused = dump("1700000000000-refused.json", "разговор, который фильтр не пропускает");
        Path retellable = dump("1700000001000-live.json", "обсудили поездку в октябре");
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        FilterThenAnswer llm = new FilterThenAnswer();
        DiaryWriter writer = new DiaryWriter(new Diary(dir.resolve("diary")), llm, "запиши дневник", 0.97);

        RaineApp.rememberPending(pending, writer, "промпт");

        assertFalse(pending.waiting().contains(refused), "отказанный разговор больше не стоит в очереди");
        assertFalse(pending.waiting().contains(retellable), "второй пересказан и потому убран");
        assertTrue(pending.waiting().isEmpty(), "больше пересказывать нечего");
        assertEquals(2, llm.calls, "по одному запросу на разговор, без повторов");

        Path aside = dir.resolve("pending").resolve("quarantine").resolve("1700000000000-refused.json");
        assertTrue(Files.exists(aside), "файл остаётся на диске: данные не выбрасываются");
        assertTrue(Files.readString(aside).contains("фильтр не пропускает"), "и остаётся читаемым");
        assertEquals(1, new Diary(dir.resolve("diary")).query(new double[0]).size(),
                "очередь дошла до второго разговора");
    }

    @Test
    void aLostConnectionStillLeavesTheRestOfTheQueueForTheNextStart() throws IOException {
        dump("1700000000000-first.json", "разговор до обрыва");
        Path second = dump("1700000001000-second.json", "разговор после обрыва");
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        FilterThenAnswer llm = new FilterThenAnswer();
        llm.breaks = new IllegalStateException("Нет связи с эндпоинтом");
        DiaryWriter writer = new DiaryWriter(new Diary(dir.resolve("diary")), llm, "запиши дневник", 0.97);

        RaineApp.rememberPending(pending, writer, "промпт");

        assertEquals(2, pending.waiting().size(), "без сети пересказ откладывается целиком");
        assertTrue(Files.exists(second), "следующий разговор не тронут — он дождётся связи");
        assertEquals(1, llm.calls, "долбить отказавший эндпоинт очередь не заставляет");
    }

    @Test
    void quarantineIsInvisibleToTheQueueAndToUnanswered() throws IOException {
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        Path file = dump("1700000000000-refused.json", "разговор, который не пересказать");

        pending.quarantine(file);

        assertEquals(List.of(), pending.waiting(), "из очереди карантин выпадает");
        assertTrue(Files.exists(dir.resolve("pending").resolve("quarantine")
                .resolve("1700000000000-refused.json")));
        assertEquals(List.of(), pending.unanswered(), "и неотвеченным он не считается");
    }
}
