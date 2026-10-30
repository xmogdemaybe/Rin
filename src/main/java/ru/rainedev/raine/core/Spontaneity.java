package ru.rainedev.raine.core;

import java.time.Duration;
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

    private static final String PROMPT = """
            It's time to reflect on your thoughts!
              - maybe make some reasoning?
              - maybe do some reflection?
            %s
              - If you truly have nothing to say, call #wait. Staying silent is a real choice, not an obligation.""";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("rin-impulse").factory());

    private final NotificationLoop loop;
    private final Diary diary;
    private final Toolbox ownerChat;
    private final long personId;
    private final RandomGenerator random;

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

    private void maybeAct() {
        try {
            // спящему человеку ничего не приходит в голову: ни разговоров,
            // ни записей в память, ни строчек в журнале
            if (asleep.getAsBoolean()) {
                log.debug("Сплю — порыв подождёт до пробуждения");
                return;
            }
            if (random.nextDouble() >= CHANCE) {
                return;
            }
            log.info("Захотелось написать самой");
            acting.set(true);
            Notification impulse = buildImpulse();
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
        StringBuilder prompt = new StringBuilder("<your_diary_page just_for_reasoning no_plagiarism no_copy>\n");
        randomEntry().ifPresent(entry -> prompt.append(Markup.body(entry.body())).append('\n'));
        prompt.append("</your_diary_page>\n\n");
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
        return new Notification(buildPrompt(), ownerChat, true, personId, personId).onOwnInitiative();
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
        // порыв не привязан к чату и уходит в случайный разговор: поводом не
        // может становиться то, что сказано в личке или в чужой переписке
        List<Diary.Match> all = diary.query(new double[0]).stream()
                .filter(match -> match.entry().origin().source() == Source.GENERAL)
                .toList();
        if (all.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<Diary.Match> fresh = all.stream()
                .filter(match -> !recent.contains(match.entry().id()))
                .toList();
        // всё уже служило поводом — пусть повторится, чем молчать
        List<Diary.Match> choice = fresh.isEmpty() ? all : fresh;
        Diary.Match chosen = choice.get(random.nextInt(choice.size()));
        diary.use(chosen);
        recent.addLast(chosen.entry().id());
        while (recent.size() > RECENT_CAP) {
            recent.removeFirst();
        }
        return java.util.Optional.of(chosen.entry());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
