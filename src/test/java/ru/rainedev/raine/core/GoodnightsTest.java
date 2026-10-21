package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.memory.TrustLedger;

class GoodnightsTest {

    /** Часы, которые двигаются руками: пауза между словами обязана кончаться. */
    private static final class TunedClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T09:00:00Z");

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

    /** Кость, всегда кладущая одно значение: 0 — всё выпадает, 0.99 — ничего. */
    private static final class FixedDice implements RandomGenerator {
        private final double value;

        FixedDice(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
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

    private String ledger(Path dir) throws Exception {
        String content = """
                - id 42, Вика: +3 — выручала (2026-10-01)
                - id 7, Соня: +2 — мило болтает (2026-10-01)
                - id 9, Тоня: -5 — интригует (2026-10-01)
                """;
        Files.writeString(dir.resolve("trust.md"), content);
        return dir.resolve("trust.md").toString();
    }

    @Test
    void wordGoesToOwnerAndToTheCloseOnes(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(Path.of(ledger(dir)), 555, "Папик");
        Goodnights goodnights = new Goodnights(ledger, 555, "Папик", new FixedDice(0.0), clock);

        List<Goodnights.Receiver> picked = goodnights.drawForNight();

        assertEquals(List.of(new Goodnights.Receiver(555, "Папик"), new Goodnights.Receiver(42, "Вика")),
                picked, "владелец почти всегда, Вика по уровню, Соня с двумя плюсами — ещё не близкая");
    }

    @Test
    void silenceInsteadOfNagging(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(Path.of(ledger(dir)), 555, "Папик");
        Goodnights goodnights = new Goodnights(ledger, 555, "Папик", new FixedDice(0.0), clock);

        assertTrue(goodnights.drawForNight().size() >= 1);

        List<Goodnights.Receiver> second = goodnights.drawForNight();
        assertEquals(List.of(new Goodnights.Receiver(555, "Папик")), second,
                "близкой с паузой не пишется, а своему человеку — можно");

        clock.advance(Duration.ofDays(15));
        assertTrue(goodnights.drawForNight().contains(new Goodnights.Receiver(42, "Вика")),
                "спустя две недели слово можно сказать снова");
    }

    @Test
    void coldNightStaysSilent(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(Path.of(ledger(dir)), 555, "Папик");
        Goodnights goodnights = new Goodnights(ledger, 555, "Папик", new FixedDice(0.99), clock);

        List<Goodnights.Receiver> picked = goodnights.drawForNight();

        assertTrue(picked.isEmpty(), "кость не выпала — никто не потревожен");
    }

    @Test
    void goodnightDoesNotEatTheMorning(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(Path.of(ledger(dir)), 555, "Папик");
        Goodnights goodnights = new Goodnights(ledger, 555, "Папик", new FixedDice(0.0), clock);

        assertTrue(goodnights.drawForNight().contains(new Goodnights.Receiver(42, "Вика")));
        assertTrue(goodnights.drawForMorning().contains(new Goodnights.Receiver(42, "Вика")),
                "ночное слово не отнимает утреннее");

        assertFalse(goodnights.drawForMorning().contains(new Goodnights.Receiver(42, "Вика")),
                "а второе утреннее подряд — уже навязчивость");
    }

    @Test
    void withoutOwnerOnlyTheLedgerSpeaks(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(Path.of(ledger(dir)), 555, "");
        Goodnights goodnights = new Goodnights(ledger, 0, "", new FixedDice(0.0), clock);

        List<Goodnights.Receiver> picked = goodnights.drawForNight();

        assertEquals(List.of(new Goodnights.Receiver(42, "Вика")), picked, "владельца нет — только близкие из файла");
    }
}
