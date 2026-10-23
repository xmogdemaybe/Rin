package ru.rainedev.raine.core;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.memory.Memory;

/**
 * Ядро поведения: очередь уведомлений и обработка каждого до тех пор,
 * пока Raine не решит, что делать больше нечего.
 * <p>
 * Ход всегда заканчивается вызовом {@code wait} или {@code pause}. Ответ
 * обычным текстом действием не считается: его никто не увидит, и модели
 * об этом напоминают.
 */
public final class NotificationLoop {

    private static final Logger log = LoggerFactory.getLogger(NotificationLoop.class);

    /** Страховка от зацикливания: живой ход столько шагов не занимает. */
    private static final int MAX_STEPS_PER_NOTIFICATION = 24;

    /** Сколько раз возвращаться к уведомлению, пока не вернётся сеть. */
    private static final int MAX_RETRIES = 5;

    /** Пауза между такими возвращениями: сеть здесь пропадает на минуты, не на секунды. */
    private Duration retryPause = Duration.ofSeconds(60);

    /**
     * Дальше не ждать: занятая модель оттаивает десятками минут, а пять попыток
     * по минуте покрывали только самый короткий сбой. В логе 22.10 сообщение
     * владельца упёрлось в 503 и было брошено через двенадцать минут, хотя
     * эндпоинт снова отвечал через девять после этого.
     */
    private static final Duration RETRY_PAUSE_CEILING = Duration.ofMinutes(10);

    /** Сигнал «пора заканчивать»: кладётся в очередь, чтобы разбудить ожидание. */
    private static final Notification STOP = new Notification("", new Toolbox());

