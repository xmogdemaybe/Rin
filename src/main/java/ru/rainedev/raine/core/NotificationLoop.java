package ru.rainedev.raine.core;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * Сколько шагов подряд модель может не звать ни одного инструмента. Живой
     * ответ спотыкается один-два раза, третий подряд означает, что она упёрлась,
     * и каждый следующий шаг — это полный контекст за деньги.
     */
    private static final int MAX_EMPTY_STEPS = 3;

    /** Сколько раз возвращаться к уведомлению, пока не вернётся сеть. */
    private static final int MAX_RETRIES = 5;

    /** Пауза между такими возвращениями: сеть здесь пропадает на минуты, не на секунды. */
    private Duration retryPause = Duration.ofSeconds(60);

    /**
     * Сколько ждать перед единственным повтором после отказа контент-фильтра.
     * Секунда: вердикт входной безопасности приходит сразу, ждать дольше
     * ничего «остудить» не может — очередь стоит, а человек остаётся без ответа.
     */
    private Duration filterRetryPause = Duration.ofSeconds(1);

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

    /** Как долго после настоящего ответа разговор считается ещё живым. */
    private static final java.time.Duration TALK_GRACE = java.time.Duration.ofMinutes(10);

    /** Сколько должно пройти после настоящего ответа, чтобы порыв был уместен. */
    private static final java.time.Duration IMPULSE_QUIET =
            java.time.Duration.ofMinutes(Spontaneity.MIN_MINUTES);

    /** Когда закончился последний ход, в котором она что-то сделала. */
    private volatile java.time.Instant lastTalk = Instant.EPOCH;

    /** Когда она отвечала тому, кто написал ей: без её собственных слов. */
    private volatile Instant lastAnswer = Instant.EPOCH;

    /** Чем закончился последний ход, в котором она что-то сделала. */
    private volatile String lastTalkAction = "";

    /**
     * Про синтаксис здесь не пишется: модель повторяет то, что видит, и запрет,
     * показанный примером, работает как подсказка.
     */
    private static final String BE_TOOL_CENTRIC = """
            Your previous answer was plain text, and plain text does nothing: it is visible only to you. \
            Instruments are called through the tool_calls field of the assistant message, never as a line \
            of text describing them. Such a line is thrown away and the turn fails.

            Repeat this step now and actually call the tool. If you truly need nothing, call #wait.""";

    /**
     * Подсказка на каждом шаге: чем можно закончить ход. Память сюда не
     * предлагается — вспоминание решает код, а не напоминание.
     */
    static final String NEXT_ACTION_PREFIX =
            "What's your next action? Use a `tool` to act. The following tools available: ";

    /** Записка после частичного сбоя: часть действий уже состоялась. */
    static final String PARTIAL_NOTE_PREFIX =
            "Earlier successful actions have already happened. Do not repeat them. ";

    /**
     * Всё, что цикл дописывает в чужой текст. Пересказ дневника вырезает это по
     * списку: новая приписка, не попавшая сюда, однажды уедет в дневник как
     * слова разговора.
     */
    public static List<String> serviceInsertions() {
        return List.of(BE_TOOL_CENTRIC, NEXT_ACTION_PREFIX, PARTIAL_NOTE_PREFIX);
    }

    /**
     * История без отработанных пинков и черновика шага, который они напоминали
     * исполнить. Тем, кто пересказывает разговор позже (дневник, трёхдневная
     * сводка памяти), напоминание цикла и её замысел — не реплики разговора:
     * без этого пара жила бы в сводке дословно. Исходный список не правится.
     */
    public static List<Message> withoutServedPings(List<Message> history) {
        List<Message> kept = new ArrayList<>(history);
        dropServedPings(kept);
        return kept;
    }

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
        boolean prepared;
        /** До первого текстового промаха шаги свободные, дальше до конца хода — только вызовы. */
        boolean forceToolCall;
        /** Сколько шагов подряд модель не позвала ни одного инструмента. */
        int emptySteps;
        /** Повтор после отказа контент-фильтра сделан ровно один раз за ход. */
        boolean filterRetried;
        /** Что вклеила память в этом ходе — чтобы снять, если ход провалился. */
        RecallSnapshot recallSnapshot;
        /** Последнее действие, видимое человеку: send_telegram_message, react_with_emoji. */
        String lastAction = "";
        /** Чем был контекст до хода порыва: холостый ход не должен оставлять следа. */
        List<Message> contextBefore;
        Origin originBefore;
        Runnable undoOutside;
        Turn(Notification notification) { this.notification = notification; }
    }

    /**
     * Снимок вклейки: куда она легла, чем было сообщение до неё и что память
     * уже считала вспомненным. Нужен, чтобы ход, зарубленный фильтром на
     * пустом месте, не оставлял в истории страницы дневника, которых модель
     * так и не увидела в ответе.
     */
    private record RecallSnapshot(int index, Message original, Set<Long> injectedBefore) {}

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

    /** Что сделать, когда контекст выброшен: вместе с ним забываются снимки чатов. */
    private Runnable onContextCleared = () -> {};

    /** Откат того, что живёт вне контекста, если порыв кончился молчанием. */
    private java.util.function.Supplier<Runnable> outsideContext = () -> () -> {};

    private Memory memory = Memory.NONE;

    /**
     * Что дописать к системному промпту, когда известно, кому она отвечает.
     * Доверие к человеку меняет тон — это и есть дописка. Пусто по умолчанию:
     * не каждое событие про человека.
     */
    private volatile java.util.function.LongFunction<String> aboutPerson = id -> "";

    /**
     * Что сейчас вокруг неё: время суток и погода. Дописывается к новому
     * сообщению хода, а не к системному промпту: неизменный префикс запроса
     * должен оставаться байт в байт, иначе кэш посредника не срабатывает.
     */
    private volatile java.util.function.Supplier<String> surroundings = () -> "";

    /**
     * Откуда чат уведомления и насколько он закрыт. Спрашивается в тот момент,
     * когда текст чата ложится в контекст, а не когда уведомление создано: событие
     * из очереди иначе приписало бы свой чат чужому разговору, который ещё не
     * сохранён.
     */
    private volatile java.util.function.LongFunction<Origin> chatOrigin = id -> Origin.GENERAL;

    /**
     * Доверие к тому, кому она отвечает: по нему память решает, что показывать.
     * Нет человека в файле доверия — ноль, как и в разговоре.
     */
    private volatile java.util.function.LongFunction<Integer> trustOf = id -> 0;

    /**
     * Откуда родом то, что уже легло в контекст: строжайшая метка затронутых
     * чатов и чат, который её задал. Разговор задевает несколько чатов, а запись
     * в дневник уходит из него одна. Пишет поток ядра, читает и тот, кто
     * останавливает бота, — поэтому одна пара на атомарную замену.
     */
    private final java.util.concurrent.atomic.AtomicReference<Origin> contextOrigin =
            new java.util.concurrent.atomic.AtomicReference<>(Origin.GENERAL);

    /**
     * Кому память показывает записи прямо сейчас. Во время хода читается и из
     * подагента поиска, поэтому volatile.
     */
    private volatile Audience audience = Audience.STRANGER;

    private Rest rest;

    /** Ход закончился — она отложила телефон. */
    private Runnable onIdle = () -> { };

    /** Перед выходом потока цикла: куда перенести недосказанный разговор. */
    private Runnable onLoopStopped = () -> { };

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

    /** Куда позвонить, когда контекст выброшен: снимки чатов пора забыть. */
    public void onContextCleared(Runnable action) {
        this.onContextCleared = action;
    }

    public void memory(Memory memory) {
        this.memory = memory;
    }

    public void aboutPerson(java.util.function.LongFunction<String> suffix) {
        this.aboutPerson = suffix;
    }

    /** Откуда брать окружение хода: время суток и погоду. */
    public void surroundings(java.util.function.Supplier<String> suffix) {
        this.surroundings = suffix;
    }

    /**
     * Начал ли текущий ход этот человек. Нужно решениям, которым важен
     * инициатор: подписка на канал и письмо незнакомому по наводке владельца
     * разрешаются в его ходу, а не по тому, какой чат сейчас открыт.
     */
    public boolean startedBy(long personId) {
        Turn turn = active;
        return turn != null && personId != 0
                && turn.notification.trigger() == Notification.Trigger.INCOMING
                && turn.notification.personId() == personId;
    }

    public void chatOrigin(java.util.function.LongFunction<Origin> origin) {
        this.chatOrigin = origin;
    }

    public void trustOf(java.util.function.LongFunction<Integer> level) {
        this.trustOf = level;
    }

    /** Чат вошёл в контекст: метка разговора становится не мягче его. */
    public void noteOrigin(Origin origin) {
        contextOrigin.accumulateAndGet(origin, Origin::strictest);
    }

    public Origin contextOrigin() {
        return contextOrigin.get();
    }

    /** Кому этот ход показывает память. */
    public Audience audience() {
        return audience;
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

    /** Что делает поток цикла перед самым выходом: переносит разговор в память. */
    public void onLoopStopped(Runnable action) {
        this.onLoopStopped = action;
    }

    /**
     * Поток цикла сворачивает работу. Разговор в память переносит он сам:
     * история живёт здесь, и тот, кто ждёт останова из другого потока, не имеет
     * права её читать или чистить, пока ход ещё идёт.
     */
    private void finish() {
        try {
            onLoopStopped.run();
        } catch (RuntimeException e) {
            log.warn("Разговор при остановке не доехал в дневник: {}", e.getMessage());
        }
        stopped.countDown();
    }

    /** Для проверок: как долго молчание считается тишиной. */
    void idleCheck(java.time.Duration howOften) {
        this.idleCheck = howOften;
    }

    /** Для проверок: сколько ждать, прежде чем вернуться к уведомлению. */
    void retryPause(Duration pause) {
        this.retryPause = pause;
    }

    /** Для проверок: повтор после отказа фильтра не должен стоить секунды теста. */
    void filterRetryPause(Duration pause) {
        this.filterRetryPause = pause;
    }

    /** Для проверок: отмотать часы последнего разговора — ждать по-настоящему нельзя. */
    void windBackLastTalk(Duration by) {
        if (!lastTalk.equals(Instant.EPOCH)) {
            lastTalk = Instant.now().minus(by);
        }
    }

    /**
     * Что ещё откатывать, когда порыв кончился молчанием: память помнит, что
     * уже выдавала, а экран чата — что уже показывал. Оба знания переживают
     * откат контекста, если про них забыть.
     */
    public void idleImpulseSnapshot(java.util.function.Supplier<Runnable> capture) {
        this.outsideContext = capture;
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
     * дождалось ответа, или она недавно отвечала тому, кто написал. Отсчитывает
     * Rest перед уходом в ночь.
     * <p>
     * Ход без видимого действия разговором не был: молчаливый {@code wait} на
     * пост из канала держал бы её на ногах каждые полчаса — вплоть до предела
     * терпения. Её собственные слова — порыв написать первой, прощание на ночь,
     * приветствие с утра — тоже: их никто не ждёт, а прощание, отмерившее ей
     * ещё десять минут бодрствования, само и будил её до полночи.
     */
    public boolean talkAlive() {
        return !queue.isEmpty() || active != null || !stranded.isEmpty() || answeredAgo(TALK_GRACE);
    }

    /**
     * Недавно она отвечала тому, кто написал ей?
     *
     * @param limit чем старше ответ, тем он не считается
     */
    private boolean answeredAgo(Duration limit) {
        Instant at = lastAnswer;
        return !at.equals(Instant.EPOCH) && Duration.between(at, Instant.now()).compareTo(limit) < 0;
    }

    /**
     * Недавно она отвечала по-настоящему — своим или чужим сообщением?
     *
     * @param limit чем старше ответ, тем он не считается
     */
    private boolean talkedAgo(Duration limit) {
        Instant at = lastTalk;
        return !at.equals(Instant.EPOCH) && Duration.between(at, Instant.now()).compareTo(limit) < 0;
    }

    /**
     * Недавно она отвечала по-настоящему? Порыву сейчас не место: он придёт
     * либо поверх живого разговора, либо через минуту после того, как она сама
     * всё сказала.
     */
    /** Порывы — и разговорный, и в свой канал — молчат, пока переписка жива. */
    public boolean talkedRecently() {
        return talkedAgo(IMPULSE_QUIET);
    }

    public List<Message> context() {
        return List.copyOf(context);
    }

    /** Краткий статус последнего завершённого разговора для спонтанного порыва. */
    public String lastTalkStatus() {
        Instant at = lastTalk;
        if (at.equals(Instant.EPOCH)) {
            return "";
        }
        String when = ago(Duration.between(at, Instant.now()));
        if (lastTalkAction.isEmpty()) {
            // молчание не было действием и напоминать о нём незачем: «в прошлый
            // раз ты промолчала» провоцирует молчать и дальше
            return "The last conversation ended %s.".formatted(when);
        }
        return "The last conversation ended %s; the last action was %s.".formatted(when, lastTalkAction);
    }

    private static String ago(Duration elapsed) {
        long minutes = elapsed.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        long hours = elapsed.toHours();
        if (hours < 24) {
            long rest = minutes % 60;
            return rest == 0
                    ? hours + (hours == 1 ? " hour ago" : " hours ago")
                    : hours + " h " + rest + " min ago";
        }
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    public void clearContext() {
        context.clear();
        // разговор ушёл в дневник — следующему его метка достаться не должна
        contextOrigin.set(Origin.GENERAL);
        // сообщений прошлых снимков в контексте больше нет, а метка «это уже
        // читали» осталась бы — следующие снимки вышли бы пустыми
        onContextCleared.run();
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
                    finish();
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
        if (!resuming && notification.trigger() == Notification.Trigger.IMPULSE && talkedRecently()) {
            // утреннее приветствие уже написало владельцу две строки назад, а
            // порыв стоит в очереди за ним: выпускать его к модели — значит
            // платить за отказ заново всем контекстом
            log.info("Порыв пропущен: с последнего ответа {} мин",
                    Duration.between(lastTalk, Instant.now()).toMinutes());
            return;
        }
        if (!resuming) {
            active = new Turn(notification);
            if (notification.trigger() == Notification.Trigger.IMPULSE) {
                // молчание после порыва не должно оставить в истории ни его
                // промпта, ни потраченных на него воспоминаний
                active.contextBefore = List.copyOf(context);
                active.originBefore = contextOrigin.get();
                active.undoOutside = outsideContext.get();
            }
        }
        Turn turn = active;
        long startedAt = turn.startedAt;
        long spentTokens = turn.tokens;
        // откуда чат и кому этот ход отвечает: снимается один раз на вход и для
        // зрителя, и для разговора, который из него выйдет
        Origin from = notification.chatId() == 0 ? null : chatOrigin.apply(notification.chatId());
        audience = audienceOf(notification, from);
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
            String suffix = surroundings.get();
            String around = suffix == null || suffix.isBlank() ? "" : "\n" + suffix.strip();
            Message screen = Message.user(text + "\nCurrent time: " + Instant.now() + " UTC" + around);
            retireStaleScreens(List.of(screen), true);
            context.add(screen);
            // метка снимается здесь, а не когда уведомление создано: событие из
            // очереди иначе приписало бы свой чат чужому разговору, который ещё
            // не сохранён, а новый контекст остался бы без метки вовсе
            if (from != null) {
                noteOrigin(from);
            }
        }
        boolean recallMemories = turn.recall;
        for (int step = turn.step; step < MAX_STEPS_PER_NOTIFICATION; step++) {
            turn.step = step;
            if (recallMemories) {
                // память поднимается один раз за ход — на шаге 0, по входящему
                // сообщению. На шаге 1 и дальше «последние три сообщения» — это
                // уже мысли модели и ответы тулз, запрос выходит другим, и
                // каждый шаг вытаскивал новые страницы: 112 077 токенов на одну
                // реакцию — они же
                turn.recallSnapshot = recallIntoContext();
                recallMemories = false;
                turn.recall = false;
            }
            // набор пересобирается каждый шаг: инструменты появляются по ходу дела.
            // открытие чата добавляет отправку — со снимком, снятым заранее, её бы не было
            Toolbox tools = withFinishingTools(notification.tools());
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

            // Пустой ответ с finish_reason=content_filter — вердикт входной
            // безопасности, а не забывчивость. Утро 18.11 показало, что он
            // выпадает и на тот же самый текст минутой позже (отказ 09:31:48,
            // успех 09:32:47 при идентичном промпте), поэтому один повтор того
            // же запроса стоит денег, но не бьётся в стенку. Больше одного —
            // снова стенка: платится весь контекст.
            if (step == 0 && calls.isEmpty() && filteredByPolicy(response)
                    && !turn.filterRetried && !stopping) {
                turn.filterRetried = true;
                log.warn("Отказ контент-фильтром на шаге 0 ({} токенов) — повторяю шаг без вклейки памяти",
                        response.totalTokens());
                // Страницы дневника — единственное, что этот ход принёс сам и что
                // он умеет снять: повтор без них идёт уже другим запросом, а не
                // дословно тем, который фильтр забраковал (вечер 18.11: 21 679
                // токенов дважды подряд). Ответить без воспоминаний лучше, чем
                // промолчать из-за отказа, а снятые страницы вернутся в выборку.
                undoRecall(turn);
                sleep(filterRetryPause);
                if (!stopping) {
                    response = llm.chat(systemPrompt.text() + aboutPerson.apply(notification.personId()),
                            context, tools.asJson(), toolChoice);
                    spentTokens += response.totalTokens();
                    turn.tokens = spentTokens;
                    calls = response.toolCalls();
                    log.warn("Повтор: finish_reason={}, вызовов {}, токенов {}, за ход всего {}",
                            response.finishReason(), calls.size(), response.totalTokens(), spentTokens);
                }
            }

            // мысли и намерения — то, ради чего вообще стоит смотреть в лог
            if (response.text() != null && !response.text().isBlank()) {
                log.info("Мысли: {}", response.text().strip());
            }
            if (!calls.isEmpty()) {
                log.info("Действия: {}", calls.stream().map(ToolCall::name).toList());
            }

            if (calls.isEmpty()) {
                // пустота пустоте рознь: без finish_reason в журнале остаётся
                // только номер шага, а отказ фильтра неотличим от «поболтала»
                String reason = response.finishReason();
                log.warn("Модель не вызвала ни одного инструмента: шаг {}, finish_reason={}, текста {} симв., "
                                + "токенов {}",
                        step, reason.isBlank() ? "нет" : reason, response.text().length(), response.totalTokens());
                if (filteredByPolicy(response)) {
                    // это не забывчивость, а вердикт: напоминание под required
                    // лишь умножит счёт, а повтор выше уже был свой
                    log.warn("Модель отказала контент-фильтром — ход прекращён{}",
                            turn.filterRetried ? " и после повтора" : "");
                    undoRecall(turn);
                    reportCost(startedAt, spentTokens, step + 1);
                    endTurn(turn);
                    active = null;
                    return;
                }
                if (++turn.emptySteps >= MAX_EMPTY_STEPS) {
                    // страховка от любой другой молчаливой петли, а не только от фильтра
                    log.warn("Подряд {} шагов без вызова инструментов — ход прекращён", turn.emptySteps);
                    reportCost(startedAt, spentTokens, step + 1);
                    endTurn(turn);
                    active = null;
                    return;
                }
                // Черновик остаётся в истории: на следующем шаге модель обязана
                // вызвать то, что замыслила. Без своего текста она выбирала заново:
                // 23:57:55 — «intention: поставить реакцию на последние сообщения»,
                // а шаг 1 под required ответил wait. В пересказ дневника этот блок
                // не уезжает: её заметки о намерениях снимает withoutMentalNotes.
                if (!response.text().isBlank()) {
                    context.add(response.firstMessage().orElseThrow());
                }
                // ответ текстом действием не является — напоминаем, и до конца
                // хода текст запрещён: каждый следующий шаг обязан содержать вызов
                context.add(Message.user(BE_TOOL_CENTRIC));
                turn.forceToolCall = true;
                continue;
            }
            turn.emptySteps = 0;

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
                retireStaleScreens(e.results(), false);
                context.add(response.firstMessage().orElseThrow());
                context.addAll(e.results());
                appendToLast("\n" + PARTIAL_NOTE_PREFIX + e.getMessage());
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                    endTurn(turn);
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
                    endTurn(turn);
                    active = null;
                    return;
                }
                continue;
            }

            // снимок открытого самим ходом чата приезжает ответом тулзы: обвязка
            // в нём свежее той, что лежала в истории
            retireStaleScreens(results, false);
            context.add(response.firstMessage().orElseThrow());
            context.addAll(results);

            String visible = visibleAction(calls, tools);
            if (!visible.isEmpty()) {
                turn.lastAction = visible;
            }

            if (calls.stream().anyMatch(call -> finishes(notification, tools, call))) {
                reportCost(startedAt, spentTokens, step + 1);
                if (response.totalTokens() >= contextTokenLimit) {
                    onContextOverflow.run();
                }
                // действие сделано — дальше ход решает endTurn
                endTurn(turn);
                active = null;
                return;
            }

            if (!tools.isEmpty()) {
                appendToLast("\n" + NEXT_ACTION_PREFIX + String.join(", ", tools.names()));
            }
        }

        reportCost(startedAt, spentTokens, MAX_STEPS_PER_NOTIFICATION);
        log.warn("Достигнут предел шагов — прекращаем ход принудительно");
        endTurn(turn);
        active = null;
    }

    /** Ход закончился: запоминаем видимое действие и откатываем холостой порыв. */
    private void endTurn(Turn turn) {
        if (!turn.lastAction.isEmpty()) {
            // молчание разговором не было: эти часы читают планировщик порывов и
            // решение лечь спать, — холостой порыв, обнулив их, сам же себя и
            // запрещал на полчаса вперёд, а перед сном не давал уснуть
            lastTalkAction = turn.lastAction;
            lastTalk = Instant.now();
            if (!turn.notification.unprompted()) {
                // её собственное слово — не обмен: сон по нему не откладывается
                lastAnswer = lastTalk;
            }
        }
        if (turn.contextBefore != null && turn.lastAction.isEmpty()) {
            rollbackIdleImpulse(turn);
        }
    }

    /**
     * Холостой порыв убирается целиком: его промпт, его шаги и то, что он успел
     * истратить вне контекста — выданные воспоминания и показанные снимки чата.
     */
    private void rollbackIdleImpulse(Turn turn) {
        List<Message> was = turn.contextBefore;
        if (context.size() >= was.size() && context.subList(0, was.size()).equals(was)) {
            context.clear();
            context.addAll(was);
            contextOrigin.set(turn.originBefore);
            log.info("Холостой порыв убран из истории, шагов {}", turn.step);
        } else {
            // контекст за это время пересобрали (например, вынесли в дневник):
            // возвращать прежний — значит воскресить уже сохранённый разговор
            log.info("Холостой порыв не откатывается: история уже другая");
        }
        // отметки «это уже вспоминалось» и «это уже было в снимке чата» снимаются
        // в любом случае: истраченное на несостоявшийся порыв не должно молчать
        // до конца дня только потому, что контекст успели пересобрать
        turn.undoOutside.run();
    }

    /**
     * Действие, которое человек увидит: отправка, реакция, стикер. Служебные
     * шаги вроде поиска по чатам сюда не входят — о них пора вспоминать незачем.
     */
    private static String visibleAction(List<ToolCall> calls, Toolbox tools) {
        String acted = "";
        for (ToolCall call : calls) {
            if (Toolbox.FINISHING.contains(call.name())) {
                continue;
            }
            boolean mutating = tools.all().stream()
                    .anyMatch(tool -> tool.name().equals(call.name()) && tool.mutates());
            if (mutating) {
                acted = call.name();
            }
        }
        return acted;
    }

    /**
     * Порыв заканчивается на первом видимом действии: чат уже открыт, и
     * отправка не должна стоить дороже молчания. В обычном разговоре ход
     * продолжается — там реплики складываются в беседу, кроме случаев, когда
     * дверь закрыта: после блокировки не с кем говорить.
     */
    private static boolean finishes(Notification notification, Toolbox tools, ToolCall call) {
        if (Toolbox.FINISHING.contains(call.name()) || Toolbox.TERMINAL.contains(call.name())) {
            return true;
        }
        return notification.trigger() == Notification.Trigger.IMPULSE && tools.all().stream()
                .anyMatch(tool -> tool.name().equals(call.name()) && tool.mutates());
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

    /**
     * Кому этот ход показывает память.
     * <p>
     * Личка владельца — единственное место, где её секреты открываются; событие не
     * из переписки зрителя не имеет вовсе, и ему остаётся только общее. У личного
     * чата идентификатор совпадает с идентификатором человека, поэтому ответ, у
     * которого чата нет, всё равно кому-то принадлежит.
     */
    private Audience audienceOf(Notification notification, Origin from) {
        return new Audience(notification.chatId() != 0 ? notification.chatId() : notification.personId(),
                trustOf.apply(notification.personId()),
                from != null && from.source() == Source.OWNER_DM);
    }

    /**
     * Воспоминания встают перед последним сообщением человека — как будто
     * вспомнилось до ответа.
     *
     * @return чем оборачивать откат, если вклеено было что-то, иначе {@code null}
     */
    private RecallSnapshot recallIntoContext() {
        int at = recallTarget(context);
        if (at < 0) {
            return null;
        }
        // список вспомненного снимается до вызова: память добавляет в него свои
        // отметки, и вернуть потом будет уже не к чему
        Set<Long> before = memory.markInjected();
        String recalled = memory.recall(context, audience);
        if (recalled.isEmpty()) {
            return null;
        }
        Message last = context.get(at);
        context.set(at, last.withContent(recalled + last.content()));
        return new RecallSnapshot(at, last, before);
    }

    /**
     * Отказ входной безопасности: ответ пришёл (HTTP 200), но в нём ни текста,
     * ни вызовов, а причина названа в {@code finish_reason}.
     */
    private static boolean filteredByPolicy(ChatResponse response) {
        return response.finishReason().startsWith("content_filter");
    }

    /**
     * Ход, зарубленный фильтром на шаге 0, ничего не сделал и ничего не показал:
     * вклеенные страницы дневника надо снять и из истории, и из списка
     * «уже вспоминалось». Иначе они лежат мёртвым грузом до переполнения
     * контекста и больше никогда не всплывут — память считает их показанными.
     * <p>
     * Порыву это не мешает: откат холостого порыва сверяет только начало истории
     * до его начала, а вклейка всегда лежит в конце — в том сообщении, которое
     * положил этот же ход. Возврат к {@code original()} возвращает ровно то, что
     * было записано в снимок истории, поэтому прежний сторож «не трогать порыв»
     * только мешал снять вклейку до повтора.
     */
    private void undoRecall(Turn turn) {
        RecallSnapshot was = turn.recallSnapshot;
        if (was == null) {
            return;
        }
        context.set(was.index(), was.original());
        memory.restoreInjected(was.injectedBefore());
        turn.recallSnapshot = null;
        log.info("Вклейку памяти снято: страниц дневника в истории не осталось");
    }

    /**
     * Новый снимок чата приносит свою полку и своё пояснение, а память следом
     * вклеивает страницы. В прежних сообщениях всё это — мёртвый груз прошлого
     * хода: вечер 18.11 дал четыре побайтово одинаковые копии полки (по 4 719
     * знаков) и пять копий пояснений в одном запросе, а в 00:07 19.11 снятие
     * вклейки оставляло в истории страницы, подмешанные тремя ходами раньше, —
     * и строка журнала про «страниц не осталось» была неправдой. Сюда же идут
     * отработанные пинки «твой ответ был просто текстом» — вместе с черновиком
     * прямо перед каждым из них: пинк относится к шагу, который модель уже
     * исправила, а черновик без него остался бы вторым ответом модели подряд.
     *
     * <p>
     * Отпечатки прочитанных страниц здесь НЕ сбрасываются: на прошлых успешных
     * ходах эти страницы были прочитаны по-настоящему, и откатывает их только
     * {@link #undoRecall(Turn)} — когда отказал текущий ход.
     *
     * <p>
     * Полка с пояснением и окружение снимаются порознь: снимок, открытый самой
     * моделью посреди хода, приносит свежую полку, но не приносит окружения —
     * и стирать его из текущего сообщения нельзя, иначе кэш префикса ломается
     * прямо посреди хода.
     *
     * @param incoming  то, что сейчас ложится в историю: снимок — сообщением
     *                  пользователя, а вызов {@code #open} — ответом инструмента
     * @param turnStart был ли это вход в новый ход. Только там из истории
     *                  убираются и пинки: внутри хода позицию вклейки помнит
     *                  {@code recallSnapshot}, а удаление сообщения сдвинуло бы
     *                  все индексы за собой
     */
    private void retireStaleScreens(Iterable<Message> incoming, boolean turnStart) {
        boolean freshScreen = false;
        boolean freshAround = false;
        for (Message message : incoming) {
            String text = message.content();
            freshScreen |= ScreenMarkup.hasShelf(text) || ScreenMarkup.hasNotes(text);
            freshAround |= ScreenMarkup.hasAround(text);
        }
        if (!freshScreen && !freshAround) {
            return;
        }
        Turn turn = active;
        for (int i = 0; i < context.size(); i++) {
            context.set(i, served(context.get(i), freshScreen, freshAround));
        }
        if (turn != null && turn.contextBefore != null) {
            // откат холостого порыва сверяет начало истории дословно: правку
            // надо провести и по её снимку, иначе проверка «история не тронута»
            // перестанет узнавать прежние сообщения и порыв не откатится
            List<Message> before = new ArrayList<>(turn.contextBefore.size());
            for (Message message : turn.contextBefore) {
                before.add(served(message, freshScreen, freshAround));
            }
            turn.contextBefore = List.copyOf(before);
        }
        if (!turnStart || !freshScreen) {
            return;
        }
        dropServedPings(context);
        if (turn != null && turn.contextBefore != null) {
            List<Message> before = new ArrayList<>(turn.contextBefore);
            dropServedPings(before);
            turn.contextBefore = List.copyOf(before);
        }
    }

    /**
     * Сообщение прошлого хода: без той обвязки снимка, которая пришла свежей,
     * и без прочитанных страниц памяти.
     */
    private static Message served(Message message, boolean screen, boolean around) {
        String text = message.content();
        if (text == null) {
            return message;
        }
        String clean = screen ? DiaryMarkup.strip(ScreenMarkup.stripShelfAndNotes(text)) : text;
        if (around) {
            clean = ScreenMarkup.stripAround(clean);
        }
        return clean.equals(text) ? message : message.withContent(clean);
    }

    /** Пинк «ответ текстом ничего не делает»: после того как модель его исполнила, это шум. */
    private static boolean isPing(Message message) {
        String text = message.content();
        return text != null && text.startsWith(BE_TOOL_CENTRIC);
    }

    /** Её реплика без вызовов — замысел шага, который пинк и просил исполнить. */
    private static boolean isDraft(Message message) {
        return message.role() == Message.Role.ASSISTANT && message.toolCallsOrEmpty().isEmpty();
    }

    /**
     * Убирает отработанные пинки вместе с черновиком прямо перед каждым из них.
     * Раздельно они разъезжаются в двусмысленное: без черновика два ответа модели
     * подряд (шаг 0 с мыслями и шаг 1 с вызовами), а сам черновик — единственное,
     * где она ещё видела своё намерение.
     *
     * <p>
     * Обход сзади вперёд, и после обоих вычитаний курсор сдвигается ещё на шаг:
     * иначе следующий за удалённой парой индекс оказывается за концом списка — на
     * паре в хвосте истории это стоило потерянным уведомлением с ERROR.
     */
    private static void dropServedPings(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!isPing(history.get(i))) {
                continue;
            }
            history.remove(i);
            if (i > 0 && isDraft(history.get(i - 1))) {
                history.remove(i - 1);
                i--;
            }
        }
    }

    /**
     * Куда подмешивать память: перед последним сообщением человека.
     * <p>
     * Ответ инструмента и реплика модели — не её место: вклеенная внутрь
     * {@code role=tool}, страница дневника прятала настоящий ответ тулзы в
     * конце пятикилобайтового сообщения, и следующий шаг модель решала так,
     * будто действие уже выполнено.
     *
     * @return индекс сообщения или -1, если подмешивать не во что
     */
    static int recallTarget(List<Message> context) {
        if (context.isEmpty()) {
            return -1;
        }
        Message last = context.getLast();
        return last.role() == Message.Role.USER ? context.size() - 1 : -1;
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
            // тот же сброс, что после дневника: вместе с сообщениями должны
            // уйти и пометки о том, что она уже видела в снимках чатов
            clearContext();
        }
    }
}
