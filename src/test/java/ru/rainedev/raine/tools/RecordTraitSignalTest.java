package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.Source;
import ru.rainedev.raine.memory.TraitSignal;
import ru.rainedev.raine.memory.TraitSignals;

class RecordTraitSignalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void clampsAndKeepsTheNote() throws Exception {
        TraitSignals pocket = new TraitSignals();
        TelegramTools tools = new TelegramTools(null, null, null, null, null);
        tools.traitSignals(pocket);

        String answer = tools.recordTraitSignal().call(MAPPER.readTree("""
                {"kind":"hurt","trait":"огрызаюсь на колкости",
                 "valence":9,"intensity":-4,"subject":"Alex"}"""));

        List<TraitSignal> taken = pocket.drain();
        assertEquals(1, taken.size());
        assertEquals(1.0, taken.getFirst().valence(), "выше шкалы — clamp, не ломаем границы");
        assertEquals(0.0, taken.getFirst().intensity(), "ниже нуля — значит «почти не заметил»");
        assertEquals("огрызаюсь на колкости", taken.getFirst().trait());
        assertEquals("Alex", taken.getFirst().subject());
        assertTrue(answer.contains("Noted"));
    }

    @Test
    void withoutThePocketItRefusesQuietly() throws Exception {
        TelegramTools tools = new TelegramTools(null, null, null, null, null);

        String answer = tools.recordTraitSignal().call(
                MAPPER.readTree("{\"kind\":\"hurt\",\"trait\":\"что-то\"}"));

        assertTrue(answer.contains("disabled"));
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        TraitSignals pocket = new TraitSignals();
        TelegramTools tools = new TelegramTools(null, null, null, null, null);
        tools.traitSignals(pocket);

        assertThrows(LowQualityException.class,
                () -> tools.recordTraitSignal().call(MAPPER.readTree("{}")));

        assertTrue(pocket.drain().isEmpty(), "хвост из пустых заметок не нужен");
    }

    @Test
    void remindsHerOwnEarlierWording(@TempDir Path dir) throws Exception {
        Diary diary = new Diary(dir);
        diary.save("Прошлая запись об этой же черте — формулировка должна повториться дословно.",
                new double[] {1, 0, 0}, 0,
                List.of(new TraitSignal("hurt", "огрызаюсь на колкости", 0.3, 0.5, "", "2026-10-18T10:00:00Z")),
                Source.GENERAL);
        TraitSignals pocket = new TraitSignals();
        TelegramTools tools = new TelegramTools(null, null, null, null, null);
        tools.traitSignals(pocket);
        tools.traitDiary(diary);

        String answer = tools.recordTraitSignal().call(MAPPER.readTree(
                "{\"kind\":\"hurt\",\"trait\":\"огрызаюсь на колкости\"}"));

        assertTrue(answer.contains("once"), "она уже раз это замечала — честно напоминаем");
        assertEquals(1, pocket.drain().size());
    }

    @Test
    void countsTheNotesStillWaitingForTheDiary(@TempDir Path dir) throws Exception {
        TraitSignals pocket = new TraitSignals();
        TelegramTools tools = new TelegramTools(null, null, null, null, null);
        tools.traitSignals(pocket);
        tools.traitDiary(new Diary(dir));
        String note = "{\"kind\":\"hurt\",\"trait\":\"огрызаюсь на колкости\"}";

        tools.recordTraitSignal().call(MAPPER.readTree(note));
        String second = tools.recordTraitSignal().call(MAPPER.readTree(note));

        assertTrue(second.contains("once"),
                "первая заметка до дневника ещё не дошла, но повтором уже считается");
        assertEquals(2, pocket.drain().size());
    }
}