    private volatile boolean stopping;
    private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);

    /** Как часто в тишине переспрашивать себя, не пора ли отдохнуть. */
    private java.time.Duration idleCheck = java.time.Duration.ofMinutes(1);

    /** Как долго после последнего хода разговор считается ещё живым. */
    private static final java.time.Duration TALK_GRACE = java.time.Duration.ofMinutes(10);

    /** Конец последнего живого хода. Пишет и читает один и тот же поток ядра. */
    private java.time.Instant lastTalk = Instant.EPOCH;

    private static final String ASK_REMINDER = """
            [system] Have you called #ask yet this turn? If the message involves personal topics, past events, \
            questions, or people you know — call #ask BEFORE send_telegram_message.""";

    private static final String ASK_MISSED = """
            [system] Note: you sent a message without consulting #ask this turn. Next time, call #ask before \
            send_telegram_message to enrich your response with memories and context.""";

    private static final String BE_TOOL_CENTRIC = """
            Your previous answer was plain text, and plain text does nothing: it is visible only to you. \
            Instruments are called through the tool_calls field of the assistant message — never as text \
            in the form 'tool call #send_telegram_message({"text": "hi"})'. That text is thrown away and \
            the turn fails.

            Repeat this step now and actually call the tool. If you truly need nothing, call #wait.""";

    private final LinkedBlockingDeque<Notification> queue = new LinkedBlockingDeque<>();
    private final List<Message> context = new ArrayList<>();

    /** Сколько раз подряд текущее уведомление уже спотыкалось о пропавшую сеть. */
    private int retries;
    private Turn active;
    private Notification retrying;

    /**
     * Уведомления, которым не досталось ответа: попытки кончились раньше, чем
     * закончился сбой. Долбиться в отказавший эндпоинт бессмысленно, поэтому
     * они ждут здесь и возвращаются в очередь, стоит модели ответить хоть чему.
     */
    private final java.util.Deque<Notification> stranded = new java.util.ArrayDeque<>();

    /** Больше стольких не копить: ответить на все нечем, а разговор уже устарел. */
    private static final int STRANDED_LIMIT = 20;

    private boolean suspended;
    private java.util.function.BiConsumer<Notification, RuntimeException> onFailure = (n, e) -> {};

    /**
     * Что сделать с чатом уведомления, которое потеряно навсегда. Само по себе
     * потерянное уведомление не видно: чат открыт, значит прочитан, и при
     * следующем запуске догон его не найдёт.
     */
    private volatile java.util.function.Consumer<Notification> backToUnread = notification -> {};

    /** Чем обернулся отказ: новая попытка, ожидание пока эндпоинт оживёт, или потеря. */
    private enum Loss { RETRY, PARKED, LOST }

    private static final class Turn {
        final Notification notification;
        final long startedAt = System.currentTimeMillis();
        long tokens;
        int step;
        boolean recall = true;
        boolean asked;
        boolean prepared;
        /** До первого текстового промаха шаги свободные, дальше до конца хода — только вызовы. */
        boolean forceToolCall;
        Turn(Notification notification) { this.notification = notification; }
    }

    public void onNotificationFailed(java.util.function.BiConsumer<Notification, RuntimeException> action) {
        onFailure = action;
    }

    /** Кому возвращать чаты уведомлений, до которых не дошла очередь. */
    public void backToUnread(java.util.function.Consumer<Notification> action) {
        backToUnread = action;
    }

    private final LlmClient llm;
    private final SystemPrompt systemPrompt;
    private final Toolbox alwaysAvailable;
    private final long contextTokenLimit;

    /** Что делать с распухшим контекстом. Позже здесь появится запись в дневник. */
    private Runnable onContextOverflow = () -> {};

    private Memory memory = Memory.NONE;

    /**
     * Что дописать к системному промпту, когда известно, кому она отвечает.
     * Доверие к человеку меняет тон — это и есть дописка. Пусто по умолчанию:
     * не каждое событие про человека.
     */
    private volatile java.util.function.LongFunction<String> aboutPerson = id -> "";

    private Rest rest;

    /** Ход закончился — она отложила телефон. */
    private Runnable onIdle = () -> { };

    /** Что сделать, когда ход закончен. Слушателей несколько: закрыть чаты, снять признак порыва. */
    private final List<java.util.function.Consumer<Notification>> onNotificationDone = new ArrayList<>();

    @FunctionalInterface
    public interface SystemPrompt {
        String text();
    }

    public NotificationLoop(LlmClient llm, SystemPrompt systemPrompt, Toolbox alwaysAvailable, long contextTokenLimit) {
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.alwaysAvailable = alwaysAvailable;
        this.contextTokenLimit = contextTokenLimit;
    }

    public void onContextOverflow(Runnable action) {
        this.onContextOverflow = action;
    }

    public void memory(Memory memory) {
        this.memory = memory;
    }

    public void aboutPerson(java.util.function.LongFunction<String> suffix) {
        this.aboutPerson = suffix;
    }

    public void rest(Rest rest) {
        this.rest = rest;
    }

    public void onIdle(Runnable action) {
        this.onIdle = action;
    }

    /**
     * Просит закончить: начатый ход доводится до конца, новые не берутся.
     * Обрывать разговор на полуслове нельзя — то, что уже сказано, ещё
     * не сохранено, и человек останется с недописанной фразой.
     */
    public void stop() {
        stopping = true;
        if (rest != null) {
            rest.stop();   // если она спит до утра, будим: закрываемся
        }
        queue.addFirst(STOP);   // будим ожидание, если она стоит в тишине
    }

    /** @return успела ли закончить сама */
    public boolean awaitStopped(java.time.Duration limit) {
        try {
            return stopped.await(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Для проверок: как долго молчание считается тишиной. */
    void idleCheck(java.time.Duration howOften) {
        this.idleCheck = howOften;
    }

    /** Для проверок: сколько ждать, прежде чем вернуться к уведомлению. */
    void retryPause(Duration pause) {
        this.retryPause = pause;
    }

    public void onNotificationDone(java.util.function.Consumer<Notification> action) {
        this.onNotificationDone.add(action);
    }

    public void submit(Notification notification) {
        queue.addLast(notification);
    }

    /** Срочное уведомление — попадёт в начало очереди. */
    public void submitFirst(Notification notification) {
        queue.addFirst(notification);
    }

    /** Убирает устаревшие уведомления — например, когда чат уже открыт вручную. */
    public void discardMatching(String substring) {
        queue.removeIf(notification -> notification.text().contains(substring));
    }

    /** Сколько уведомлений ждёт разбора. */
    public int queued() {
        return queue.size();
    }

    /** Сколько так и не дождались ответа — но ещё не забыты. */
    int strandedCount() {
        return stranded.size();
    }

    /**
     * Живой разговор, спать нельзя: в очереди непрочитанное, есть то, что не
     * дождалось ответа, или последний ход кончился только что. Отсчитывает Rest
     * перед уходом в ночь.
     */
    public boolean talkAlive() {
        return !queue.isEmpty() || active != null || !stranded.isEmpty()
                || Duration.between(lastTalk, Instant.now()).compareTo(TALK_GRACE) < 0;
    }

    public List<Message> context() {
        return List.copyOf(context);
    }

    public void clearContext() {
        context.clear();
    }

    /** Бесконечный цикл. Прерывается только остановкой потока. */
    public void run() {
        try {
            runUntilStopped();
        } finally {
            // Отложенное без ответа умирает вместе с процессом: без этого его
            // чат остался бы прочитанным, и при следующем запуске догон его не
            // найдёт — ровно так потерялось сообщение владельца 22.10
            for (Notification parked : stranded) {
                giveUp(parked, "бот остановился");
            }
            stranded.clear();
        }
    }

    private void runUntilStopped() {
        while (!Thread.currentThread().isInterrupted()) {
            Notification taken = null;
            try {
                if (rest != null) {
                    // отходит перед тем, как взяться за уведомление: накопившееся
                    // прочитается разом, как это и бывает у человека
                    rest.maybeRest();
                }
                // ждём не бесконечно: разговор затих — значит через минуту снова
                // проверим, не пора ли спать. Иначе разбуженная среди ночи она
                // так и осталась бы на ногах до утра, пока ей не напишут ещё раз
                Notification notification;
                if (retrying != null) {
                    notification = retrying;
                    retrying = null;
                } else {
                    notification = queue.poll(idleCheck.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                if (stopping) {
                    log.info("Заканчиваю: новых уведомлений больше не беру");
                    stopped.countDown();
                    return;
                }
                if (notification == null) {
                    continue;
                }
                taken = notification;
                if (suspended) {
                    onFailure.accept(notification, new IllegalStateException("Запросы приостановлены до перезапуска после ошибки оплаты/доступа"));
                    notifyDone(notification);
                    giveUp(notification, "запросы приостановлены");
                    continue;
                }
                process(notification);
                retries = 0;
                releaseStranded();
                notifyDone(notification);
                onIdle.run();
                // ход кончился — разговор только что был живым, ночь подождёт
                lastTalk = Instant.now();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped.countDown();
                return;
            } catch (RuntimeException e) {
                if (Fatal.check(e)) {
                    return;
                }
                Loss loss = retryLater(taken, e);
                if (loss == Loss.RETRY) {
                    continue;
                }
                log.error("Не удалось обработать уведомление", e);
                if (taken != null) {
                    try { onFailure.accept(taken, e); }
                    catch (RuntimeException saveFailure) { log.error("Не удалось сохранить необработанное уведомление", saveFailure); }
                    notifyDone(taken);
                }
                if (loss == Loss.LOST) {
                    giveUp(taken, "ход не получился");
                }
                active = null;
                retries = 0;
                if (e instanceof ru.rainedev.raine.llm.LlmFailure failure && failure.requiresOperator()) {
                    suspended = true;
                    log.error("Запросы приостановлены: проверьте баланс/доступ и перезапустите бота");
                }
                recoverFrom(e);
            }
        }
        // Поток прервали, не дожидаясь конца: пауза между попытками умеет
        // просыпаться на остановку, но флаг прерывания всё равно может решить
        // исход раньше. Остановившейся стороне об этом тоже надо знать, иначе
        // выключение будет ждать свой двухминутный предел впустую.
        stopped.countDown();
    }

    /** Обработка одного уведомления целиком — до wait/pause. */
    public void process(Notification notification) {
        boolean resuming = active != null && active.notification == notification;
        if (!resuming) active = new Turn(notification);
        Turn turn = active;
        long startedAt = turn.startedAt;
        long spentTokens = turn.tokens;
        if (!resuming) {
            // «тебе написали» открытый чат заменяет собой: в нём и так всё видно.
            // А вот «снимок готов, вот имя файла» в переписке не написано нигде —
            // выбросив этот текст, мы оставили бы её ждать того, что уже случилось
            String opened = openImmediately(notification).orElse("");
            String text;
            if (opened.isEmpty()) {
                text = notification.text();
            } else if (notification.ownText()) {
                text = notification.text() + "\n\n" + opened;
            } else {
                text = opened;
            }
            context.add(Message.user(text + "\nCurrent time: " + Instant.now() + " UTC"));

        }
        boolean recallMemories = turn.recall;
        boolean askedThisTurn = turn.asked;
        for (int step = turn.step; step < MAX_STEPS_PER_NOTIFICATION; step++) {
            turn.step = step;
            if (!turn.prepared && recallMemories) {
                recallIntoContext();
            }
            // набор пересобирается каждый шаг: инструменты появляются по ходу дела.
            // открытие чата добавляет отправку — со снимком, снятым заранее, её бы не было
            Toolbox tools = withFinishingTools(notification.tools());
            if (!turn.prepared && !askedThisTurn && tools.names().contains("ask")) {
                // напоминание встаёт прямо перед выбором действия, чтобы модель его заметила
                appendToLast(ASK_REMINDER);
            }
            turn.prepared = true;
            String toolChoice = turn.forceToolCall ? LlmClient.TOOL_CHOICE_REQUIRED : LlmClient.TOOL_CHOICE_AUTO;
            // дописка про человека стоит рядом с промптом, а не в контексте: в
            // пересказ для дневника она попасть не должна
            ChatResponse response = llm.chat(systemPrompt.text() + aboutPerson.apply(notification.personId()),
                    context, tools.asJson(), toolChoice);
            turn.prepared = false;
            turn.step = step + 1;
            spentTokens += response.totalTokens();
            turn.tokens = spentTokens;
            List<ToolCall> calls = response.toolCalls();

            // мысли и намерения — то, ради чего вообще стоит смотреть в лог
            if (response.text() != null && !response.text().isBlank()) {
                log.info("Мысли: {}", response.text().strip());
            }
            if (!calls.isEmpty()) {
                log.info("Действия: {}", calls.stream().map(ToolCall::name).toList());
            }

            if (calls.isEmpty()) {
                // ответ текстом действием не является — напоминаем, и до конца
                // хода текст запрещён: каждый следующий шаг обязан содержать вызов
                log.warn("Модель не вызвала ни одного инструмента, шаг {}", step);
                context.add(Message.user(BE_TOOL_CENTRIC));
                turn.forceToolCall = true;
                continue;
            }

            List<Message> results;
            try {
                // инструменты открытого чата остаются до конца хода, а не до конца
                // шага: иначе на следующем шаге вернутся инструменты прежнего чата,
                // и реакция или сообщение уйдут не туда
                results = tools.invoke(calls, appeared -> {
                    tools.add(appeared);
                    notification.tools().add(appeared);
                });
            } catch (Toolbox.PartialFailure e) {
                // Earlier actions are real: retain the complete tool protocol and do not replay them.
                context.add(response.firstMessage().orElseThrow());
                context.addAll(e.results());
                appendToLast("Earlier successful actions have already happened. Do not repeat them. " + e.getMessage());
                turn.recall = false;
                recallMemories = false;
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                    lastTalk = Instant.now();
                    active = null;
                    return;
                }
                continue;
            } catch (LowQualityException e) {
                // откат: сообщение модели в контекст не попадает, вместо него — подсказка
                log.info("Ответ низкого качества, пробуем иначе: {}", e.getMessage());
                context.add(Message.user(e.getMessage()));
                if (response.totalTokens() > contextTokenLimit) {
                    log.warn("Контекст переполнен, а подходящий ответ не найден — сбрасываем");
                    onContextOverflow.run();
                    lastTalk = Instant.now();
                    active = null;
                    return;
                }
                continue;
            }

            context.add(response.firstMessage().orElseThrow());
            context.addAll(results);

            // после отправки сообщения за воспоминаниями не ходим: подмешанные между
            // репликами, они сбивают мысль, и вместо связного ответа выходит поток
            // случайных фактов
            recallMemories = calls.stream().noneMatch(call -> "send_telegram_message".equals(call.name()));
            askedThisTurn |= calls.stream().anyMatch(call -> "ask".equals(call.name()));
            turn.asked = askedThisTurn;
            turn.recall = recallMemories;
            if (!askedThisTurn && !recallMemories) {
                appendToLast(ASK_MISSED);
            }

            if (calls.stream().anyMatch(call -> Toolbox.FINISHING.contains(call.name()))) {
                reportCost(startedAt, spentTokens, step + 1);
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                }
                // ход кончился — разговор только что был живым, ночь подождёт
                lastTalk = Instant.now();
                active = null;
                return;
            }

            if (!tools.isEmpty()) {
                appendToLast("\nWhat's your next action? Use a `tool` to act. Use #ask to consult with your "
                        + "knowledge database. The following tools available: " + String.join(", ", tools.names()));
            }
        }

        reportCost(startedAt, spentTokens, MAX_STEPS_PER_NOTIFICATION);
        log.warn("Достигнут предел шагов — прекращаем ход принудительно");
        lastTalk = Instant.now();
        active = null;
    }

    /**
     * Во что обошёлся один разговор. Без этой строки понять, сколько стоит
     * ответить человеку, можно только по счёту у поставщика в конце месяца.
     */
    private static void reportCost(long startedAt, long tokens, int steps) {
        log.info("Ход закончен: {} c, шагов {}, израсходовано {} токенов",
                (System.currentTimeMillis() - startedAt) / 1000, steps, tokens);
    }

    private void notifyDone(Notification notification) {
        for (var listener : onNotificationDone) {
            try {
                listener.accept(notification);
            } catch (RuntimeException e) {
                // уборка после хода не должна ронять цикл
                log.warn("Не удалось завершить ход: {}", e.getMessage());
            }
        }
    }

    private void appendToLast(String addition) {
        Message last = context.getLast();
        context.set(context.size() - 1, last.withContent(last.content() + "\n" + addition));
    }

    /**
     * Если уведомление предлагает единственное действие «открыть» без параметров,
     * выполняем его сразу. Модель открывает чат в ста случаях из ста, и спрашивать
     * об этом — лишнее обращение к ней на каждое сообщение.
     */
    private java.util.Optional<String> openImmediately(Notification notification) {
        var tools = notification.tools().all();
        if (tools.size() != 1) {
            return java.util.Optional.empty();
        }
        Tool only = tools.iterator().next();
        if (!"open".equals(only.name()) || !only.parameters().path("properties").isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            log.debug("Открываю чат сразу, не спрашивая модель");
            // инструменты, которые откроет чат, попадают в набор уведомления —
            // вместе с ним они и исчезнут
            return java.util.Optional.of(
                    only.handler().call(only.parameters().objectNode(), notification.tools()::add));
        } catch (RuntimeException e) {
            log.warn("Не удалось открыть чат сразу: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** Воспоминания встают перед последним сообщением — как будто вспомнилось до ответа. */
    private void recallIntoContext() {
        String recalled = memory.recall(context);
        if (recalled.isEmpty()) {
            return;
        }
        Message last = context.getLast();
        context.set(context.size() - 1, last.withContent(recalled + last.content()));
    }

    /** wait и pause доступны всегда: иначе ход невозможно закончить. */
    private Toolbox withFinishingTools(Toolbox notificationTools) {
        Toolbox tools = new Toolbox();
        // правами владеет постоянный набор: чат, открытый по ходу дела, не должен
        // вернуть то, что владелец снял
        tools.actionsAllowed(alwaysAvailable.allowsActions() && notificationTools.allowsActions());
        alwaysAvailable.all().forEach(tools::add);
        notificationTools.all().forEach(tools::add);
        tools.add(Tool.simple("wait", "Wait until further notifications", arguments -> "Success"));
        tools.add(Tool.simple("pause", "Pauses the conversation", arguments -> "Success"));
        return tools;
    }

    /**
     * Пропавшая сеть не должна стоить ей сообщения.
     * <p>
     * Клиент уже пробовал трижды подряд; здесь ожидания растут — минута, две,
     * четыре, дальше с потолком в десять, — потому что занятая модель оттаивает
     * десятками минут. Повтор продолжает тот же ход: контекст и шаг сохранены,
     * выполненные действия не делаются второй раз.
     *
     * @return чем обернулся отказ — новая попытка, отложение или потеря
     */
    private Loss retryLater(Notification notification, RuntimeException failure) {
        boolean network = failure instanceof UncheckedIOException;
        boolean busy = failure instanceof ru.rainedev.raine.llm.LlmFailure endpoint && endpoint.retryable();
        if (notification == null || !(network || busy) || stopping) {
            return Loss.LOST;
        }
        if (++retries > MAX_RETRIES) {
            log.error("Эндпоинт не отвечает за {} попыток — откладываю, пока не оживёт", MAX_RETRIES);
            retries = 0;
            strand(notification);
            return Loss.PARKED;
        }
        // Resume the existing turn and context: previous tool actions cannot be rolled back.
        retrying = notification;
        Duration pause = pauseBeforeAttempt(retryPause, retries);
        log.warn("{}, вернусь к уведомлению через {} с (попытка {} из {})",
                busy ? "модель занята" : "сеть недоступна", pause.toSeconds(), retries, MAX_RETRIES);
        sleep(pause);
        return Loss.RETRY;
    }

    /** Пауза перед попыткой: с каждой вдвое длиннее, но не больше потолка. */
    static Duration pauseBeforeAttempt(Duration base, int attempt) {
        Duration pause = base;
        for (int doubling = 1; doubling < attempt && pause.compareTo(RETRY_PAUSE_CEILING) < 0;
                doubling++) {
            Duration longer = pause.multipliedBy(2);
            pause = longer.compareTo(RETRY_PAUSE_CEILING) > 0 ? RETRY_PAUSE_CEILING : longer;
        }
        return pause;
    }

    /**
     * Отложить без ответа: ждёт, пока модель снова ответит хоть чему-нибудь.
     * В очереди такому не место — там оно сошло бы за новое и удвоило бы
     * реплику в контексте.
     */
    private void strand(Notification notification) {
        while (stranded.size() >= STRANDED_LIMIT) {
            Notification forgotten = stranded.removeFirst();
            log.warn("Неотвеченных уже накопилось {}, самое давнее забываю", STRANDED_LIMIT);
            giveUp(forgotten, "неотвеченных больше " + STRANDED_LIMIT);
        }
        stranded.addLast(notification);
    }

    /**
     * Уведомление потеряно: ответа на него не будет уже никогда, не только в
     * этом процессе. Чат, который под этим уведомлением открыли, Telegram
     * пометил прочитанным, и догон при следующем запуске его не увидит — поэтому
     * отметку надо снять.
     */
    private void giveUp(Notification notification, String reason) {
        if (notification == null) {
            return;
        }
        log.warn("Уведомление потеряно ({}): возвращаю чат в непрочитанные", reason);
        try {
            backToUnread.accept(notification);
        } catch (RuntimeException e) {
            // потерянное уведомление не должно ронять цикл
            log.error("Не удалось вернуть чат в непрочитанные", e);
        }
    }

    /** Эндпоинт ожил — возвращаем в очередь всё, что не досказали. */
    private void releaseStranded() {
        if (stranded.isEmpty()) {
            return;
        }
        int count = stranded.size();
        while (!stranded.isEmpty()) {
            queue.addLast(stranded.removeFirst());
        }
        log.info("Модель снова отвечает — возвращаю к разговору {} уведомл.", count);
    }

    /**
     * Ждёт паузу, но просыпается на остановку. Паузы растут, а время, отведённое
     * на штатное выключение, нет: десять минут сна переживают его, и ход
     * продолжился бы уже по закрывающемуся клиенту.
     */
    private void sleep(Duration pause) {
        long deadline = System.currentTimeMillis() + pause.toMillis();
        try {
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return;
                }
                Thread.sleep(Math.min(1000, remaining));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Испорченный контекст проще выбросить, чем чинить: с ним не проходит ни
     * один следующий запрос, и она молчала бы навсегда. Но «json» встречается
     * и в отказах, которые касаются самого эндпоинта, — живой разговор из-за
     * них терять незачем. Сбрасываем только когда отвергнут наш запрос.
     */
    private void recoverFrom(RuntimeException e) {
        if (e instanceof ru.rainedev.raine.llm.LlmFailure failure && failure.status() == 400
                && String.valueOf(failure.getMessage()).toLowerCase().contains("json")) {
            log.warn("Контекст повреждён, сбрасываем");
            context.clear();
        }
    }
}
