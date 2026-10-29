package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

class PendingConversationsTest {

    @TempDir
    Path dir;

    @Test
    void conversationSurvivesUntilTheNetworkIsBack() {
        // сеть отвалилась на остановке — разговор должен дожить до запуска,
        // а не исчезнуть вместе с процессом
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        List<Message> conversation = List.of(
                Message.user("тебе написали"),
                new Message(Message.Role.ASSISTANT, "думаю",
                        List.of(new ToolCall("1", "function", new ToolCall.Function("open", "{}"))), null, null),
                Message.toolResult("1", "<chat>переписка</chat>"));

        pending.keep(conversation, Origin.GENERAL);

        List<Path> waiting = pending.waiting();
        assertEquals(1, waiting.size(), "отложенный разговор должен найтись");

        List<Message> restored = pending.read(waiting.getFirst()).conversation();
        assertEquals(3, restored.size());
        assertEquals("тебе написали", restored.getFirst().content());
        // вызовы инструментов тоже сохраняются: без них пересказ беднее
        assertEquals("open", restored.get(1).toolCallsOrEmpty().getFirst().function().name());
        assertEquals("1", restored.getLast().toolCallId());
    }

    @Test
    void theDumpKeepsWhereTheConversationCameFrom() throws Exception {
        // пересказ делает модель, а её может не быть в момент остановки: без
        // метки в дампе дозаписанный после обрыва разговор стал бы общим, и
        // чужая личка уехала бы в круг случайных людей
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        pending.keep(List.of(Message.user("Серёжа признался в личке")), new Origin(777, Source.PRIVATE_DM));

        Path file = pending.waiting().getFirst();
        PendingConversations.Pending restored = pending.read(file);

        assertEquals(new Origin(777, Source.PRIVATE_DM), restored.origin(),
                "разговор помнит, откуда он, даже после перезапуска процесса");
        assertEquals("Серёжа признался в личке", restored.conversation().getFirst().content());
        assertTrue(Files.readString(file).contains("private_dm"),
                "метка видна глазами: файл остался человекочитаемым");
    }

    @Test
    void anOldDumpStillReadsAsGeneral() throws Exception {
        // прежние дампы — голый список сообщений: метку оттуда брать неоткуда,
        // и при следующем запуске такой разговор читается общим, а не выбрасывается
        Path folder = dir.resolve("pending");
        Files.createDirectories(folder);
        Path file = folder.resolve("1700000000000-old.json");
        Files.writeString(file, """
                [{"role":"user","content":"разговор из старой версии"}]
                """);
        PendingConversations pending = new PendingConversations(folder);

        assertEquals(1, pending.read(file).conversation().size(), "прежний формат всё равно читается");
        assertEquals(Origin.GENERAL, pending.read(file).origin());
    }

    @Test
    void retoldConversationIsNotKeptAround() {
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));
        pending.keep(List.of(Message.user("разговор")), Origin.GENERAL);

        Path file = pending.waiting().getFirst();
        pending.done(file);

        assertFalse(Files.exists(file), "пересказанное не должно пересказываться снова");
        assertTrue(pending.waiting().isEmpty());
    }

    @Test
    void nothingIsWrittenForAnEmptyConversation() {
        PendingConversations pending = new PendingConversations(dir.resolve("pending"));

        pending.keep(List.of(), new Origin(777, Source.PRIVATE_DM));

        assertTrue(pending.waiting().isEmpty());
    }
}
