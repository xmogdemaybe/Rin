package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class NightDeferralTest {

    /** Часы, которые двигаются руками: у предела терпения должен быть конец. */
    private static final class TunedClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T23:30:00Z");

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Кость: дневные отлучки не выпадают, ночи не касаются. */
    private static final class FixedDice implements RandomGenerator {
        @Override
        public double nextDouble() {
            return 0.9;
        }

        @Override
        public int nextInt(int bound) {
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    private final TunedClock clock = new TunedClock();

    /** Ложится ровно в 23:00, встаёт по утру. На тестовых часах — уже ночь. */
    private Rest rest() {
        NightSleep night = new NightSleep(
                LocalTime.of(23, 0), LocalTime.of(23, 0),
                LocalTime.of(6, 0), LocalTime.of(8, 0),
                clock, new FixedDice());
        Rest rest = new Rest(new FixedDice(), clock);
        rest.night(night);
        return rest;
    }

    private static void awaitRest(Rest rest) throws InterruptedException {
        Instant start = Instant.now();
        while (!rest.isResting() && Duration.between(start, Instant.now()).toSeconds() < 3) {
            Thread.sleep(10);
        }
    }

    @Test
    @Timeout(10)
    void liveConversationPostponesFallingAsleep() throws Exception {
        Rest rest = rest();
        rest.aliveTalk(() -> true);

        rest.maybeRest();   // разговор жив — спать пока нельзя
        assertFalse(rest.isResting(), "живой разговор — спать нельзя");

        clock.advance(Duration.ofMinutes(30));
        rest.maybeRest();   // всё ещё живой — всё ещё нельзя
        assertFalse(rest.isResting());

        // предел терпения вышел: первое уснуть уходит на пустое прощание,
        // на следующий заход — прямо под одеяло
        clock.advance(Duration.ofMinutes(89));
        rest.maybeRest();
        assertFalse(rest.isResting());

        Thread falling = Thread.ofVirtual().start(rest::maybeRest);
        awaitRest(rest);
        assertTrue(rest.isResting(), "после предела терпения уснуть обязана");

        falling.interrupt();
        falling.join(Duration.ofSeconds(5));
        assertFalse(rest.isResting());
    }

    @Test
    @Timeout(10)
    void farewellIsOneImpulseAndSleepFollows() throws Exception {
        Rest rest = rest();
        AtomicInteger farewells = new AtomicInteger();
        rest.leavingForNight(farewells::incrementAndGet);
        rest.aliveTalk(() -> true);

        rest.maybeRest();   // разговор ещё жив — прощание раньше времени
        assertEquals(0, farewells.get(), "разговор жив — прощание пока не уместно");

        // разговор стих: сперва прощание, и только потом — сон
        rest.aliveTalk(() -> false);
        rest.maybeRest();
        assertEquals(1, farewells.get(), "прощание стало порывом");
        assertFalse(rest.isResting(), "сама ночь начинается после прощания");

        Thread falling = Thread.ofVirtual().start(rest::maybeRest);
        awaitRest(rest);
        assertTrue(rest.isResting(), "очередь опустела — уходит под одеяло");
        assertEquals(1, farewells.get(), "прощание в эту ночь уже прозвучало");

        falling.interrupt();
        falling.join(Duration.ofSeconds(5));
        assertFalse(rest.isResting());
    }

    @Test
    @Timeout(10)
    void wokenSheNeverSaysGoodnightTwice() throws Exception {
        // уснула, хозяин разбудил, поговорили, стихли — второе прощание не звучит
        Rest rest = rest();
        AtomicInteger farewells = new AtomicInteger();
        rest.leavingForNight(farewells::incrementAndGet);
        rest.aliveTalk(() -> false);

        rest.maybeRest();   // прощание
        assertEquals(1, farewells.get());

        Thread falling = Thread.ofVirtual().start(rest::maybeRest);
        awaitRest(rest);
        rest.wakeUp();      // сообщение владельца поднимает
        falling.join(Duration.ofSeconds(5));
        assertFalse(rest.isResting());

        // первый после подъёма заход видит флаг «уже позвали» и не спит —
        // этим заходом флаг и гасится, уснёт она следующим
        rest.maybeRest();
        assertFalse(rest.isResting());

        Thread fallingAgain = Thread.ofVirtual().start(rest::maybeRest);
        awaitRest(rest);
        assertTrue(rest.isResting(), "вечер тот же — спать снова можно");
        assertEquals(1, farewells.get(), "проснувшись, она не повторяет «спокойной ночи»");

        fallingAgain.interrupt();
        fallingAgain.join(Duration.ofSeconds(5));
        assertFalse(rest.isResting());
    }
}
