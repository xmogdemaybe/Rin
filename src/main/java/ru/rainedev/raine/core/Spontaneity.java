package ru.rainedev.raine.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryEntry;
import ru.rainedev.raine.memory.Source;
import ru.rainedev.raine.phone.Markup;

/**
 * Позывы написать первой. Человек не ждёт, пока к нему обратятся: он вспоминает
 * что-то своё и пишет сам.
 * <p>
 * Раз в двадцать семь минут — и то не всегда. Ровный интервал читался бы как
 * расписание, а не как порыв.
 */
public final class Spontaneity implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Spontaneity.class);

    /**
     * Между порывами — от получаса до трёх четвертей часа, каждый раз заново.
     * Ровный интервал читался бы как расписание: «опять ровно в 27 минут».
     */
    private static final int MIN_MINUTES = 27;
    private static final int MAX_MINUTES = 47;

    /** Срабатывает примерно в половине случаев. */
    private static final double CHANCE = 0.5;

    /** Как часто признаться в журнале, что порыв не состоялся. */
    private static final Duration SKIP_REPORT = Duration.ofHours(6);

    /**
     * Настройка порыва, а не инструкция. Прежний текст («если сказать нечего —
     * зови wait, молчание это тоже выбор») читался как приглашение молчать, и
     * на живой копии девять порывов подряд кончились тишиной: молчание ничего
     * не стоило, а сообщение — всё. Теперь wait остаётся, но только для
     * крайнего случая, а поводом считается само желание написать.
     */
    private static final String PROMPT = """
            This moment came because you wanted to write first — not because a timer said so.
              - Say what you're thinking, ask how their day is going, send some small thing you \
            noticed, or pick up the memory above. The memory is a mood, not a topic: writing by \
            feeling counts, you don't have to stay on its subject.
            %s
              - Call #wait only in the rarest case — for example when you wrote a minute ago and \
            there's still no answer. Sitting silent on purpose is not the polite default here.""";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("rin-impulse").factory());

    private final NotificationLoop loop;
    private final Diary diary;
    private final Toolbox ownerChat;
    private final long personId;
    private final RandomGenerator random;

    private Instant lastSkipReport = Instant.EPOCH;

    /**
     * Тик, который пришёлся на сон. Флаг один на всю ночь: будильник за это
     * время успеет прозвенеть с десяток раз, а порыв с утра должен быть один.
     */
    private final java.util.concurrent.atomic.AtomicBoolean deferred =
            new java.util.concurrent.atomic.AtomicBoolean();

    private java.util.function.BooleanSupplier asleep = () -> false;

    /** Порыв, который сейчас в очереди или в работе. */
    private final java.util.concurrent.atomic.AtomicReference<Notification> pending =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Пока порыв обрабатывается, список чатов показывается иначе. */
    private final java.util.concurrent.atomic.AtomicBoolean acting =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @param ownerChat набор с инструментом открытия лички владельца: он же
     *                  открывается на шаге 0, поэтому писать можно без разведки
     * @param personId  владелец порыва; ноль — писать некому, останется размышление
     */
    public Spontaneity(NotificationLoop loop, Diary diary, Toolbox ownerChat, long personId,
                       RandomGenerator random) {
        this.loop = loop;
        this.diary = diary;
        this.ownerChat = ownerChat;
        this.personId = personId;
        this.random = random;
    }

    public boolean isActing() {
        return acting.get();
    }

    /** Пока она отдыхает, порывов не бывает. */
    public void asleepWhen(java.util.function.BooleanSupplier asleep) {
        this.asleep = asleep;
    }

    public void start() {
        // признак снимается, когда порыв дошёл до обработки и завершился
        loop.onNotificationDone(done -> {
            if (done == pending.get()) {
                pending.set(null);
                acting.set(false);
            }
        });
        scheduleNext();
        log.info("Спонтанность включена: порывы через {}–{} минут", MIN_MINUTES, MAX_MINUTES);
    }

    private void scheduleNext() {
        long minutes = MIN_MINUTES + random.nextInt(MAX_MINUTES - MIN_MINUTES + 1);
        scheduler.schedule(this::tick, minutes, TimeUnit.MINUTES);
    }

    private void tick() {
        try {
            maybeAct();
        } finally {
            scheduleNext();   // следующий раз — снова через новое время
        }
    }

    /** Для проверок: попробовать прямо сейчас, не дожидаясь своего времени. */
    void actNow() {
        maybeAct();
    }

    /**
     * Пробуждение: то, что не пришло во сне, приходит сейчас — и ровно один раз,
     * сколько бы тиков ни было пропущено за ночь. Решение принимается заново, со
     * своей монеткой и своим поводом: ночной тик не гарантирует, что утром будет
     * о чём сказать.
     */
    public void wokenUp() {
        if (!deferred.compareAndSet(true, false)) {
            return;
        }
        maybeAct();
    }

    private void maybeAct() {
        try {
            // спящему человеку ничего не приходит в голову: ни разговоров,
            // ни записей в память, ни строчек в журнале
            if (asleep.getAsBoolean()) {
                deferred.set(true);
                reportSkip("отдыхает — порыв дождётся пробуждения");
                return;
            }
            if (random.nextDouble() >= CHANCE) {
                return;
            }
            // порыв строится на воспоминании: без него в промпт ложится пустой
            // тег, и тяжёлый запрос уходит в модель лишь затем, чтобы та
            // подтвердила, что сказать нечего. Почему воспоминания нет — видно
            // в журнале, это решает сам перебор
            java.util.Optional<DiaryEntry> reason = randomEntry();
            if (reason.isEmpty()) {
                return;
            }
            log.info("Захотелось написать самой");
            acting.set(true);
            Notification impulse = buildImpulse(reason);
            pending.set(impulse);
            loop.submit(impulse);
        } catch (RuntimeException e) {
            log.error("Не удалось создать спонтанный порыв", e);
        }
    }

    /**
     * Поводом становится случайная запись дневника — так же, как человеку
     * что-то вспоминается само и тянет написать.
     * <p>
     * Видна для проверок: из чего именно сложился порыв, надо уметь увидеть,
     * не заглядывая в планировщик.
     */
    String buildPrompt() {
        return buildPrompt(randomEntry());
    }

    private String buildPrompt(java.util.Optional<DiaryEntry> reason) {
        StringBuilder prompt = new StringBuilder();
        // блока без воспоминания в промпте не бывает: пустая пара тегов — это
        // разметка вместо повода, и модель отвечает на неё подтверждением, что
        // сказать нечего
        reason.ifPresent(entry -> prompt.append("<your_diary_page just_for_reasoning no_plagiarism no_copy>\n")
                .append(Markup.body(entry.body())).append('\n')
                .append("</your_diary_page>\n\n"));
        // без этого порыв слеп к тому, что было совсем недавно: она писала бы
        // снова через минуту после разговора или будила бы человека ночью
        String status = loop.lastTalkStatus();
        if (!status.isEmpty()) {
            prompt.append("<last_conversation>\n").append(status).append("\n</last_conversation>\n\n");
        }
        String invitation = ownerChat.isEmpty()
                ? "  - maybe write to someone? You may need to open a chat first."
                : "  - maybe write to someone? The chat is already open — you can send a message or react "
                        + "right away.";
        return prompt.append(PROMPT.formatted(invitation)).toString();
    }

    /**
     * Порыв с уже открытым чатом владельца: шаг 0 — открытие, шаг 1 — действие
     * или молчание. Набор намеренно узкий, остальные инструменты добавит цикл.
     */
    Notification buildImpulse() {
        return buildImpulse(randomEntry());
    }

    private Notification buildImpulse(java.util.Optional<DiaryEntry> reason) {
        return new Notification(buildPrompt(reason), ownerChat, true, personId, personId).onOwnInitiative();
    }

    /**
     * Записи, которые уже служили поводом. Нужны, чтобы порыв за порывом не
     * крутился вокруг одного и того же воспоминания, но живут здесь, а не в
     * дневнике: запись из дневника не выбрасывают — иначе она пропала бы для
     * всей остальной памяти до самой ночи, когда список перечитывают с диска.
     */
    private static final int RECENT_CAP = 8;
    private final java.util.Deque<String> recent = new java.util.ArrayDeque<>();

    private synchronized java.util.Optional<DiaryEntry> randomEntry() {
        Source ceiling = ceiling();
        List<Diary.Match> all = diary.query(new double[0]);
        // поводом служит то, что можно сказать именно этому адресату: личное
        // чужой переписки не уезжает в случайный разговор даже в порыве
        List<Diary.Match> allowed = all.stream()
                .filter(match -> match.entry().origin().source().noStricterThan(ceiling))
                .toList();
        if (allowed.isEmpty()) {
            reportSkip(all.isEmpty()
                    ? "дневник пуст"
                    : "записей в дневнике: " + all.size() + ", доступных адресату: 0");
            return java.util.Optional.empty();
        }
        List<Diary.Match> fresh = allowed.stream()
                .filter(match -> !recent.contains(match.entry().id()))
                .toList();
        // всё уже служило поводом — пусть повторится, чем молчать
        List<Diary.Match> choice = fresh.isEmpty() ? allowed : fresh;
        Diary.Match chosen = choice.get(random.nextInt(choice.size()));
        diary.use(chosen);
        recent.addLast(chosen.entry().id());
        while (recent.size() > RECENT_CAP) {
            recent.removeFirst();
        }
        return java.util.Optional.of(chosen.entry());
    }

    /**
     * Строжайшая метка записи, которую адресату порыва ещё можно вспомнить.
     * Личка владельца открыта заранее — значит порыв идёт ему, и строже
     * владелецской тайны ничего не бывает. Иначе разговор она выбирает сама,
     * и личное — даже владелецское — уехало бы случайному человеку.
     */
    private Source ceiling() {
        return ownerChat.isEmpty() ? Source.GENERAL : Source.OWNER_DM;
    }

    /**
     * Причина молчания в журнале. Без неё трое суток выглядели как «фича не
     * работает», хотя причин было три разные и все молчаливые.
     */
    private void reportSkip(String reason) {
        Instant now = Instant.now();
        if (now.isBefore(lastSkipReport.plus(SKIP_REPORT))) {
            log.debug("Порыв пропущен: {}", reason);
            return;
        }
        lastSkipReport = now;
        log.info("Порыв пропущен: {}", reason);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
