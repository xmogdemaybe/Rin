package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RestTest {

    private static final Instant START = Instant.parse("2026-11-18T00:00:00Z");

    private static final class ManualClock extends Clock {
        private Instant now = START;

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
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

    private static final class FakeSleeper implements Rest.Sleeper {
        private final ManualClock clock;
        private Runnable afterSleep = () -> {};
        private int calls;

        private FakeSleeper(ManualClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(Duration duration) {
            clock.advance(duration);
            calls++;
            afterSleep.run();
        }
    }

    /** Позволяет задать, выпадет ли отдых и насколько длинный. */
    private record FixedRandom(double chance, int minutes) implements RandomGenerator {
        @Override
        public double nextDouble() {
            return chance;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(minutes, bound - 1);
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    private static final class RollingRandom implements RandomGenerator {
        private final double chance;
        private final int minutes;
        private int rolls;

        private RollingRandom(double chance, int minutes) {
            this.chance = chance;
            this.minutes = minutes;
        }

        @Override
        public double nextDouble() {
            rolls++;
            return chance;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(minutes, bound - 1);
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    @Test
    @Timeout(5)
    void usuallyDoesNotRest() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        Rest rest = new Rest(new FixedRandom(0.9, 0), clock, sleeper);

        rest.maybeRest();

        assertFalse(rest.isResting());
        assertEquals(0, sleeper.calls);
    }

    @Test
    @Timeout(5)
    void ownerMessageBringsHerBack() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        Rest rest = new Rest(new FixedRandom(0.0, 45), clock, sleeper);
        sleeper.afterSleep = rest::wakeUp;

        rest.maybeRest();

        assertFalse(rest.isResting());
        assertEquals(1, sleeper.calls, "пробуждение прерывает ожидание на первом шаге");
        assertEquals(START.plusSeconds(1), clock.instant());
    }

    @Test
    @Timeout(5)
    void wakeUpBeforeRestingIsNotLost() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        Rest rest = new Rest(new FixedRandom(0.0, 0), clock, sleeper);
        rest.wakeUp();

        rest.maybeRest();

        assertFalse(rest.isResting());
        assertEquals(0, sleeper.calls, "если её только что позвали, отдых не начинается");
    }

    @Test
    void aliveTalkPreventsADayNap() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        RollingRandom random = new RollingRandom(0.0, 15);
        Rest rest = new Rest(random, clock, sleeper);
        rest.aliveTalk(() -> true);

        rest.maybeRest();

        assertFalse(rest.isResting());
        assertEquals(0, random.rolls, "живой разговор не тратит дневной бросок");
        assertEquals(0, sleeper.calls);
    }

    @Test
    void dayChanceIsThrottledForFortyFiveSeconds() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        RollingRandom random = new RollingRandom(0.9, 15);
        Rest rest = new Rest(random, clock, sleeper);

        rest.maybeRest();
        assertEquals(1, random.rolls);
        clock.advance(Duration.ofSeconds(44));
        rest.maybeRest();
        assertEquals(1, random.rolls, "до 45 секунд повторного броска нет");
        clock.advance(Duration.ofSeconds(1));
        rest.maybeRest();
        assertEquals(2, random.rolls, "после 45 секунд бросок снова разрешён");
    }

    @Test
    void ordinaryDayNapStartsAThreeHourCooldown() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        RollingRandom random = new RollingRandom(0.0, 15);
        Rest rest = new Rest(random, clock, sleeper);

        rest.maybeRest();
        assertTrue(sleeper.calls > 0);
        clock.advance(Duration.ofMinutes(179).plusSeconds(59));
        rest.maybeRest();
        assertEquals(1, random.rolls, "кулдаун ещё действует");
        clock.advance(Duration.ofSeconds(1));
        rest.maybeRest();
        assertEquals(2, random.rolls, "после трёх часов отлучка снова возможна");
    }

    @Test
    void wakeUpAlsoStartsTheCooldownAndDoesNotLeaveAStickyFlag() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        RollingRandom random = new RollingRandom(0.0, 45);
        Rest rest = new Rest(random, clock, sleeper);
        sleeper.afterSleep = rest::wakeUp;

        rest.maybeRest();
        assertEquals(1, sleeper.calls);
        sleeper.afterSleep = () -> {};
        clock.advance(Duration.ofMinutes(179).plusSeconds(59));
        rest.maybeRest();
        assertEquals(1, random.rolls, "после wakeUp кулдаун тот же");
        clock.advance(Duration.ofSeconds(1));
        rest.maybeRest();
        assertEquals(2, random.rolls, "флаг пробуждения не залипает на следующей отлучке");
        assertEquals(START.plus(Duration.ofMinutes(45)).plus(Duration.ofMinutes(180)).plusSeconds(1), clock.instant());
    }

    @Test
    void onlyTwoDayNapsAreAllowedAndTheLimitResetsNextDay() {
        ManualClock clock = new ManualClock();
        FakeSleeper sleeper = new FakeSleeper(clock);
        RollingRandom random = new RollingRandom(0.0, 15);
        Rest rest = new Rest(random, clock, sleeper);

        rest.maybeRest();
        clock.advance(Duration.ofMinutes(180));
        rest.maybeRest();
        clock.advance(Duration.ofMinutes(180));
        rest.maybeRest();

        assertEquals(2, random.rolls, "третья отлучка не тратит бросок");
        clock.advance(Duration.between(clock.instant(), START.plus(Duration.ofDays(1))));
        rest.maybeRest();
        assertEquals(3, random.rolls, "новая дата сбрасывает дневной лимит");
    }

    @Test
    void dayNapDurationStaysBetweenFifteenAndFortyFiveMinutes() {
        ManualClock shortClock = new ManualClock();
        FakeSleeper shortSleeper = new FakeSleeper(shortClock);
        new Rest(new FixedRandom(0.0, 0), shortClock, shortSleeper).maybeRest();
        assertEquals(START.plus(Duration.ofMinutes(15)), shortClock.instant());

        ManualClock longClock = new ManualClock();
        FakeSleeper longSleeper = new FakeSleeper(longClock);
        new Rest(new FixedRandom(0.0, 45), longClock, longSleeper).maybeRest();
        assertEquals(START.plus(Duration.ofMinutes(45)), longClock.instant());
    }
}
