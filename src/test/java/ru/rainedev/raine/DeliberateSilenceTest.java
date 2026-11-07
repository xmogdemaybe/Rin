package ru.rainedev.raine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Notification;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.LlmFailure;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

/**
 * «Проигнорила — значит проигнорила», а не «забыла ответить».
 * <p>
 * Тихий ход — такое же поведение, как и ответ: ничего не отправлено, ход закрыт
 * #wait, чат остаётся прочитанным, и догон при следующем запуске его не поднимает
 * (фильтр — {@link RaineApp#awaitsAnswer}). Отметку «не прочитано» цикл просит
 * снять только когда ход сорвался: там тишина вышла аварией, а не решением, и
 * сообщение иначе пропало бы молча.
 */
class DeliberateSilenceTest {

    /** Чат глазами Telegram: открытие снимает счётчик само, флаг отметки — нет. */
    private static final class TelegramChat {
        private final TdApi.Chat chat = new TdApi.Chat();
        private boolean opened;

        TelegramChat() {
            chat.unreadCount = 1;
            TdApi.Message last = new TdApi.Message();
            last.id = 7;
            // последнее слово за ним: ответа она так и не дала
            last.isOutgoing = false;
            chat.lastMessage = last;
        }

        /** TdApi.OpenChat: прочитанное перестаёт быть непрочитанным без отдельной просьбы. */
        private void read() {
            opened = true;
            chat.unreadCount = 0;
        }

        /** ToggleChatIsMarkedAsUnread(true) — то, ради чего существует backToUnread. */
        private void markUnread() {
            chat.isMarkedAsUnread = true;
        }

        private boolean awaitsAnswer() {
            return RaineApp.awaitsAnswer(chat);
        }
    }

    /** Модель по плану: какой шаг — такой ответ, либо обрыв. */
    private static final class Scripted implements LlmClient {
        private final IntFunction<ChatResponse> plan;
        private final AtomicInteger calls = new AtomicInteger();

        Scripted(IntFunction<ChatResponse> plan) {
            this.plan = plan;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            return plan.apply(calls.getAndIncrement());
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static ChatResponse stepCalling(String tool) {
        ToolCall call = new ToolCall("call_" + tool, "function", new ToolCall.Function(tool, "{}"));
        Message message = new Message(Message.Role.ASSISTANT, "", List.of(call), null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                new ChatResponse.Usage(10, 0, 10));
    }

    /** Открытие чата из набора постоянных инструментов: входящие сами не открываются. */
    private static Tool openChat(TelegramChat chat) {
        return Tool.named("open").describedAs("opens the chat").buildContextual((arguments, addTool) -> {
            chat.read();
            return "CHAT OPENED";
        });
    }

    private static NotificationLoop loop(LlmClient llm, TelegramChat chat, AtomicInteger marks) {
        NotificationLoop loop =
                new NotificationLoop(llm, () -> "системный промпт", new Toolbox(openChat(chat)), 40_000);
        loop.backToUnread(notification -> {
            marks.incrementAndGet();
            chat.markUnread();
        });
        return loop;
    }

    /** Чужое сообщение: чат и человек известны, в файле доверия его нет. */
    private static Notification stranger() {
        return new Notification("Неизвестный написал тебе (chat_id = 900)").about(900).from(900);
    }

    @Test
    void aQuietWaitLeavesTheChatReadSoTheCatchUpSkipsIt() {
        TelegramChat chat = new TelegramChat();
        AtomicInteger marks = new AtomicInteger();
        // прочитать и смолчать: текста в уведомлении нет, решение принято по сообщению
        Scripted llm = new Scripted(step -> stepCalling(step == 0 ? "open" : "wait"));

        loop(llm, chat, marks).process(stranger());

        assertTrue(chat.opened, "молчание было осознанным — чат прочитан");
        assertEquals(0, marks.get(), "тихий ход не авария: снимать отметку незачем");
        assertFalse(chat.awaitsAnswer(), "проигнорированный чат не должен всплывать при догоне");
    }

    @Test
    void aBrokenTurnGivesTheSameChatBackToTheCatchUp() throws Exception {
        // тот же чат, но ход оборвался на середине: молчание вынужденное, и чат
        // обязан дождаться — иначе проверка выше прошла бы пустой
        TelegramChat chat = new TelegramChat();
        AtomicInteger marks = new AtomicInteger();
        Scripted llm = new Scripted(step -> {
            if (step == 0) {
                return stepCalling("open");
            }
            throw new LlmFailure(402, "Ошибка эндпоинта (402): не хватает баланса");
        });

        NotificationLoop loop = loop(llm, chat, marks);
        Thread worker = Thread.ofVirtual().start(loop::run);
        loop.submit(stranger());
        for (int waited = 0; waited < 100 && marks.get() == 0; waited++) {
            Thread.sleep(20);
        }
        worker.interrupt();
        worker.join(2000);

        assertEquals(1, marks.get(), "сорванный ход обязан один раз попросить вернуть чат в непрочитанные");
        assertTrue(chat.awaitsAnswer(), "вынужденное молчание догон обязан найти заново");
    }
}
