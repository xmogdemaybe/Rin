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

    public void allow() {
        Duration wait = reserve();
        if (wait.isZero()) {
            return;
        }
        log.debug("Слишком часто обращаюсь к Telegram — притормаживаю");
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Занимает место в текущем окне. Счёт и решение о паузе принимались двумя
     * раздельными счётчиками, и при конкурентии потоки расходились: каждый
     * считал, что последний слот его, и обращений уходилось вдвое больше
     * лимита. Здесь окно занято целиком под одним замком, а спит уже тот, кому
     * не хватило места, — замок он не держит и других не задерживает.
     */
    private synchronized Duration reserve() {
        long now = System.nanoTime();
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
        windowStartedAt = now + PAUSE.toNanos();
        return PAUSE;
    }
}
