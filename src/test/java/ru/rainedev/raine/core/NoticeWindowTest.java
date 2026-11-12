package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * Скользящее окно: три реплики с разницей в пару секунд — это один ход, а не
 * три, и последний не отвечает на первое сообщение.
 */
class NoticeWindowTest {

    /** Планировщик, который ничего не ждёт: помнит, что и на сколько отложили. */
    private static final class Recorded implements NoticeWindow.Delayer {
        private final List<Long> waits = new ArrayList<>();
        private final List<Runnable> armed = new ArrayList<>();
        private final List<Runnable> cancelled = new ArrayList<>();

        @Override
        public Runnable after(long millis, Runnable action) {
            waits.add(millis);
            armed.add(action);
            return () -> cancelled.add(action);
        }

        /** Срабатывает то, что отложено последним и не отменено. */
        void fireLast() {
            armed.get(armed.size() - 1).run();
        }
    }

    /** Часы, которые идут туда, куда скажет тест. */
    private static final class Clock implements LongSupplier {
        private long now;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private final Recorded delayer = new Recorded();
    private final Clock clock = new Clock();
    private final NoticeWindow window = new NoticeWindow(delayer, clock, 15_000);

    @Test
    void aBurstIntoTheSameChatLeavesOneNotice() {
        boolean[] firstRan = {false};
        boolean[] secondRan = {false};
        window.defer(7L, 2_000, () -> firstRan[0] = true);
        clock.advance(500);
        window.defer(7L, 2_000, () -> secondRan[0] = true);

        delayer.fireLast();

        assertFalse(firstRan[0], "первое уведомление отменено вторым");
        assertTrue(secondRan[0], "ход заберёт последнюю реплику");
        assertEquals(List.of(2_000L, 2_000L), delayer.waits, "новое сообщение даёт полную выдержку заново");
    }

    @Test
    void aBurstStopsBeingPostponedAtTheCeiling() {
        for (int message = 0; message < 9; message++) {
            window.defer(7L, 2_000, () -> { });
            clock.advance(2_000);
        }

        assertEquals(0L, delayer.waits.getLast(),
                "на потолке окно уже не откладывается — ход идёт сразу: " + delayer.waits);
        assertEquals(8, delayer.cancelled.size(), "восемь раз отменили, девятый — уже пора");
    }

    @Test
    void aLoneLongWindowKeepsItsWholeSpread() {
        // полоса «глянула на экран блокировки» измеряется от своего сообщения,
        // потолок придуман не для того, чтобы её обрезать
        window.defer(7L, 40_000, () -> { });

        assertEquals(List.of(40_000L), delayer.waits);
    }

    @Test
    void chatsDoNotShareTheirWindows() {
        window.defer(1L, 2_000, () -> { });
        window.defer(2L, 2_000, () -> { });

        assertEquals(List.of(), delayer.cancelled, "чужое окно не прикрывается этим чатом");
        assertEquals(2, delayer.armed.size());
    }

    @Test
    void theWindowStartsFreshAfterItFired() {
        window.defer(7L, 2_000, () -> { });
        delayer.fireLast();
        clock.advance(60_000);

        window.defer(7L, 2_000, () -> { });

        assertEquals(2_000L, delayer.waits.getLast(),
                "следующий разговор не живёт под остаток чужого потолка");
    }
}
