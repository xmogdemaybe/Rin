package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.memory.Audience;
import ru.rainedev.raine.memory.DiaryMarkup;
import ru.rainedev.raine.memory.Memory;
import ru.rainedev.raine.memory.Origin;
import ru.rainedev.raine.memory.Source;
import ru.rainedev.raine.phone.ScreenMarkup;

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

    /** Отказ контент-фильтра: ответ пришёл, но в нём ни текста, ни вызовов. */
    private static ChatResponse refused() {
        Message message = new Message(Message.Role.ASSISTANT, "", List.of(), null, null);
        return new ChatResponse("id", "test",
                List.of(new ChatResponse.Choice(0, message, "content_filter: PROHIBITED_CONTENT")),
                new ChatResponse.Usage(42_069, 0, 42_069));
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
    void endsTurnOnBanningBecauseClosedDoorHasNothingToContinue() {
        // за закрытой дверью продолжать не с кем: холостой шаг после блокировки
        // стоил бы ещё одного обращения к модели и ничего бы не менял
        List<String> acted = new ArrayList<>();
        Toolbox tools = new Toolbox(mutating("remove_and_ban_chat", acted));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("remove_and_ban_chat")), 10));
        llm.willRespond(finishing());

        loopWith(llm, tools).process(new Notification("тебе написали"));

        assertEquals(List.of("remove_and_ban_chat"), acted, "дверь закрывается одним вызовом");
        assertEquals(1, llm.calls, "после блокировки ход кончен — второго шанса не нужно");
    }

    @Test
    void remembersBanningAsTheLastVisibleAction() {
        // блокировка — действие, а не молчание: в отличие от wait и pause она
        // должна остаться в памяти о том, чем закончился разговор
        List<String> acted = new ArrayList<>();
        Toolbox tools = new Toolbox(mutating("remove_and_ban_chat", acted));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("remove_and_ban_chat")), 10));

        NotificationLoop loop = loopWith(llm, tools);
        loop.process(new Notification("тебе написали"));

        assertTrue(loop.lastTalkStatus().contains("remove_and_ban_chat"), loop.lastTalkStatus());
        assertEquals(1, acted.size(), acted.toString());
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
        // укол не показывает моделью запрещённый синтаксис: она его копирует
        assertFalse(loop.context().stream().anyMatch(m -> m.content() != null && m.content().contains("tool call #")),
                "в подсказке не должно быть образца текстового вызова");
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
    void aContentFilterRefusalIsRetriedOnceAndThenEndsTheTurn() {
        // HTTP 200 с пустым ответом и finish_reason=content_filter — это отказ
        // входной безопасности, а не «модель поболтала». Утро 18.11: идентичный
        // промпт дал отказ в 09:31:48 и ответ в 09:32:47, поэтому одна вторая
        // попытка имеет смысл; дальше долбить — платить полным контекстом.
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(refused());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));

        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls, "повтор ровно один: " + llm.calls);
    }

    @Test
    void aFilterRetryThatAnswersKeepsTheTurnAlive() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(refused());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));

        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls);
        assertTrue(loop.context().stream().anyMatch(message -> !message.toolCallsOrEmpty().isEmpty()),
                "ответ повтора — действие, а не выброшенный ответ: " + loop.context());
    }

    @Test
    void aRefusedTurnGivesItsDiaryPagesBackToMemory() {
        // вклейка легла в сообщение, модель на неё не ответила ничего: страницы
        // остались бы лежать мёртвым грузом и считать себя показанными
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(refused());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));
        RecallingMemory memory = new RecallingMemory();
        loop.memory(memory);

        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls, "ход с повтором: " + llm.calls);
        String first = loop.context().getFirst().content();
        assertFalse(first.contains("your_diary_page"), "вклейка снята: " + first);
        assertTrue(first.contains("тебе написали"), "реплика осталась: " + first);
        assertTrue(memory.injected.isEmpty(), "страницы вернулись в выборку: " + memory.injected);
    }

    @Test
    void aRefusedImpulseIsRolledBackByTheImpulseUndoAlone() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(refused());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));
        RecallingMemory memory = new RecallingMemory();
        loop.memory(memory);
        boolean[] undone = {false};
        loop.idleImpulseSnapshot(() -> () -> undone[0] = true);
        int before = loop.context().size();

        loop.process(new Notification("порыв в никуда").onOwnInitiative());

        assertTrue(undone[0], "отметки снимаются штатным откатом порыва");
        assertEquals(before, loop.context().size(), "порыв убран целиком, включая вклейку");
        assertEquals(2, llm.calls, "на порыве тоже сделан ровно один повтор: " + llm.calls);
    }

    @Test
    void aFilterRefusalAfterAnActionIsNotRetried() {
        // повтор сделан только на шаге 0: дальше «последние три сообщения» —
        // уже её собственные вызовы, и вердикт по тому же тексту не меняется
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(refused());
        llm.willRespond(refused());
        List<String> acted = new ArrayList<>();
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));
        loop.filterRetryPause(Duration.ofMillis(10));

        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls, "шаг 0 — действие, шаг 1 — отказ без повтора: " + llm.calls);
        assertEquals(List.of("send_telegram_message"), acted);
    }

    /** Снимок чата с полкой и пояснением — как его собирают TelegramTools и ChatScreen. */
    private static Toolbox screenWithShelf(String marker) {
        return new Toolbox(Tool.simple("open", "Открывает чат", arguments -> """
                You switched to the chat "xmogde" in Telegram. You see last messages:
                <message message_id="1">привет, %s</message>

                <instructions>
                You are in private chat with xmogde. Answer as yourself.
                </instructions>

                <favourite_stickers>
                Your own stickers. Send one with #sticker_send(sticker_id) — no need to look them up.
                  - sticker_id="1" emoji="❤️": Chibi anime character with white hair and cat ears.
                </favourite_stickers>
                """.formatted(marker)));
    }

    @Test
    void onlyTheFreshestScreenshotKeepsTheShelf() {
        // полка приклеена к каждому снимку чата, а смысл у неё один на весь
        // разговор: вечер 18.11 дал три побайтово одинаковые копии по 4 719 знаков
        // в одном запросе — 20% текста и три порции той же лексики
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("первое уведомление", screenWithShelf("прежняя")));
        loop.process(new Notification("второе уведомление", screenWithShelf("свежая")));

        List<String> shelves = loop.context().stream()
                .map(Message::content)
                .filter(ScreenMarkup::hasShelf)
                .toList();
        assertEquals(1, shelves.size(), "полка остаётся одна: " + loop.context());
        assertTrue(shelves.getFirst().contains("свежая"), "и самая свежая: " + shelves);
        assertFalse(shelves.getFirst().contains("прежняя"), "прежний снимок должен остаться без полки: " + shelves);
        assertEquals(1, withText(loop.context(), "<instructions>"),
                "пояснение к чату тоже остаётся в одном экземпляре: " + loop.context());
        assertEquals(1, withText(loop.context(), "</instructions>"), loop.context().toString());
    }

    /** Сколько сообщений в истории содержат метку. */
    private static int withText(List<Message> history, String needle) {
        int hits = 0;
        for (Message message : history) {
            String text = message.content();
            if (text != null && text.contains(needle)) {
                hits++;
            }
        }
        return hits;
    }

    @Test
    void anIdleImpulseRollsBackEvenThoughTheShelfWasRetired() {
        // откат холостого порыва сверяет начало истории дословно; снятие старой
        // полки правит те же сообщения, поэтому проверка должна увидеть
        // отредактированный снимок истории, а не прежний
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.process(new Notification("тебе написали", screenWithShelf("прежняя")));
        loop.windBackLastTalk(Duration.ofMinutes(31));
        List<Message> before = loop.context();
        assertTrue(before.stream().anyMatch(m -> ScreenMarkup.hasShelf(m.content())),
                "полка в истории до порыва есть: " + before);

        loop.process(new Notification("порыв, который ни к чему не привёл", screenWithShelf("свежая"))
                .onOwnInitiative());

        List<Message> after = loop.context();
        assertEquals(before.size(), after.size(), "порыв убран целиком");
        assertTrue(after.stream().noneMatch(m -> String.valueOf(m.content()).contains("свежая")),
                "его снимка не осталось: " + after);
        for (int i = 0; i < before.size(); i++) {
            String was = before.get(i).content();
            assertEquals(was == null ? null : ScreenMarkup.stripScreen(was), after.get(i).content(),
                    "от прежнего сообщения осталась одна обвязка снимка, индекс " + i);
        }
    }

    @Test
    void theFilterRetryGoesWithoutTheGluedDiaryPages() {
        // повтор дословно того же запроса фильтр забраковал дважды (21 679 токенов
        // дважды подряд) — вклейка памяти единственное, что этот ход принёс сам
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(refused());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));
        RecallingMemory memory = new RecallingMemory();
        loop.memory(memory);

        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls);
        String first = llm.seenContexts.get(0).getFirst().content();
        String second = llm.seenContexts.get(1).getFirst().content();
        assertTrue(first.contains("your_diary_page"), "первый запрос шёл со вклейкой: " + first);
        assertFalse(second.contains("your_diary_page"), "повтор — без неё: " + second);
        assertTrue(second.contains("тебе написали"), "слова человека на месте: " + second);
        assertTrue(memory.injected.isEmpty(), "страницы вернулись в выборку: " + memory.injected);
    }

    @Test
    void anIdleImpulseDropsThePagesBeforeItsRetryAndStillRollsBack() {
        // страж «порыв откатывает историю сам» мешал снять вклейку до повтора;
        // без него откат порыва должен работать так же чисто
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(refused());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));
        RecallingMemory memory = new RecallingMemory();
        loop.memory(memory);
        boolean[] undone = {false};
        loop.idleImpulseSnapshot(() -> () -> undone[0] = true);
        int before = loop.context().size();

        loop.process(new Notification("порыв в никуда").onOwnInitiative());

        assertEquals(2, llm.calls, "на порыве тоже сделан один повтор: " + llm.calls);
        assertTrue(llm.seenContexts.get(0).stream()
                .anyMatch(m -> String.valueOf(m.content()).contains("your_diary_page")),
                "первый запрос был со вклейкой: " + llm.seenContexts.get(0));
        assertTrue(llm.seenContexts.get(1).stream()
                .noneMatch(m -> String.valueOf(m.content()).contains("your_diary_page")),
                "повтор ушёл без вклейки: " + llm.seenContexts.get(1));
        assertEquals(before, loop.context().size(), "холостой порыв откатан целиком");
        assertTrue(undone[0], "отметки за его пределами сняты");
        assertTrue(memory.injected.isEmpty(), "вспомненное вернулось в выборку: " + memory.injected);
    }

    @Test
    void pagesReadOnAPreviousTurnLeaveTheHistory() {
        // страница, которую модель прочитала и по которой ответила, больше не
        // нужна в запросе: в 00:07 19.11 такие страницы ездили в контексте по
        // три хода, а строка журнала про «страниц не осталось» была неправдой
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        RotatingMemory memory = new RotatingMemory();
        loop.memory(memory);

        loop.process(new Notification("первый ход", screenWithShelf("прежняя")));
        loop.process(new Notification("второй ход", screenWithShelf("свежая")));

        List<String> pages = loop.context().stream()
                .map(m -> String.valueOf(m.content()))
                .filter(t -> t.contains("your_diary_page"))
                .toList();
        assertEquals(1, pages.size(), "страницы остаются только у текущего хода: " + loop.context());
        assertEquals(1, withText(loop.context(), "</your_diary_page>"),
                "закрывающий тег остался только у самой свежей вклейки: " + loop.context());
        assertTrue(pages.getFirst().contains("страница номер 2"), "и самые свежие: " + pages);
        assertFalse(pages.getFirst().contains("страница номер 1"), pages.getFirst());
        assertEquals(0, memory.restores, "прочитанное не возвращается в выборку: " + memory.injected);
        assertEquals(Set.of(1L, 2L), memory.injected);
    }

    @Test
    void aRefusedTurnLeavesNoPagesFromEarlierTurnsEither() {
        // откат текущего хода плюс зачистка прошлых: после отказа в истории не
        // должно остаться ни одной страницы дневника
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.alwaysRespond(refused());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.filterRetryPause(Duration.ofMillis(10));
        RotatingMemory memory = new RotatingMemory();
        loop.memory(memory);

        loop.process(new Notification("первый ход", screenWithShelf("прежняя")));
        loop.process(new Notification("второй ход", screenWithShelf("свежая")));

        assertTrue(loop.context().stream().noneMatch(m -> String.valueOf(m.content()).contains("your_diary_page")),
                "ни прочитанных, ни отменённых страниц не осталось: " + loop.context());
        assertEquals(1, memory.restores, "откатился только текущий ход: " + memory.restores);
        assertEquals(Set.of(1L), memory.injected, "страница первого хода считает себя прочитанной: " + memory.injected);
    }

    @Test
    void servedPingsDoNotRideAlong() {
        String ping = NotificationLoop.serviceInsertions().getFirst();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText("- thought: надо ответить.\n- intention: послать привет."));
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("первый ход", screenWithShelf("прежняя")));
        assertTrue(loop.context().stream().anyMatch(m -> String.valueOf(m.content()).startsWith(ping)),
                "промах шага 0 оставил пинк: " + loop.context());

        loop.process(new Notification("второй ход", screenWithShelf("свежая")));
        assertTrue(loop.context().stream().noneMatch(m -> String.valueOf(m.content()).startsWith(ping)),
                "отработанный пинк убран из истории: " + loop.context());
    }

    @Test
    void theMissedStepHandsItsIntentionToTheNextStep() {
        // 23:57:55: на шаге 0 модель задумала реакцию, шаг 1 под required поставил
        // wait — своего черновика она уже не видела
        String draft = "- thought: надо поддержать.\n- intention: поставить реакцию на его арт.";
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText(draft));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("тебе написали"));

        assertEquals(2, llm.calls);
        List<Message> second = llm.seenContexts.get(1);
        assertTrue(second.stream().anyMatch(m -> m.role() == Message.Role.ASSISTANT
                        && String.valueOf(m.content()).contains("- intention: поставить реакцию")),
                "шаг 1 должен видеть намерение шага 0: " + second);
        assertTrue(llm.seenContexts.get(0).stream().noneMatch(m -> m.role() == Message.Role.ASSISTANT),
                "до промаха черновика в истории не было");
    }

    /**
     * Снимок, открытый самой моделью в середине хода, чистит прежние сообщения,
     * но не трогает их позиции: пинк прошлого хода остаётся на месте, иначе
     * индекс, который помнит снимок вклейки, уедет и откат вернёт не то сообщение.
     */
    @Test
    void aSnapshotInsideTheTurnKeepsMessagePositions() {
        String ping = NotificationLoop.serviceInsertions().getFirst();
        Toolbox choice = new Toolbox(
                Tool.simple("open", "Открывает чат", arguments -> """
                        You switched to the chat "xmogde" in Telegram.
                        <message message_id="9">привет, свежая</message>
                        <instructions>
                        You are in private chat with xmogde. Answer as yourself.
                        </instructions>
                        <favourite_stickers>
                          - sticker_id="1" emoji="❤️": Chibi anime character with white hair.
                        </favourite_stickers>"""),
                // вторая тулза не даёт циклу открыть чат молча на входе хода
                Tool.simple("ignore", "Игнорирует", arguments -> "ok"));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText("- thought: надо ответить.\n- intention: послать привет."));
        llm.willRespond(finishing());                                   // ход 1, шаг 1
        llm.willRespond(withToolCalls(List.of(call("open")), 10));      // ход 2, шаг 0
        llm.willRespond(finishing());                                   // ход 2, шаг 1
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("первый ход", screenWithShelf("прежняя")));
        loop.process(new Notification("второй ход", choice));

        assertEquals(1, withText(loop.context(), ping.substring(0, 40)),
                "пинк прошлого хода остался на своём месте: " + loop.context());
        assertEquals(1, withText(loop.context(), "</favourite_stickers>"), loop.context().toString());
        assertEquals(1, withText(loop.context(), "</instructions>"), loop.context().toString());
        assertTrue(loop.context().stream().filter(m -> String.valueOf(m.content()).contains("</favourite_stickers>"))
                .allMatch(m -> String.valueOf(m.content()).contains("свежая")),
                "в истории осталась только свежая полка: " + loop.context());
    }

    /**
     * Стикеры могут быть выключены настройкой — тогда в снимке только пояснение.
     * Триггер зачистки обязан срабатывать и в этом случае, иначе прошлые
     * страницы и пинки переживают такой ход нетронутыми.
     */
    @Test
    void notesWithoutShelfStillTidyTheHistory() {
        Toolbox screen = new Toolbox(Tool.simple("open", "Открывает чат", arguments -> """
                You switched to the chat "xmogde" in Telegram.
                <message message_id="1">привет, прежняя</message>
                <instructions>
                You are in private chat with xmogde. Answer as yourself.
                </instructions>"""));
        Toolbox fresh = new Toolbox(Tool.simple("open", "Открывает чат", arguments -> """
                You switched to the chat "xmogde" in Telegram.
                <message message_id="2">привет, свежая</message>
                <instructions>
                You are in private chat with xmogde. Answer as yourself.
                </instructions>"""));

        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        RotatingMemory memory = new RotatingMemory();
        loop.memory(memory);

        loop.process(new Notification("первый ход", screen));
        loop.process(new Notification("второй ход", fresh));

        assertEquals(1, withText(loop.context(), "</instructions>"),
                "пояснение остаётся одно: " + loop.context());
        assertEquals(1, withText(loop.context(), "</your_diary_page>"),
                "и страница памяти тоже одна: " + loop.context());
    }

    /** Память, которая на каждый ход поднимает свою страницу. */
    private static final class RotatingMemory implements Memory {
        final Set<Long> injected = new java.util.HashSet<>();
        int restores;
        private int turn;

        @Override
        public String recall(List<Message> recentContext, Audience audience) {
            turn++;
            injected.add((long) turn);
            return DiaryMarkup.page("[from: your private chat with your person]", "страница номер " + turn);
        }

        @Override
        public Set<Long> markInjected() {
            return Set.copyOf(injected);
        }

        @Override
        public void restoreInjected(Set<Long> snapshot) {
            restores++;
            injected.clear();
            injected.addAll(snapshot);
        }
    }

    /** Память с отпечатками: как настоящая, но без дневника и эмбеддингов. */
    private static final class RecallingMemory implements Memory {
        final Set<Long> injected = new java.util.HashSet<>();

        @Override
        public String recall(List<Message> recentContext, Audience audience) {
            injected.add(42L);
            return DiaryMarkup.page("[from: your private chat with your person]",
                    "мы ездили на Байкал");
        }

        @Override
        public Set<Long> markInjected() {
            return Set.copyOf(injected);
        }

        @Override
        public void restoreInjected(Set<Long> snapshot) {
            injected.clear();
            injected.addAll(snapshot);
        }
    }

    @Test
    void threeEmptyStepsInARowEndTheTurn() {
        // одного-двух текстовых промахов хватает на напоминание; три подряд
        // означают, что модель упёрлась, и платить двадцатью четырьмя шагами
        // незачем ни при какой причине
        ScriptedLlm llm = new ScriptedLlm();
        llm.alwaysRespond(plainText("думаю в тексте..."));

        loopWith(llm, new Toolbox()).process(new Notification("тебе написали"));

        assertEquals(3, llm.calls, "предел пустых шагов подряд: " + llm.calls);
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

    /** Контекст выброшен — снимки чатов не должны считать свои сообщения уже показанными. */
    @Test
    void clearingTheContextForggetsTheScreens() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        List<String> forgotten = new ArrayList<>();

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.onContextCleared(() -> forgotten.add("снимки"));
        loop.process(new Notification("тебе написали"));
        loop.clearContext();

        assertEquals(List.of("снимки"), forgotten,
                "сообщений в контексте нет, а пометка о них осталась бы — следующие снимки вышли бы пустыми");
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
    void serviceNotesAreKnownToTheReteller() {
        // пересказ вырезает приписки цикла по списку: чего в списке нет —
        // однажды уедет в дневник как слова разговора
        List<String> notes = NotificationLoop.serviceInsertions();

        assertTrue(notes.stream().anyMatch(note -> note.contains("What's your next action?")),
                "подсказка про следующий шаг приписана к чужому тексту: " + notes);
        assertTrue(notes.stream().anyMatch(note -> note.contains("Earlier successful actions")),
                "записка о недосказанных действиях тоже: " + notes);
    }

    @Test
    void theNextStepHintOnlyNamesActions() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("ask")), 10));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm,
                new Toolbox(Tool.simple("ask", "Спрашивает память", arguments -> "вспомнила")));

        loop.process(new Notification("тебе написали"));

        String hint = loop.context().stream()
                .map(Message::content)
                .filter(java.util.Objects::nonNull)
                .filter(text -> text.contains("What's your next action?"))
                .findFirst().orElse("");
        assertTrue(hint.contains("The following tools available"), "подсказка должна быть на месте: " + hint);
        assertFalse(hint.contains("Use #ask"),
                "вспоминание решает код, а не напоминание в каждом шаге: " + hint);
    }

    @Test
    void theLoopThreadItselfCarriesTheConversationToMemory() throws Exception {
        // история принадлежит потоку цикла: тот, кто ждёт останова из другого
        // потока, не имеет права её читать или чистить, пока ход ещё идёт
        NotificationLoop loop = loopWith(new ScriptedLlm(), new Toolbox());
        boolean[] saved = {false};
        loop.onLoopStopped(() -> saved[0] = true);

        Thread brain = Thread.ofVirtual().start(loop::run);
        loop.stop();

        assertTrue(loop.awaitStopped(java.time.Duration.ofSeconds(5)), "поток обязан завершиться");
        assertTrue(saved[0], "разговор в память переносит сам цикл, до того как отпустить ожидающих");
        brain.join(java.time.Duration.ofSeconds(3));
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
    void justFinishedTalkStillCountsAsLively() {
        // спать не пускает именно настоящая переписка: ответила полчаса назад —
        // человек ещё здесь и может ответить
        List<String> acted = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));

        loop.process(new Notification("тебе написали"));

        assertTrue(loop.talkAlive(), "отвеченное сообщение — живой разговор");
    }

    @Test
    void aSilentTurnDoesNotHoldHerUp() {
        // фоновый шум сон не откладывает: прочитала пост в канале и промолчала —
        // это не разговор, а перед сном такое молчание случается каждые полчаса
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("пост из канала"));

        assertFalse(loop.talkAlive(), "молчаливый ход не живой разговор — ложиться можно");
    }

    @Test
    void herOwnGoodbyeDoesNotPutOffSleep() {
        // прощание на ночь — её же ход, никто его не ждёт: он не должен отмерять
        // поблажку, из-за которой она ложится на десять минут позже слова «споки»
        List<String> acted = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));

        loop.process(new Notification("пора сказать спокойной ночи").asGreeting());

        assertFalse(loop.talkAlive(), "собственное прощание сон не откладывает");
        assertEquals(1, acted.size(), "прощание всё-таки отправлено");
    }

    @Test
    void anAnswerToHimStillKeepsHerUp() {
        // а вот ответ на его слова — живой разговор: уходить спать посреди него нельзя
        List<String> acted = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));

        loop.process(new Notification("он ответил на её прощание"));

        assertTrue(loop.talkAlive(), "ответ на чужое сообщение — разговор");
    }

    @Test
    void silentTurnLeavesTheConversationClockWhereItWas() {
        // ход без видимого действия — это её решение промолчать, а не разговор:
        // холостые порывы каждые полчаса сдвигали «последний разговор был только
        // что», и следующий приходил поверх тишины, которую она сама устроила.
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());

        loop.process(new Notification("тебе написали"));

        assertEquals("", loop.lastTalkStatus(), "молчание не было последним разговором");
        assertFalse(loop.talkAlive(), "и сон оно не откладывает");
    }

    @Test
    void theMarksOfAnIdleImpulseAreReleasedEvenIfHistoryWasRebuilt() {
        // пересказ посреди хода обнуляет историю — и прежний код на этом
        // останавливался, leaving отметки «это уже вспоминалось» и «это уже было
        // в снимке» потраченными до конца дня
        boolean[] undone = {false};
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("wait")), 50_000));
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.idleImpulseSnapshot(() -> () -> undone[0] = true);
        loop.onContextOverflow(loop::clearContext);

        loop.process(new Notification("порыв в никуда").onOwnInitiative());

        assertTrue(undone[0], "отметки снимаются независимо от того, успели ли пересобрать историю");
    }

    @Test
    void impulseIsSkippedWhileTheConversationIsStillFresh() {
        List<String> acted = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));
        loop.process(new Notification("тебе написали"));
        int calls = llm.calls;

        loop.process(new Notification("порыв написать первой").onOwnInitiative());

        assertEquals(2, calls, "обычный ход: ответ и завершение");
        assertEquals(calls, llm.calls, "порыв поверх только что отвеченного сообщения модели не показывается");
    }

    @Test
    void impulseIsAllowedOnceTheConversationHasGoneQuiet() {
        List<String> acted = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));
        loop.process(new Notification("тебе написали"));
        loop.windBackLastTalk(java.time.Duration.ofMinutes(31));

        loop.process(new Notification("порыв написать первой").onOwnInitiative());

        assertEquals(3, llm.calls, "полчаса тишины — порыв законен");
    }

    @Test
    void idleImpulseLeavesNoTraceInTheContext() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.chatOrigin(id -> new Origin(id, Source.OWNER_DM));
        loop.process(new Notification("тебе написали").from(777));
        loop.windBackLastTalk(java.time.Duration.ofMinutes(31));
        List<Message> before = loop.context();
        Origin beforeOrigin = loop.contextOrigin();
        assertTrue(before.size() > 1, "разговор в контексте есть: " + before);

        loop.process(new Notification("порыв, который ни к чему не привёл").onOwnInitiative().from(1));

        assertEquals(before, loop.context(), "холостой порыв не оставляет следа в истории");
        assertEquals(beforeOrigin, loop.contextOrigin(), "и в метке разговора");
    }

    @Test
    void idleImpulseRollsBackWhatLivesOutsideTheContext() {
        boolean[] undone = {false};
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.idleImpulseSnapshot(() -> () -> undone[0] = true);

        loop.process(new Notification("порыв в никуда").onOwnInitiative());

        assertTrue(undone[0], "воспоминания и показанные снимки потрачены зря — их тоже надо вернуть");
    }

    @Test
    void impulseThatActuallyWroteKeepsItsTrace() {
        List<String> acted = new ArrayList<>();
        boolean[] undone = {false};
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());
        NotificationLoop loop = loopWith(llm, new Toolbox(mutating("send_telegram_message", acted)));
        loop.idleImpulseSnapshot(() -> () -> undone[0] = true);
        List<Message> before = loop.context();

        loop.process(new Notification("порыв, из которого вышла реплика").onOwnInitiative());

        assertFalse(undone[0], "состояние состоявшегося разговора откатывать нельзя");
        assertTrue(loop.context().size() > before.size(), "сказанное остаётся в истории");
        assertTrue(loop.lastTalkStatus().contains("send_telegram_message"), loop.lastTalkStatus());
    }

    /**
     * Ход 21:11: стикер, три реакции, подписка — и после каждого шага память
     * поднималась заново, потому что «последние три сообщения» уже состоят из
     * ответов тулз. Итог того хода — 112 077 токенов на одну реакцию.
     */
    @Test
    void memoryIsAskedOnceEvenWhenTheTurnRunsSeveralTools() {
        List<String> moments = new ArrayList<>();
        Toolbox tools = new Toolbox(
                Tool.simple("sticker_send", "Отправляет стикер", arguments -> "Sticker sent."),
                Tool.simple("react_with_emoji", "Ставит реакцию", arguments -> "Reaction added."),
                Tool.simple("join_channel", "Подписывается", arguments -> "Joined."));
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("sticker_send")), 10));
        llm.willRespond(withToolCalls(List.of(call("react_with_emoji")), 10));
        llm.willRespond(withToolCalls(List.of(call("join_channel")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.memory((ctx, audience) -> {
            moments.add("вспомнила");
            return "";
        });

        loop.process(new Notification("тебе написали"));

        assertEquals(1, moments.size(),
                "память поднимается один раз за ход, а не после каждого инструмента: " + moments);
    }

    /** Тот же шаг 1, но без действия: пинк цикла «только вызов инструментов» тоже не поводы вспоминать. */
    @Test
    void theToolNudgeDoesNotBringMemoryBack() {
        List<String> moments = new ArrayList<>();
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(plainText("просто текст без вызова"));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, new Toolbox());
        loop.memory((ctx, audience) -> {
            moments.add("вспомнила");
            return "";
        });

        loop.process(new Notification("тебе написали"));

        assertEquals(1, moments.size(), "на шаге 1 последним стоит подсказка цикла: " + moments);
    }

    /** Откат ответа низкого качества дописывает в контекст реплику цикла — тот же случай. */
    @Test
    void theLowQualityRollbackDoesNotBringMemoryBack() {
        List<String> moments = new ArrayList<>();
        Toolbox tools = new Toolbox(Tool.simple("send_telegram_message", "Отправляет", arguments -> {
            throw new LowQualityException("слишком длинно, напиши короче");
        }));
        ScriptedLlm llm = new ScriptedLlm();
        llm.willRespond(withToolCalls(List.of(call("send_telegram_message")), 10));
        llm.willRespond(finishing());

        NotificationLoop loop = loopWith(llm, tools);
        loop.memory((ctx, audience) -> {
            moments.add("вспомнила");
            return "";
        });

        loop.process(new Notification("тебе написали"));

        assertEquals(1, moments.size(), "после отката память не возвращается: " + moments);
    }

    /** В 17:19:53 страницы дневника вклеились в ответ тулзы, и настоящий ответ уехал в конец пяти килобайт. */
    @Test
    void memoryNeverGoesInsideAToolResultOrHerOwnLine() {
        assertEquals(-1, NotificationLoop.recallTarget(List.of()), "пустой контекст — подмешивать некуда");
        assertEquals(0, NotificationLoop.recallTarget(List.of(Message.user("тебе написали"))));
        assertEquals(-1, NotificationLoop.recallTarget(List.of(
                Message.user("тебе написали"), Message.toolResult("call-1", "Sticker sent."))),
                "память не прячет ответ инструмента");
        assertEquals(-1, NotificationLoop.recallTarget(List.of(Message.assistant("- thought: ок"))),
                "и не вклеивается в её же реплику");
    }
}
