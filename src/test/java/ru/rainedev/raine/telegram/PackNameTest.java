package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Имя пака по его номеру — тем зовут {@code sticker_pack}.
 * <p>
 * Присланный стикер приходит с номером пака, а не с его именем, и без имени
 * просьба «добавь этот стикерпак» упирается в нечем-ответить. Спрашивать
 * Telegram приходится посреди разметки чужих сообщений, поэтому повторять один
 * и тот же вопрос нельзя: десяток стикеров из одного пака в групповом чате дал
 * бы десяток запросов.
 */
class PackNameTest {

    @Test
    void oneQuestionPerPack() {
        Map<Long, String> memo = new ConcurrentHashMap<>();
        AtomicInteger asked = new AtomicInteger();
        java.util.function.Function<Long, Optional<String>> telegram = id -> {
            asked.incrementAndGet();
            return Optional.of("thatcursedrat");
        };

        assertEquals("thatcursedrat", Telegram.packName(memo, 7, telegram));
        assertEquals("thatcursedrat", Telegram.packName(memo, 7, telegram));

        assertEquals(1, asked.get(), "второй раз Telegram спрашивать незачем");
    }

    @Test
    void aMissingPackIsRememberedAsMissing() {
        Map<Long, String> memo = new ConcurrentHashMap<>();

        assertEquals("", Telegram.packName(memo, 9, id -> Optional.empty()),
                "пак мог быть удалён: промах тоже нужно помнить");
        assertEquals("", Telegram.packName(memo, 10, id -> {
            throw new IllegalStateException("стикерсет недоступен");
        }));
        assertEquals(2, memo.size(), "оба промаха закрывают вопрос до конца работы процесса");
    }

    @Test
    void aStickerWithoutAPackIsNotAskedAbout() {
        Map<Long, String> memo = new ConcurrentHashMap<>();
        AtomicInteger asked = new AtomicInteger();

        assertEquals("", Telegram.packName(memo, 0, id -> {
            asked.incrementAndGet();
            return Optional.of("не должно случиться");
        }));

        assertEquals(0, asked.get(), "нулевой номер пака — это не пак, а его отсутствие");
        assertTrue(memo.isEmpty());
    }
}
