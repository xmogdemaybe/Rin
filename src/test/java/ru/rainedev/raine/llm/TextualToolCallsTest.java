package ru.rainedev.raine.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Модель иногда пишет вызов инструмента текстом, а не в tool_calls. Такой
 * ответ раньше считался болтовнёй и выбрасывался вместе с решением, которое
 * в нём было.
 */
class TextualToolCallsTest {

    /** Точная строка из лога 30 октября: прощальное сообщение так и не ушло. */
    private static final String FROM_LOG = """
            Пора спать, уже поздно. Прощальное сообщение лучше отправить сейчас.
            <call:default_api:send_telegram_message{text:Я спать собираюсь уже, соня. Споки ноки, xmogde >_< <3}></call:default_api:send_telegram_message>""";

    /** Точная строка из лога 22:00:32: вызовы написаны текстом, шаг сгорел впустую. */
    private static final String FROM_LOG_HASH = """
            - thought: xmogde наконец-то доделал то, над чем работал.
            - intention: ответить с любопытством и попросить показать скорее.

            tool call #react_with_emoji{emoji:❤️,message_id:394264576}
            tool call #send_telegram_message{text:очень хочу! >_< показывай скорее, что за красоту ты там натворил :3}""";

    @Test
    void readsTheCallThatWasWrittenAsText() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(FROM_LOG);

