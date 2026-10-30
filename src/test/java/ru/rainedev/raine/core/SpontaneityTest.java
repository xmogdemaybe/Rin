package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.Origin;
import ru.rainedev.raine.memory.Source;

class SpontaneityTest {

    private static final LlmClient UNUSED = new LlmClient() {
        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1};
        }
    };

    private static NotificationLoop loop() {
        return new NotificationLoop(UNUSED, () -> "промпт", new Toolbox(), 40_000);
    }

    private static ChatResponse toolCall(String name) {
        Message message = new Message(Message.Role.ASSISTANT, "", List.of(
                new ToolCall("call_" + name, "function", new ToolCall.Function(name, "{}"))), null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                ChatResponse.Usage.EMPTY);
    }

    private static LlmClient scripted(ChatResponse... responses) {
        Deque<ChatResponse> queue = new ArrayDeque<>(List.of(responses));
        return new LlmClient() {
            @Override
            public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
                return queue.removeFirst();
            }

            @Override
            public double[] embedding(String input) {
                return new double[] {1};
            }
        };
    }

    @Test
    void anImpulseOpensTheOwnersChatAtStepZero(@TempDir Path dir) {
        // разведка «кто это» съедала шаги: чат владельца открывается сам, и
        // действие стоит столько же, сколько молчание
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        Tool open = Tool.named("open").describedAs("open chat")
                .buildContextual((arguments, addTool) -> "CHAT OPENED");

        try (Spontaneity spontaneity =
                     new Spontaneity(loop(), diary, new Toolbox(open), 777, new Random(1))) {
            Notification impulse = spontaneity.buildImpulse();

            assertEquals(777, impulse.chatId());
            assertEquals(777, impulse.personId());
            assertTrue(impulse.initiative(), "цикл должен знать, что это порыв");
            assertTrue(impulse.ownText(), "текст порыва не должен пропасть под историей чата");
            assertEquals(Set.of("open"), impulse.tools().names(),
                    "иначе чат не откроется сам и отправка останется за трёмя шагами");
        }
    }

    @Test
    void theImpulseKnowsHowTheLastConversationEnded(@TempDir Path dir) {
        // слепой порыв писал бы снова через минуту после разговора или среди ночи
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        Tool send = Tool.named("send_telegram_message").describedAs("send").mutating()
                .build(arguments -> "ok");
        NotificationLoop loop = new NotificationLoop(
                scripted(toolCall("send_telegram_message"), toolCall("wait")),
                () -> "промпт", new Toolbox(), 40_000);
        loop.process(new Notification("тебе написали", new Toolbox(send)));

        try (Spontaneity spontaneity =
                     new Spontaneity(loop, diary, new Toolbox(), 0, new Random(1))) {
            String prompt = spontaneity.buildPrompt();

            assertTrue(prompt.contains("<last_conversation>"), prompt);
            assertTrue(prompt.contains("send_telegram_message"), prompt);
            assertTrue(prompt.contains("just now"), prompt);
        }
    }

    @Test
    void anImpulseTellsHerToUseWaitConsciously(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});

        try (Spontaneity spontaneity =
                     new Spontaneity(loop(), diary, new Toolbox(), 0, new Random(1))) {
            String prompt = spontaneity.buildPrompt();

            assertTrue(prompt.contains("#wait"), prompt);
            assertTrue(prompt.contains("real choice"), "молчание — не побег от тулз: " + prompt);
            assertFalse(prompt.contains("Act proactively"), prompt);
        }
    }

    @Test
    void asleepSheHasNoImpulses(@TempDir Path dir) {
        // спящему человеку ничего не приходит в голову: ни разговоров,
        // ни записей в память, ни строчек в журнале
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), 0, new Random(1))) {
            spontaneity.asleepWhen(() -> true);
            spontaneity.actNow();

            assertEquals(0, loop.queued(), "во сне порывов быть не должно");
        }
    }

    @Test
    void awakeSheSometimesWritesFirst(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), 0, new Random(1))) {
            int impulses = 0;
            for (int attempt = 0; attempt < 40; attempt++) {
                spontaneity.actNow();
                impulses = loop.queued();
                if (impulses > 0) {
                    break;
                }
            }
            assertTrue(impulses > 0, "бодрствуя, она хоть иногда пишет сама");
        }
    }

    @Test
    void anImpulseNeverUsesASecret(@TempDir Path dir) {
        // порыв не привязан к чату: он уходит в случайный разговор, и секрет
        // лички — даже владелецской — не должен становиться поводом написать
        Diary diary = new Diary(dir);
        diary.save("общая новость из ленты", new double[] {1, 0, 0});
        diary.save("владелец поверил только мне", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(1, Source.OWNER_DM));
        diary.save("Серёжа признался в чужой личке", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(111, Source.PRIVATE_DM));
        diary.save("обсудили в общей группе", new double[] {1, 0, 0}, 0, List.of(),
                new Origin(5, Source.GROUP));

        try (Spontaneity spontaneity = new Spontaneity(loop(), diary, new Toolbox(), 0, new Random(1))) {
            for (int attempt = 0; attempt < 30; attempt++) {
                String prompt = spontaneity.buildPrompt();
                assertTrue(prompt.contains("общая новость"), "поводом служит общая запись: " + prompt);
                assertFalse(prompt.contains("поверил только мне"), prompt);
                assertFalse(prompt.contains("признался"), prompt);
                assertFalse(prompt.contains("обсудили"), prompt);
            }
        }
    }

    @Test
    void impulseDoesNotConsumeTheDiary(@TempDir Path dir) {
        // повод вспомнить не стирает само воспоминание: иначе после нескольких
        // порывов в дневнике оставалось бы всё меньше того, что видно
        Diary diary = new Diary(dir);
        diary.save("что-то из прошлого", new double[] {1, 0, 0});
        NotificationLoop loop = loop();

        try (Spontaneity spontaneity = new Spontaneity(loop, diary, new Toolbox(), 0, new Random(1))) {
            int impulses = 0;
            for (int attempt = 0; attempt < 40 && impulses == 0; attempt++) {
                spontaneity.actNow();
                impulses = loop.queued();
            }
            assertTrue(impulses > 0, "порыв был");
            assertEquals(1, diary.size(), "дневник остался цел");
        }

        assertEquals(1, diary.query(new double[0]).size(), "и запись по-прежнему находится");
    }
}
