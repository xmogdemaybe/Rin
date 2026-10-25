package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

    /**
     * Опрос распознавания речи спрашивает одно и то же сообщение каждые полсекунды,
     * а состояние его меняется между вопросами. Отдать такому недавний снимок — значит
     * залипнуть на весь срок кэша: ответ уже пришёл, а она всё ещё ждёт его.
     */
    @Test
    void aPollingQuestionIgnoresTheLifetime() {
        ShortLivedCache<String, String> cache = new ShortLivedCache<>(60_000);
        AtomicInteger asked = new AtomicInteger();
        Function<String, Optional<String>> telegram = key -> Optional.of("ответ " + asked.incrementAndGet());

        assertEquals("ответ 1", cache.optional("голос", telegram).orElseThrow());
        assertEquals("ответ 2", cache.refresh("голос", telegram).orElseThrow(),
                "опрос обязан спрашивать источник, а не память");
        assertEquals("ответ 2", cache.optional("голос", telegram).orElseThrow(),
                "что увидел опрос, то и должно остаться запомненным");
        assertEquals(2, asked.get(), "свежее значение опроса переспрашивается заново");
    }

    /** Пустой ответ опроса — не «такого сообщения нет», а «спроси ещё раз». */
    @Test
    void aPollThatFoundNothingRemembersNothing() {
        ShortLivedCache<String, String> cache = new ShortLivedCache<>(60_000);
        cache.put("голос", "прежний ответ");

        assertEquals(Optional.empty(), cache.refresh("голос", key -> Optional.empty()));

        AtomicInteger asked = new AtomicInteger();
        assertEquals("ожил", cache.optional("голос", key -> {
            asked.incrementAndGet();
            return Optional.of("ожил");
        }).orElseThrow(), "пустота опроса закрыла собой ответ следующего тика");
        assertEquals(1, asked.get());
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
