package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Кэш живёт всю сессию бота, а переписка за это время растёт — проверка на потолок. */
class ShortLivedCacheTest {

    @Test
    void freshEntryIsNotRecomputed() {
        ShortLivedCache<String, String> cache = new ShortLivedCache<>(60_000);
        AtomicInteger computed = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            cache.get("чат", key -> "значение " + computed.incrementAndGet());
        }

        assertEquals(1, computed.get(), "свежий ответ пересчитывается каждый раз");
    }

    @Test
    void expiredEntryIsRecomputed() {
        ShortLivedCache<String, String> cache = new ShortLivedCache<>(0);
        AtomicInteger computed = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            cache.get("чат", key -> "значение " + computed.incrementAndGet());
        }

        assertEquals(3, computed.get(), "устаревшее значение отдаётся как годное");
    }

    @Test
    void failedLookupIsNotRemembered() {
        // сбой связи на минуту не должен превращаться в пустое имя до перезапуска
        ShortLivedCache<String, String> cache = new ShortLivedCache<>(60_000);

        assertNull(cache.get("чат", key -> null));
        assertEquals("Вернулось", cache.get("чат", key -> "Вернулось"));
    }

    @Test
    void growthIsCapped() {
        ShortLivedCache<Integer, String> cache = new ShortLivedCache<>(60_000);

        for (int i = 0; i < 2000; i++) {
            cache.put(i, "значение " + i);
        }

        assertEquals(512, cache.size(), "кэш растёт вместе с перепиской и не ужимается никогда");
    }
}
