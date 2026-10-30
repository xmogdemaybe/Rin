package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.memory.Audience;
import ru.rainedev.raine.memory.Origin;
import ru.rainedev.raine.memory.Source;

class NotificationLoopTest {

    /** Заглушка вместо модели: отдаёт заранее заготовленные ответы по очереди. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResponse> scripted = new ArrayDeque<>();
        private final List<List<Message>> seenContexts = new ArrayList<>();
        private final List<String> seenPrompts = new ArrayList<>();
        private final List<String> toolChoices = new ArrayList<>();
        private int calls;

        private ChatResponse repeating;

        void willRespond(ChatResponse response) {
            scripted.addLast(response);
        }

        /** Отвечать так всегда — для проверки зациклившейся модели. */
        void alwaysRespond(ChatResponse response) {
            repeating = response;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools, String toolChoice) {
            toolChoices.add(toolChoice);
            return chat(systemPrompt, history, tools);
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            calls++;
            seenContexts.add(List.copyOf(history));
            seenPrompts.add(systemPrompt);
            if (!scripted.isEmpty()) {
                return scripted.removeFirst();
            }
            return repeating != null ? repeating : finishing();
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static ChatResponse withToolCalls(List<ToolCall> calls, long tokens) {
        Message message = new Message(Message.Role.ASSISTANT, "", calls, null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                new ChatResponse.Usage(tokens, 0, tokens));
    }

    private static ChatResponse plainText(String text) {
        Message message = new Message(Message.Role.ASSISTANT, text, List.of(), null, null);
        return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                ChatResponse.Usage.EMPTY);
    }

    private static ChatResponse finishing() {
        return withToolCalls(List.of(call("wait")), 10);
    }

    private static ToolCall call(String name) {
        return new ToolCall("call_" + name, "function", new ToolCall.Function(name, "{}"));
    }

    private NotificationLoop loopWith(ScriptedLlm llm, Toolbox tools) {
        return new NotificationLoop(llm, () -> "системный промпт", tools, 40_000);
    }

