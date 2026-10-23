package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Режим только чтения держит её на правах, а не на честном слове: пока действие
 * в наборе, модель тратит шаг на поступок, который всё равно никуда не уйдёт.
 * Поэтому в этом режиме действия не предлагаются вовсе — как выключенная
 * настройкой камера или выкладывание историй.
 */
class ReadOnlyToolboxTest {

    @Test
    void actionsAreNotOfferedInReadOnly() {
        Toolbox tools = new Toolbox().actionsAllowed(false)
                .add(Tool.named("send").describedAs("").mutating().build(arguments -> "sent"))
                .add(Tool.simple("look", "", arguments -> "read"));

        assertFalse(tools.names().contains("send"), "действия в наборе быть не должно: " + tools.names());
        assertFalse(offered(tools.asJson()).contains("send"));
        assertTrue(offered(tools.asJson()).contains("look"), "читать ей никто не мешает");
    }

    @Test
    void aGuessedActionDoesNotRunEvenIfTheModelAsksForIt() {
        // название можно и выдумать: выполнение сверяется с тем же набором,
        // из которого действие уже ушло
        StringBuilder ran = new StringBuilder();
        Toolbox tools = new Toolbox().actionsAllowed(false)
                .add(Tool.named("send").describedAs("").mutating().build(arguments -> {
                    ran.append("сообщение ушло");
                    return "sent";
                }));

        List<Message> results = tools.invoke(List.of(call("send")));

        assertEquals("", ran.toString(), "действие не должно было выполниться");
        assertTrue(results.get(0).content().contains("недоступен"), results.get(0).content());
    }

    @Test
    void theSameActionIsOfferedWhileActionsAreAllowed() {
        Toolbox tools = new Toolbox().add(Tool.named("send").describedAs("").mutating().build(a -> "sent"));

        assertTrue(tools.names().contains("send"), "обычный режим ничего не теряет");
        assertTrue(offered(tools.asJson()).contains("send"));
    }

    @Test
    void switchingTheRightOffClearsWhatWasAlreadyPutIn() {
        // порядок вызовов не должен решать, что увидит модель: набор собирают
        // заранее, а право снимают потом
        Toolbox tools = new Toolbox()
                .add(Tool.named("send").describedAs("").mutating().build(a -> "sent"))
                .add(Tool.simple("look", "", a -> "read"));
        assertTrue(tools.names().contains("send"));

        tools.actionsAllowed(false);

        assertFalse(tools.names().contains("send"));
        assertTrue(tools.names().contains("look"));
    }

    @Test
    void openingAChatDoesNotBringActionsBack() {
        // чат открывается инструментом, который права не требует, и по ходу
        // добавляет отправку: вернётся она на следующем шаге — и запрет
        // держится только на слове
        StringBuilder ran = new StringBuilder();
        List<List<String>> seen = new ArrayList<>();

        Toolbox always = new Toolbox().actionsAllowed(false);
        Toolbox ofThisChat = new Toolbox(Tool.named("open").describedAs("")
                .buildContextual((arguments, addTool) -> {
                    addTool.accept(Tool.named("send").describedAs("").mutating().build(ignored -> {
                        ran.append("сообщение ушло");
                        return "sent";
                    }));
                    return "чат открыт";
                }));

        ScriptedLlm llm = new ScriptedLlm(seen);
        llm.willRespond(withToolCalls(List.of(call("open"))));
        llm.willRespond(withToolCalls(List.of(call("send"))));
        llm.willRespond(finishing());

        NotificationLoop loop = new NotificationLoop(llm, () -> "промпт", always, 40_000);
        loop.process(new Notification("тебе написали", ofThisChat));

        assertEquals("", ran.toString(), "отправку она сделать не могла");
        assertTrue(seen.size() >= 2, "шагов должно быть минимум два: " + seen);
        assertFalse(seen.get(1).contains("send"), "на втором шаге отправки в наборе не было: " + seen.get(1));
        assertTrue(loop.context().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("недоступен")),
                "модель должна увидеть, что действия нет, а не молча получить пустоту");
    }

    private static List<String> offered(JsonNode tools) {
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("function").path("name").asText()));
        return names;
    }

    private static ToolCall call(String name) {
        return new ToolCall("call_" + name, "function", new ToolCall.Function(name, "{}"));
    }

    /** Модель по сценарию; заодно запоминает, что ей предлагали на каждом шаге. */
    private static final class ScriptedLlm implements LlmClient {
        private final List<List<String>> seen;
        private final List<ChatResponse> scripted = new ArrayList<>();
        private int index;

        ScriptedLlm(List<List<String>> seen) {
            this.seen = seen;
        }

        void willRespond(ChatResponse response) {
            scripted.add(response);
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            seen.add(offered(tools));
            ChatResponse next = index < scripted.size() ? scripted.get(index) : finishing();
            index++;
            return next;
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static ChatResponse withToolCalls(List<ToolCall> calls) {
        Message message = new Message(Message.Role.ASSISTANT, "", calls, null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                new ChatResponse.Usage(10, 0, 10));
    }

    private static ChatResponse finishing() {
        return withToolCalls(List.of(call("wait")));
    }
}
