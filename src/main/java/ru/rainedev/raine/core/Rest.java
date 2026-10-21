package ru.rainedev.raine.core;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Уходит в офлайн, как человек, у которого своя жизнь.
 * <p>
 * Смысл не в экономии: непрерывная доступность выдаёт программу. Живой
 * собеседник иногда отходит, и сообщения за это время накапливаются, а потом
 * читаются разом. Сообщение владельца будит — как звонок посреди дня.
 */
public class Rest {

    private static final Logger log = LoggerFactory.getLogger(Rest.class);

    /** Насколько вероятно отойти перед очередным уведомлением. */
    private static final double CHANCE = 0.01;

    private static final int MIN_MINUTES = 15;
    private static final int MAX_MINUTES = 120;

    /** Шаг проверки пробуждения — реагировать надо быстро, а не досыпать час. */
    private static final Duration TICK = Duration.ofSeconds(1);

    private final RandomGenerator random;
    private NightSleep night;

    /** Жив ли разговор прямо сейчас — решает ядро, Rest своего мнения держать не обязан. */
    private java.util.function.BooleanSupplier aliveTalk = () -> false;

    /** Суммарный предел терпения: даже при живом разговоре дольше этого не бодрствовать. */
    private static final java.time.Duration DEFER_LIMIT = java.time.Duration.ofMinutes(90);

    /** Когда начали откладывать уход в ночь; null — никто ничего не откладывал. */
    private java.time.Instant deferSince;

    /** Предел терпения вышел: уснуть обязана, даже если разговор всё ещё жив. */
    private boolean patienceOver;

    /** Прощания перед уходом в ночь. */
    private Runnable leavingForNight = () -> {};

    /** Проснулась сама к утру — не от сообщения владельца и не от остановки. */
    private Runnable seenMorning = () -> {};

    /** Прощания этой ночи уже прозвучали: проснутая посреди ночи не повторяет их. */
    private boolean farewellDone;

    private final Clock clock;

    /** Дневные отлучки можно выключить: остаётся только ночной сон. */
    private volatile boolean dayNaps = true;

    /**
     * Чем заняться ночью. Получает отведённое время и признак пробуждения.
     * <p>
     * Именно ночью, а не в любую отлучку: пересмотр памяти необратимо
     * переписывает накопленное, и запускать его на пятнадцатиминутной
     * передышке среди дня — значит перекраивать дневник по нескольку раз
     * в сутки.
     */
    private volatile java.util.function.BiConsumer<Duration, java.util.function.BooleanSupplier> duringNight = (d, w) -> { };

    /** Уснула или проснулась — по этому меняется статус «в сети». */
    private java.util.function.Consumer<Boolean> onStateChange = resting -> { };
    private final AtomicBoolean awoken = new AtomicBoolean();
    private volatile boolean resting;

    /** Пора закрываться: спать больше незачем, и досыпать тем более. */
    private volatile boolean stopping;
    private java.time.LocalDateTime consolidatedNight;

    public Rest(RandomGenerator random) {
        this(random, Clock.systemDefaultZone());
    }

    /** Часы отдельно — в тестах видно, как кончается предел терпения. */
    Rest(RandomGenerator random, Clock clock) {
        this.random = random;
        this.clock = clock;
    }

    public void dayNaps(boolean allowed) {
        this.dayNaps = allowed;
    }

    /** Ночь: спит подолгу и в предсказуемое время, в отличие от дневных отлучек. */
    public void night(NightSleep night) {
        this.night = night;
    }

    public void duringNight(java.util.function.BiConsumer<Duration, java.util.function.BooleanSupplier> action) {
        this.duringNight = action;
    }

    public void onStateChange(java.util.function.Consumer<Boolean> action) {
        this.onStateChange = action;
    }

    /** Жив ли разговор — спрашивается перед каждым уходом в ночь. */
    public void aliveTalk(java.util.function.BooleanSupplier alive) {
        this.aliveTalk = alive;
    }

    /** Прощание перед тем, как уйти в ночь. */
    public void leavingForNight(Runnable action) {
        this.leavingForNight = action;
    }

    /** Проснулась сама к утру — не от сообщения и не от остановки. */
    public void seenMorning(Runnable action) {
        this.seenMorning = action;
    }