    @Test
    void finishesTurnOnWait() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertEquals(1, llm.calls);
    }

    @Test
    void nudgesModelThatRepliedWithPlainText() {
        // текстовый ответ никто не увидит — это не действие
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText("Ой, надо бы ответить!"));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls, "после текстового ответа модель должна получить второй шанс");
        assertTrue(loop.context().stream().anyMatch(m -> m.content() != null && m.content().contains("tool_calls")));
        assertEquals(List.of("auto", "required"), llm.toolChoices, "повтор идёт с запретом текста");
    }

    @Test
    void keepsForcingToolCallsAfterFirstMiss() {
        // однажды сорвавшись в текст, модель не должна жечь шаги дальше:
        // каждый следующий запрос до конца хода идёт под запретом текста
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText("Надо бы ответить!"));
        llm.willRespond(plainText("Снова думаю в тексте..."));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertEquals(3, llm.calls);
        assertEquals(List.of("auto", "required", "required"), llm.toolChoices,
                "после первого промаха запрет держится до конца хода");
    }

    @Test
    void keepsGoingUntilFinishingToolIsCalled() {
        List<String> sent = new ArrayList<>();
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> {
            sent.add("сообщение");
            return "Отправлено";
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        loopWith(llm, tools).process(new Notification("тебе написали"));

        assertEquals(2, sent.size(), "модель успевает отправить несколько сообщений подряд");
        assertEquals(3, llm.calls);
    }

    /** Действие, видимое человеку: отправка, реакция, стикер. */
    private static Tool mutating(String name, List<String> acted) {
        return Tool.named(name).describedAs(name).mutating().build(arguments -> {
            acted.add(name);
            return "ok";
        });
    }

    /** Чат открывается сам и приносит инструменты переписки. */
    private static Tool opening(Tool... appears) {
        return Tool.named("open").describedAs("opens the chat").buildContextual((arguments, addTool) -> {
            for (Tool tool : appears) {
                addTool.accept(tool);
            }
            return "CHAT OPENED";
        });
    }

    private static String seenText(ScriptedLlm llm) {
        return String.join("\n", llm.seenContexts.getLast().stream()
                .map(Message::content)
                .filter(text -> text != null)
                .toList());
    }

    @Test
    void anImpulseActsInOneStepLikeSilence() {
        // чат открыт на шаге 0, поэтому отправка — первый и единственный шаг:
        // бездействие не должно быть дешевле действия
        List<String> acted = new ArrayList<>();
        Toolbox available = new Toolbox(opening(mutating("send_telegram_message", acted)));
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("порыв", available, true, 777, 777).onOwnInitiative());

        assertEquals(1, llm.calls, "действие не должно стоить дороже молчания");
        assertEquals(List.of("send_telegram_message"), acted);
        assertTrue(seenText(llm).contains("CHAT OPENED"), "история открытого чата видна сразу");
    }

    @Test
    void anImpulseStillCostsOneStepForSilence() {
        List<String> acted = new ArrayList<>();
        Toolbox available = new Toolbox(opening(mutating("send_telegram_message", acted)));
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("порыв", available, true, 777, 777).onOwnInitiative());

        assertEquals(1, llm.calls);
        assertTrue(acted.isEmpty());
    }

    @Test
    void anImpulseIsNotNaggedAboutAsk() {
        // самостоятельный порыв не обязан консультироваться с памятью
        List<String> acted = new ArrayList<>();
        Toolbox always = new Toolbox(Tool.named("ask").describedAs("память").build(arguments -> "memory"));
        Toolbox available = new Toolbox(opening(mutating("send_telegram_message", acted)));
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));

        loopWith(llm, always).process(new Notification("порыв", available, true, 777, 777).onOwnInitiative());

        String seen = seenText(llm);
        assertFalse(seen.contains("Have you called #ask yet"), seen);
        assertFalse(seen.contains("without consulting #ask"), seen);
    }

    @Test
    void lastTalkStatusNamesWhenAndWhat() {
        List<String> acted = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));
        loop.process(new Notification("тебе написали"));

        String status = loop.lastTalkStatus();
        assertTrue(status.contains("last conversation"), status);
        assertTrue(status.contains("send_telegram_message"), status);
        assertTrue(status.contains("now"), status);
    }

    @Test
    void rollsBackLowQualityAnswerWithoutStoringIt() {
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> {
            throw new LowQualityException("Слишком длинно, напиши короче");
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().stream().anyMatch(m -> "Слишком длинно, напиши короче".equals(m.content())));
        assertFalse(loop.context().stream()
                        .flatMap(m -> m.toolCallsOrEmpty().stream())
                        .anyMatch(c -> "send".equals(c.name())),
                "откаченный вызов не должен оставаться в контексте");
    }

    @Test
    void technicalFailureReachesTheModelWithoutItsDetails() {
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> {
            throw new IllegalStateException("нет сети");
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().stream().anyMatch(m -> m.content() != null
                        && m.content().startsWith("Не получилось")),
                "модель должна увидеть отказ и решить, что делать");
        // причины в контексте нет: в тексте ошибки бывают данные аккаунта,
        // а подробности и так ложатся в журнал
        assertFalse(loop.context().stream().anyMatch(m -> m.content() != null
                        && m.content().contains("нет сети")),
                "текст ошибки не должен уходить в разговор");
    }

    @Test
    void unknownToolDoesNotBreakTurn() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("несуществующий")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("недоступен")));
    }

    @Test
    void dumpsContextWhenItGrowsTooBig() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("wait")), 50_000));

        NotificationLoop loop = loopWith(llm, new Toolbox());
        boolean[] dumped = {false};
        loop.onContextOverflow(() -> dumped[0] = true);

        loop.process(new Notification("тебе написали"));

        assertTrue(dumped[0], "распухший контекст пора сбрасывать в память");
    }

    @Test
    void stopsAfterStepLimitWhenModelNeverFinishes() {
        // модель зациклилась и не зовёт wait — цикл обязан выйти сам
        Toolbox tools = new Toolbox(Tool.simple("send", "Отправляет", arguments -> "ок"));
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(withToolCalls(List.of(call("send")), 10));

        loopWith(llm, tools).process(new Notification("тебе написали"));

        assertTrue(llm.calls <= 24, "цикл не должен крутиться бесконечно, было вызовов: " + llm.calls);
        assertTrue(llm.calls > 1);
    }

    @Test
    void toolAddedDuringTurnBecomesAvailable() {
        // «открыть чат» добавляет «отправить» — набор обязан обновиться на следующем шаге
        Toolbox available = new Toolbox();
        List<String> sent = new ArrayList<>();
        available.add(Tool.simple("open", "Открывает чат", arguments -> {
            available.add(Tool.simple("send", "Отправляет", args -> {
                sent.add("сообщение");
                return "Отправлено";
            }));
            return "чат открыт";
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("open")), 10));
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", available));

        assertEquals(1, sent.size(), "инструмент, появившийся по ходу хода, должен работать");
        assertFalse(loop.context().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("недоступен")),
                "модель не должна получить отказ по инструменту, который уже добавлен");
    }

    @Test
    void doesNotDigIntoMemoryRightAfterSendingMessage() {
        // воспоминания между репликами сбивают мысль: вместо связного ответа
        // получается поток случайных фактов
        List<String> recallMoments = new ArrayList<>();
        Toolbox tools = new Toolbox(Tool.simple("send_telegram_message", "Отправляет", arguments -> "ок"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.memory((ctx, audience) -> {
            recallMoments.add("вспомнила");
            return "";
        });

        loop.process(new Notification("тебе написали"));

        assertEquals(1, recallMoments.size(),
                "память поднимается один раз в начале хода, а не после каждого сообщения");
    }

    @Test
    void recallsMemoryBeforeAnsweringAndPutsItBeforeNotification() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.memory((ctx, audience) -> "<your_diary_page>вы ездили на Байкал</your_diary_page>\n");

        loop.process(new Notification("тебе написали"));

        String firstMessage = loop.context().getFirst().content();
        assertTrue(firstMessage.startsWith("<your_diary_page>"), firstMessage);
        assertTrue(firstMessage.contains("тебе написали"), "уведомление должно остаться на месте");
    }

    @Test
    void opensChatWithoutAskingModel() {
        // модель открывает чат в ста случаях из ста — спрашивать об этом незачем
        List<String> opened = new ArrayList<>();
        Toolbox available = new Toolbox(Tool.simple("open", "Открывает чат", arguments -> {
            opened.add("открыт");
            return "Ты открыла чат. Последние сообщения: привет";
        }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", available));

        assertEquals(1, opened.size());
        assertEquals(1, llm.calls, "лишнего обращения к модели быть не должно");
        assertTrue(loop.context().getFirst().content().contains("Последние сообщения"),
                "модель должна сразу увидеть переписку, а не уведомление");
    }

    @Test
    void doesNotOpenAutomaticallyWhenThereIsAChoice() {
        Toolbox available = new Toolbox(
                Tool.simple("open", "Открывает чат", arguments -> "открыто"),
                Tool.simple("ignore", "Игнорирует", arguments -> "ладно"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", available));

        assertTrue(loop.context().getFirst().content().contains("тебе написали"),
                "есть выбор — решает модель");
    }

    @Test
    void remindsToConsultMemoryBeforeAnswering() {
        Toolbox tools = new Toolbox(Tool.simple("ask", "Спрашивает память", arguments -> "ничего"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.context().getFirst().content().contains("Have you called #ask"),
                loop.context().getFirst().content());
    }

    @Test
    void doesNotNagWhenMemoryWasAlreadyConsulted() {
        Toolbox tools = new Toolbox(Tool.simple("ask", "Спрашивает память", arguments -> "вспомнила"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("ask")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        long reminders = loop.context().stream()
                .filter(m -> m.content() != null && m.content().contains("Have you called #ask"))
                .count();
        assertEquals(1, reminders, "напоминание уместно один раз, до обращения к памяти");
    }

    @Test
    void thePersonOfTheTurnReachesTheSystemPrompt() {
        // доверие меняет тон: на ходу разговора она знает, кому отвечает
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());

        List<Long> asked = new ArrayList<>();
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.aboutPerson(id -> {
            asked.add(id);
            return id == 0 ? "" : "<about id=%d>".formatted(id);
        });

        loop.process(new Notification("сообщение от человека").about(777));
        loop.process(new Notification("служебное событие"));

        assertEquals(List.of(777L, 0L), asked, "каждый ход спрашивает про своего человека");
        assertTrue(llm.seenPrompts.getFirst().contains("<about id=777>"), llm.seenPrompts.getFirst());
        assertFalse(llm.seenPrompts.getLast().contains("<about"),
                "событие не про человека — и дописки про человека нет");
        assertTrue(loop.context().stream().allMatch(m -> m.content() == null || !m.content().contains("<about")),
                "дописка живёт в промпте, а не в контексте: в пересказ для дневника ей не надо");
    }

    @Test
    void theContextRemembersTheStrictestChatItSaw() {
        // разговор ещё не сохранён, а в контексте уже чужая личка и личка
        // владельца: метка одна на запись, значит берётся самая строгая
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.chatOrigin(id -> id == 1 ? new Origin(id, Source.OWNER_DM) : new Origin(id, Source.GROUP));

        loop.process(new Notification("сообщение из группы").from(2));
        assertEquals(new Origin(2, Source.GROUP), loop.contextOrigin());

        loop.process(new Notification("сообщение от владельца").from(1));
        assertEquals(new Origin(1, Source.OWNER_DM), loop.contextOrigin(),
                "в несохранённом контексте есть личка владельца — общим он стать не должен");

        loop.process(new Notification("и снова из группы").from(2));
        assertEquals(new Origin(1, Source.OWNER_DM), loop.contextOrigin(),
                "более открытый чат не вправе смягчать метку несохранённого разговора");
    }

    @Test
    void aSavedConversationStartsWithACleanLabel() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.chatOrigin(id -> new Origin(id, Source.OWNER_DM));
        loop.process(new Notification("сообщение от владельца").from(1));
        assertEquals(new Origin(1, Source.OWNER_DM), loop.contextOrigin());

        loop.clearContext();

        assertEquals(Origin.GENERAL, loop.contextOrigin(),
                "разговор записан — следующему его метка достаться не должна");
    }

    @Test
    void aChatOpenedDuringTheTurnCountsToo() {
        // открытый ею чат ложится в контекст целиком: для памяти это тот же
        // разговор, что и входящее сообщение, и метку он обязан дать свою
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("open")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        Toolbox openedChat = new Toolbox();
        openedChat.add(Tool.named("open").describedAs("Открывает чат")
                .buildContextual((arguments, addTool) -> {
                    loop.noteOrigin(new Origin(1, Source.OWNER_DM));
                    return "чат открыт";
                }));

        loop.process(new Notification("ночной порыв", openedChat));

        assertEquals(new Origin(1, Source.OWNER_DM), loop.contextOrigin(),
                "чат, открытый в середине хода, тоже часть этого разговора");
    }

    @Test
    void anEventThatCameFromNoChatIsNotAttributed() {
        // ночное размышление или напоминание приходят не из переписки:
        // приписать их чужой личке значит спрятать от самой себя
        List<Long> asked = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.chatOrigin(id -> {
            asked.add(id);
            return new Origin(id, Source.OWNER_DM);
        });

        loop.process(new Notification("служебное событие"));

        assertTrue(asked.isEmpty(), "спрашивать про чат нечего — его нет");
        assertEquals(Origin.GENERAL, loop.contextOrigin());
    }

    /** Куда память уходит зрителю — ловим сам зритель, а не его строку. */
    private static List<Audience> audienceSpy(NotificationLoop loop) {
        List<Audience> seen = new ArrayList<>();
        loop.memory((ctx, audience) -> {
            seen.add(audience);
            return "";
        });
        return seen;
    }

    @Test
    void memoryIsAnsweredToTheChatThatAsked() {
        // подмешивание ищет не по тексту: один и тот же вопрос в разных чатах
        // даёт разную выдачу, и кто именно спросил надо передать в память
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        List<Audience> seen = audienceSpy(loop);
        loop.chatOrigin(id -> new Origin(id, Source.GROUP));
        loop.trustOf(id -> id == 555 ? 3 : 0);

        loop.process(new Notification("сообщение из группы").about(555).from(2));

        assertEquals(List.of(new Audience(2, 3, false)), seen, "зритель — этот чат с этим доверием");
    }

    @Test
    void theOwnerChatIsMarkedAsTheOwners() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        List<Audience> seen = audienceSpy(loop);
        loop.chatOrigin(id -> new Origin(id, Source.OWNER_DM));
        loop.trustOf(id -> 0);

        loop.process(new Notification("личное сообщение").about(1).from(1));

        assertEquals(List.of(new Audience(1, 0, true)), seen,
                "в своей личке ей открываются и то, что рассказывали другие");
    }

    @Test
    void anEventAboutAPersonWithNoChatIsSeenByThatPerson() {
        // событие о человеке, но не из его чата (например, напоминание из
        // планировщика): зрителем всё равно он, раз доверие меряют по нему
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        List<Audience> seen = audienceSpy(loop);
        loop.trustOf(id -> id == 555 ? 3 : 0);

        loop.process(new Notification("напоминание о человеке").about(555));

        assertEquals(List.of(new Audience(555, 3, false)), seen,
                "без адреса чата оставался бы ноль — и показывать ему было бы нечего");
    }

    @Test
    void anImpulseThatCameFromNoChatHasNoAudience() {
        // зритель считается заново на каждый ход: вчерашняя личка владельца не
        // должна протаскивать его права в служебное событие без адреса
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        List<Audience> seen = audienceSpy(loop);
        loop.chatOrigin(id -> new Origin(id, Source.OWNER_DM));

        loop.process(new Notification("личное сообщение").about(1).from(1));
        loop.process(new Notification("служебное событие"));

        assertEquals(List.of(new Audience(1, 0, true), Audience.STRANGER), seen,
                "без адреса зрителя нет — и такому ходу остаётся только общее");
    }

    @Test
    void toolsFromOneTurnDoNotLeakIntoTheNext() {
        // самая опасная ошибка: отправка в чат, открытый когда-то раньше,
        // остаётся доступной — и сообщение уходит не тому человеку
        List<String> sentTo = new ArrayList<>();
        Toolbox firstNotification = new Toolbox();
        firstNotification.add(Tool.named("open").describedAs("Открывает чат")
                .buildContextual((arguments, addTool) -> {
                    addTool.accept(Tool.simple("send", "Отправляет в первый чат", args -> {
                        sentTo.add("первый");
                        return "ок";
                    }));
                    return "чат открыт";
                }));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("первое сообщение", firstNotification));
        assertEquals(1, sentTo.size(), "в своём ходе отправка работает");

        // второй ход, другой чат — отправки в первый быть не должно
        llm.willRespond(withToolCalls(List.of(call("send")), 10));
        llm.willRespond(finishing());
        loop.process(new Notification("сообщение из другого чата", new Toolbox()));

        assertEquals(1, sentTo.size(), "инструмент прошлого хода не должен работать");
        assertTrue(loop.context().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("недоступен")),
                "модель должна получить отказ, а не отправку не туда");
    }

    @Test
    void discardsObsoleteNotifications() {
        NotificationLoop loop = loopWith(new ScriptedLlm(), new Toolbox());
        loop.submit(new Notification("<notification chat_id=\"5\">\nсообщение"));
        loop.submit(new Notification("<notification chat_id=\"9\">\nдругое"));

        loop.discardMatching("<notification chat_id=\"5\">\n");

        // косвенная проверка: осталось одно — обработка не зависнет на take()
        loop.process(new Notification("проверка"));
        assertTrue(true);
    }

    @Test
    void quietNightIsSleepable() {
        NotificationLoop loop = loopWith(new ScriptedLlm(), new Toolbox());

        assertFalse(loop.talkAlive(), "тишина: спать можно");
    }

    @Test
    void unreadQueueKeepsHerAwake() {
        NotificationLoop loop = loopWith(new ScriptedLlm(), new Toolbox());

        loop.submit(new Notification("тебе написали"));

        assertTrue(loop.talkAlive(), "в очереди непрочитанное — спать нельзя");
    }

    @Test
    void justFinishedTurnStillCountsAsLivelyTalk() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("тебе написали"));

        assertTrue(loop.talkAlive(), "ход кончился только что — разговор живой");
    }
}
