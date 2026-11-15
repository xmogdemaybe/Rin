package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraitSignalsTest {

    private static final Clock NIGHT =
            Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC);

    private static TraitSignal note(String trait, String at) {
        return new TraitSignal("preference", trait, 0.3, 0.5, "", at);
    }

    @Test
    void aRestartDoesNotEmptyThePocket(@TempDir Path dir) {
        Path file = dir.resolve("trait_signals.json");
        new TraitSignals(file, NIGHT).add(note("люблю грозу", "2026-10-20T23:00:00Z"));

        TraitSignals after = new TraitSignals(file, NIGHT);

        assertEquals(1, after.snapshot().size(), "заметка ждёт повтора, а перезапуск случается чаще");
        assertEquals("люблю грозу", after.snapshot().getFirst().trait());
    }

    @Test
    void drainedNotesDoNotComeBackAfterARestart(@TempDir Path dir) {
        Path file = dir.resolve("trait_signals.json");
        new TraitSignals(file, NIGHT).add(note("люблю грозу", "2026-10-20T23:00:00Z"));

        TraitSignals afterRestart = new TraitSignals(file, NIGHT);
        assertEquals(1, afterRestart.drain().size(), "заметка доехала до перезапуска и ушла в дневник");

        assertTrue(new TraitSignals(file, NIGHT).snapshot().isEmpty(), "ушедшее в дневник не возвращается");
    }

    @Test
    void requeuedNotesSurviveARestart(@TempDir Path dir) {
        Path file = dir.resolve("trait_signals.json");
        TraitSignals before = new TraitSignals(file, NIGHT);
        before.add(note("люблю грозу", "2026-10-20T23:00:00Z"));
        before.requeue(before.drain());

        assertEquals(1, new TraitSignals(file, NIGHT).snapshot().size(),
                "пересказ не случился — заметка живёт дальше");
    }

    @Test
    void notesPastTheirChanceAreNotKept(@TempDir Path dir) {
        TraitSignals pocket = new TraitSignals(dir.resolve("trait_signals.json"), NIGHT);

        pocket.add(note("старая", "2026-10-01T10:00:00Z"));
        pocket.add(note("свежая", "2026-10-20T23:00:00Z"));

        assertEquals(List.of("свежая"), pocket.snapshot().stream().map(TraitSignal::trait).toList(),
                "копилка не растёт вечно");
    }

    @Test
    void aBrokenFileDoesNotSinkTheStart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trait_signals.json");
        Files.writeString(file, "{ это не json");

        assertTrue(new TraitSignals(file, NIGHT).snapshot().isEmpty());
    }

    @Test
    void theFileIsReadableByAHuman(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trait_signals.json");

        new TraitSignals(file, NIGHT).add(note("люблю грозу", "2026-10-20T23:00:00Z"));

        assertTrue(Files.readString(file).contains("люблю грозу"), "заметки можно прочитать глазами");
    }
}
