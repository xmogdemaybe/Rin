package ru.rainedev.raine.telegram;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Придерживает поток обращений к Telegram.
 * <p>
 * Telegram строго относится к сторонним клиентам: слишком частые запросы
 * выглядят как автоматизация и приводят к блокировке аккаунта. Пауза после
 * пачки запросов стоит секунды, потерянный аккаунт — всего.
 */
public final class Throttle {

    private static final Logger log = LoggerFactory.getLogger(Throttle.class);

    /** Столько обращений подряд ещё выглядят как живой человек. */
    private static final int BURST = 20;

    private static final Duration PAUSE = Duration.ofSeconds(1);

    private long windowStartedAt = System.nanoTime();
    private int usedInWindow;

    /** До этого момента обращения не отпускаются никому — штраф общий. */
    private long blockedUntil;

    public void allow() {
        while (true) {
            Duration wait = reserve();
            if (wait.isZero()) {
                return;
            }
            log.debug("Слишком часто обращаюсь к Telegram — притормаживаю");
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                // прерывание — это выключение: смысл ждать потерялся
                Thread.currentThread().interrupt();
                return;
            }
            // место ещё не занято: после паузы просим его заново, иначе
            // под штраф попали бы одни, а разошлись все
        }
    }

    /**
     * Занимает место в текущем окне и говорит, сколько ждать, если места нет.
     * <p>
     * Счёт, окно и штраф решаются под одним замком: не спросив друг друга,
     * потоки считали, что последний свободный слот их, — и обращений уходило
     * вдвое больше лимита. Спит тот, кому не хватило места, и уже вне замка,
     * чтобы не держать остальных.
     */
    private synchronized Duration reserve() {
        long now = System.nanoTime();
        if (now < blockedUntil) {
            return Duration.ofNanos(blockedUntil - now);
        }
        if (now - windowStartedAt >= PAUSE.toNanos()) {
            // прошла секунда — окно начинается заново
            windowStartedAt = now;
            usedInWindow = 1;
            return Duration.ZERO;
        }
        if (++usedInWindow < BURST) {
            return Duration.ZERO;
        }
        usedInWindow = 0;
        blockedUntil = now + PAUSE.toNanos();
        return PAUSE;
    }
}