        assertEquals(1, parsed.calls().size(), "вызов должен найтись: " + parsed.calls());
        ToolCall call = parsed.calls().getFirst();
        assertEquals("send_telegram_message", call.name(), "префикс пространства имён отбрасывается");
        assertEquals("{\"text\":\"Я спать собираюсь уже, соня. Споки ноки, xmogde >_< <3\"}",
                call.arguments(), "запятые внутри текста — часть текста, а не новый аргумент");
    }

    @Test
    void theServiceTagIsCutOutButTheThoughtsStay() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(FROM_LOG);

        assertFalse(parsed.text().contains("call:"), "служебный тег не должен остаться в тексте: " + parsed.text());
        assertTrue(parsed.text().contains("Пора спать"), "человеческий текст остаётся: " + parsed.text());
    }

    @Test
    void aTagWithoutAnyOtherTextLeavesNothingToSay() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "<call:wait{}></call:wait>");

        assertEquals("{}", parsed.calls().getFirst().arguments());
        assertTrue(parsed.text().isEmpty(), "видимого текста не осталось: " + parsed.text());
    }

    @Test
    void properJsonArgumentsSurviveWhole() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "<call:schedule{\"at\":\"22:30\",\"note\":\"спать\",\"nested\":{\"a\":1}}>");

        assertEquals(1, parsed.calls().size());
        String arguments = parsed.calls().getFirst().arguments();
        assertTrue(arguments.contains("\"at\":\"22:30\""), arguments);
        assertTrue(arguments.contains("\"nested\":{\"a\":1}"), arguments);
    }

    @Test
    void looseArgumentsKnowNumbersAndBooleans() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "<call:remind{count:3, silent:true, nick:null, name:'кот'}>");

        assertEquals("{\"count\":3,\"silent\":true,\"nick\":null,\"name\":\"кот\"}",
                parsed.calls().getFirst().arguments());
    }

    @Test
    void aCommaInsideAValueIsNotASeparator() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "<call:send_telegram_message{text:привет, соня, как дела}>");

        assertEquals("{\"text\":\"привет, соня, как дела\"}", parsed.calls().getFirst().arguments());
    }

    @Test
    void plainTextStaysPlain() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse("Просто ответ без всяких вызовов.");

        assertTrue(parsed.calls().isEmpty());
        assertEquals("Просто ответ без всяких вызовов.", parsed.text());
    }

    @Test
    void aBrokenTagIsNotGuessedAt() {
        // без закрывающей скобки это не вызов, а испорченный текст: догадка
        // отправила бы собеседнику обрывок
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse("<call:send_telegram_message{text:привет");

        assertTrue(parsed.calls().isEmpty(), "битый тег не должен стать вызовом");
        assertTrue(parsed.text().contains("привет"), "и текст не должен пропасть");
        assertTrue(parsed.text().startsWith("<call:"), "битый тег остаётся как был: " + parsed.text());
    }

    @Test
    void aBrokenTagDoesNotEatTheCallAfterIt() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "<call:не имя{обрывок} и <call:wait{}>");

        assertEquals(1, parsed.calls().size(), "рабочий вызов после битого должен найдётся");
        assertEquals("wait", parsed.calls().getFirst().name());
        assertTrue(parsed.text().contains("не имя"), "битый кусок остаётся текстом: " + parsed.text());
    }

    @Test
    void severalCallsInOneMessageAreAllKept() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "Отвечу и подожду.\n<call:send_telegram_message{text:раз}>\n<call:wait{}>");

        assertEquals(List.of("send_telegram_message", "wait"),
                parsed.calls().stream().map(ToolCall::name).toList());
        assertEquals("Отвечу и подожду.", parsed.text());
    }

    @Test
    void anExistingToolCallIsNotDuplicatedByText() {
        Message message = new Message(Message.Role.ASSISTANT,
                "<call:wait{}>",
                List.of(new ToolCall("real-1", "function", new ToolCall.Function("wait", "{}"))), null, null);
        ChatResponse response = new ChatResponse("id", "model",
                List.of(new ChatResponse.Choice(0, message, "tool_calls")), ChatResponse.Usage.EMPTY);

        ChatResponse repaired = TextualToolCalls.repair(response);

        assertEquals(1, repaired.toolCalls().size(), "настоящий вызов текст не удваивает");
        assertEquals("real-1", repaired.toolCalls().getFirst().id());
    }

    @Test
    void repairFillsAnEmptyToolCallsListFromTheText() {
        Message message = new Message(Message.Role.ASSISTANT, FROM_LOG, List.of(), null, null);
        ChatResponse response = new ChatResponse("id", "model",
                List.of(new ChatResponse.Choice(0, message, "stop")), ChatResponse.Usage.EMPTY);

        ChatResponse repaired = TextualToolCalls.repair(response);

        assertEquals(1, repaired.toolCalls().size());
        assertEquals("send_telegram_message", repaired.toolCalls().getFirst().name());
        assertFalse(repaired.text().contains("call:"), "тег вырезан из текста ответа: " + repaired.text());
        assertEquals(1, repaired.firstMessage().orElseThrow().toolCallsOrEmpty().size(),
                "исправленное сообщение уйдёт в контекст вместе с вызовом");
    }

    @Test
    void withoutToolsTheTextIsLeftAlone() {
        // дневник и пересказы зовут модель без инструментов: вырезать оттуда
        // тег значит испортить чужой ответ, которому вызов и не нужен
        Message message = new Message(Message.Role.ASSISTANT, FROM_LOG, null, null, null);
        ChatResponse response = new ChatResponse("id", "model",
                List.of(new ChatResponse.Choice(0, message, "stop")), ChatResponse.Usage.EMPTY);

        ChatResponse repaired = TextualToolCalls.repair(response, false);

        assertTrue(repaired.toolCalls().isEmpty());
        assertTrue(repaired.text().contains("call:"), "текст остался как был: " + repaired.text());
    }

    @Test
    void contentWithoutCallsIsLeftUntouched() {
        Message message = new Message(Message.Role.ASSISTANT, "просто мысли", null, null, null);
        ChatResponse response = new ChatResponse("id", "model",
                List.of(new ChatResponse.Choice(0, message, "stop")), ChatResponse.Usage.EMPTY);

        ChatResponse repaired = TextualToolCalls.repair(response);

        assertTrue(repaired.toolCalls().isEmpty());
        assertEquals("просто мысли", repaired.text());
    }

    @Test
    void readsCallsWrittenWithHashPrefix() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(FROM_LOG_HASH);

        assertEquals(List.of("react_with_emoji", "send_telegram_message"),
                parsed.calls().stream().map(ToolCall::name).toList(), "оба вызова из строки находятся");
        assertEquals("{\"emoji\":\"❤️\",\"message_id\":394264576}", parsed.calls().getFirst().arguments());
        assertEquals("{\"text\":\"очень хочу! >_< показывай скорее, что за красоту ты там натворил :3\"}",
                parsed.calls().get(1).arguments(), "двоеточие внутри текста не режет аргумент");
    }

    @Test
    void hashCallLinesAreCutAndTheThoughtsStay() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(FROM_LOG_HASH);

        assertFalse(parsed.text().contains("tool call"), "строки вызова вырезаны: " + parsed.text());
        assertTrue(parsed.text().contains("- thought:"), "мысли остаются на месте: " + parsed.text());
    }

    @Test
    void readsTheExampleFormWithParenthesesAndQuotes() {
        // так вызовы выглядят в примерах характера — модель копирует именно их
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "tool call #send_telegram_message({\"text\":\"Иди сюда, обниму\"})");

        assertEquals(1, parsed.calls().size());
        assertEquals("send_telegram_message", parsed.calls().getFirst().name());
        assertEquals("{\"text\":\"Иди сюда, обниму\"}", parsed.calls().getFirst().arguments());
        assertTrue(parsed.text().isEmpty(), "скобка после аргументов тоже съедена: " + parsed.text());
    }

    @Test
    void aHashCallWithoutArgumentsGetsAnEmptyObject() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse("tool call #wait({})");

        assertEquals("{}", parsed.calls().getFirst().arguments());
    }

    @Test
    void aHashCallMentionedMidSentenceIsNotACall() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "не буду писать tool call #wait({}) посреди строки");

        assertTrue(parsed.calls().isEmpty(), "вызовом считается только строка, начинающаяся с вызова");
        assertEquals("не буду писать tool call #wait({}) посреди строки", parsed.text());
    }

    @Test
    void aBrokenHashCallIsNotGuessedAt() {
        TextualToolCalls.Parsed parsed = TextualToolCalls.parse(
                "tool call #send_telegram_message({\"text\":\"привет");

        assertTrue(parsed.calls().isEmpty(), "без закрывающей скобки это не вызов");
        assertTrue(parsed.text().contains("привет"), "текст не должен пропасть: " + parsed.text());
    }

    @Test
    void repairTurnsALostStepIntoRealActions() {
        Message message = new Message(Message.Role.ASSISTANT, FROM_LOG_HASH, List.of(), null, null);
        ChatResponse response = new ChatResponse("id", "model",
                List.of(new ChatResponse.Choice(0, message, "stop")), ChatResponse.Usage.EMPTY);

        ChatResponse repaired = TextualToolCalls.repair(response);

        assertEquals(2, repaired.toolCalls().size(), "ход не должен был сгореть на пустом шаге");
        assertFalse(repaired.text().contains("tool call"), "служебные строки не уйдут в чат: " + repaired.text());
        assertTrue(repaired.text().contains("- thought:"), repaired.text());
    }
}