    public boolean isResting() {
        return resting;
    }

    /** Зовётся из другого потока, когда пришло сообщение от владельца. */
    public void wakeUp() {
        if (resting) {
            log.info("Просыпаюсь раньше времени");
        }
        awoken.set(true);
    }

    /** Работа заканчивается: прекратить отдых и больше в него не уходить. */
    public void stop() {
        stopping = true;
    }

    /** Иногда отходит. Возвращается сама или от сообщения владельца. */
    public void maybeRest() {
        if (stopping) {
            return;
        }
        if (night != null) {
            var untilMorning = night.untilMorning();
            if (untilMorning.isPresent()) {
                fallAsleepTillMorning(untilMorning.get());
                return;
            }
        }
        // день: отложка и прощания здесь не живут — перенос сна только про ночь
        deferSince = null;
        patienceOver = false;
        farewellDone = false;
        if (!dayNaps || random.nextDouble() >= CHANCE) {
            return;
        }
        sleep(Duration.ofMinutes(MIN_MINUTES + random.nextInt(MAX_MINUTES - MIN_MINUTES + 1)), "ненадолго", false);
    }

    /**
     * Уход в ночь. Живой разговор откладывает сон, пока не стихнет или
     * пока не кончится предел терпения.
     */
    private void fallAsleepTillMorning(Duration untilMorning) {
        java.time.Instant now = java.time.Instant.now(clock);
        boolean alive = aliveTalk.getAsBoolean();
        if (patienceOver) {
            // пределы уже вышли: спать обязана, даже если собеседник ещё тут
            alive = false;
        } else {
            if (alive && deferSince == null) {
                log.info("Разговор ещё жив — ночь подождёт");
                deferSince = now;
            }
            if (alive && java.time.Duration.between(deferSince, now).compareTo(DEFER_LIMIT) >= 0) {
                log.info("Предел терпения кончился — ложусь, хоть разговор и жив");
                patienceOver = true;
                alive = false;
            }
        }
        if (alive) {
            // ядру это ничего не стоит: в тишине вопрос повторится через минуту
            return;
        }
        if (!farewellDone) {
            // прощания идут порывами и встают в очередь ядра: спать уходим после
            // них — в следующем же заходе очередь опустеет, и тогда до одеяла
            farewellDone = true;
            deferSince = null;
            try {
                leavingForNight.run();
            } catch (RuntimeException e) {
                log.error("Прощание не прозвучало: {}", e.getMessage());
            }
            return;
        }
        deferSince = null;
        patienceOver = false;
        sleep(untilMorning, "до утра", true);
    }

    private void sleep(Duration duration, String reason, boolean night) {
        if (duration.isNegative() || duration.isZero()) {
            return;
        }
        if (awoken.getAndSet(false)) {
            // её только что позвали — не время уходить. Сбросить флаг молча нельзя:
            // сообщение владельца оказалось бы потеряно, и она ушла бы спать
            // с непрочитанным
            return;
        }
        log.info("Ухожу {} — на {} мин", reason, duration.toMinutes());

        long deadline = System.nanoTime() + duration.toNanos();
        resting = true;
        onStateChange.accept(true);
        try {
            // ночной сон — не простой: память в это время пересматривается.
            // Дневная передышка на это не годится, она слишком коротка и часта
            if (night && !java.util.Objects.equals(consolidatedNight, this.night.bedtime())) {
                consolidatedNight = this.night.bedtime();
                duringNight.accept(duration, () -> awoken.get() || stopping);
            }
            while (System.nanoTime() < deadline) {
                if (awoken.get()) {
                    log.info("Вернулась раньше — меня позвали");
                    return;
                }
                if (stopping) {
                    log.info("Просыпаюсь: пора закрываться");
                    return;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) Thread.sleep(Duration.ofNanos(Math.min(TICK.toNanos(), remaining)));
            }
            log.info("Вернулась");
            if (night) {
                // проснулась сама, ко времени: редко, но можно и первой поприветствовать близких
                try {
                    seenMorning.run();
                } catch (RuntimeException e) {
                    log.error("Утреннее приветствие не сложилось: {}", e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            resting = false;
            onStateChange.accept(false);
        }
    }
}
