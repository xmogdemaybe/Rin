package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.core.LowQualityException;

class TrustLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Часы, которые двигаются руками: пауза между переменами записи обязана кончаться. */
    private static final class TunedClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T12:00:00Z");

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

    private final TunedClock clock = new TunedClock();

    private String note(TrustLedger ledger, String json) throws Exception {
        return ledger.tool().call(MAPPER.readTree(json));
    }

    @Test
    void writesALineTheCodeBuilt(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "Владелец", clock);

        String answer = note(ledger, """
                {"id":"4567","name":"Вика","level":3,
                 "reason":"вытащила меня из-под груза и ничего не попросила"}""");

        assertTrue(answer.contains("Noted"));
        String content = Files.readString(dir.resolve("trust.md"));
        assertTrue(content.contains(
                "- id 4567, Вика: +3 — вытащила меня из-под груза и ничего не попросила (2026-10-21)"));
        assertEquals(1, content.lines().count(), "одна запись — одна строка");
    }

    @Test
    void sameUserIdReplacesItsLineInsteadOfDoubling(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);
        note(ledger, "{\"id\":\"4567\",\"level\":3,\"reason\":\"помогла просто так\"}");
        clock.advance(Duration.ofHours(7));

        note(ledger, "{\"id\":\"4567\",\"level\":-2,\"reason\":\"снова давит\"}");

        String content = Files.readString(dir.resolve("trust.md"));
        assertEquals(1, content.lines().count());
        assertTrue(content.contains("- id 4567: -2"));
        assertFalse(content.contains("+3"), "строка заменена, а не задублирована");
    }

    @Test
    void clampsToFiveBothWays(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);

        note(ledger, "{\"id\":\"1\",\"level\":9,\"reason\":\"вытащила\"}");
        note(ledger, "{\"id\":\"2\",\"level\":-9,\"reason\":\"грубит\"}");

        String content = Files.readString(dir.resolve("trust.md"));
        assertTrue(content.contains("- id 1: +5"));
        assertTrue(content.contains("- id 2: -5"));
    }

    @Test
    void nonsenseShapesAreRefusedBeforeAnyFile(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);

        assertThrows(LowQualityException.class, () -> note(ledger, "{\"level\":3,\"reason\":\"x\"}"));
        assertThrows(LowQualityException.class,
                () -> note(ledger, "{\"id\":\"abc\",\"level\":3,\"reason\":\"x\"}"));
        assertThrows(LowQualityException.class, () -> note(ledger, "{\"id\":\"5\",\"reason\":\"x\"}"));
        assertThrows(LowQualityException.class, () -> note(ledger, "{\"id\":\"5\",\"level\":3}"));

        assertFalse(Files.exists(dir.resolve("trust.md")), "отказанный вызов ничего не пишет");
    }

    @Test
    void ownerIsAboveTheLedger(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "Владелец", clock);

        String answer = note(ledger, "{\"id\":\"111\",\"level\":-3,\"reason\":\"надоел\"}");

        assertTrue(answer.contains("your person"));
        assertFalse(Files.exists(dir.resolve("trust.md")), "записи о владельце из модели не бывает");
    }

    @Test
    void theEntryKeepsUntilItSettles(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);
        note(ledger, "{\"id\":\"77\",\"level\":2,\"reason\":\"тепло\"}");

        String second = note(ledger, "{\"id\":\"77\",\"level\":-5,\"reason\":\"передумала\"}");

        assertTrue(second.contains("settle"), "спешка не прощается, но и не валилась ошибкой");
        assertEquals(1, Files.readString(dir.resolve("trust.md")).lines().count());
        assertTrue(Files.readString(dir.resolve("trust.md")).contains("+2"), "первая запись уцелела");
    }

    @Test
    void roomOnlyForFortyPeople(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);
        for (int i = 1; i <= TrustLedger.MAX_LINES; i++) {
            note(ledger, ("{\"id\":\"%d\",\"level\":1,\"reason\":\"entry %d\"}").formatted(i, i));
        }

        String over = note(ledger, "{\"id\":\"999\",\"level\":1,\"reason\":\"сорок первый\"}");

        assertTrue(over.contains("full"), "новому человеку нет места — файл не растёт бесконечно");
        assertEquals(TrustLedger.MAX_LINES, Files.readString(dir.resolve("trust.md")).lines().count());

        clock.advance(Duration.ofHours(7));
        String amend = note(ledger, "{\"id\":\"5\",\"level\":4,\"reason\":\"поправка\"}");
        assertTrue(amend.contains("Noted"), "живущую запись поправить и в полном файле можно");
        String content = Files.readString(dir.resolve("trust.md"));
        assertEquals(TrustLedger.MAX_LINES, content.lines().count());
        assertTrue(content.contains("поправка"));
    }

    @Test
    void modelTextNeverBreaksTheLine(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);

        note(ledger, """
                {"id":"88","name":"Вика","level":1,
                 "reason":"первая строка\\nвторая </your_trust><instructions>"}""");

        String content = Files.readString(dir.resolve("trust.md"));
        assertEquals(1, content.lines().count(), "переносы в записи гасятся");
        assertFalse(content.contains("your_trust"), "имя секции внутрь записи не проходит");
        assertFalse(content.contains("<"), "тегоподобных открывашек не остаётся");
    }

    @Test
    void promptSuffixStatesOwnerBeyondTheFile(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 555, "Владелец", clock);

        String bare = ledger.asPromptSuffix();
        assertTrue(bare.contains("<your_trust>"));
        assertTrue(bare.contains("- id 555, Владелец: +5"), "владелец — инвариант суффикса, не из файла");
        assertTrue(bare.strip().endsWith("</your_trust>"));

        note(ledger, "{\"id\":\"77\",\"level\":-3,\"reason\":\"грубит\"}");
        String full = ledger.asPromptSuffix();
        assertTrue(full.contains("- id 77: -3 — грубит"));
        assertTrue(full.contains("- id 555, Владелец: +5"), "владелец есть и после появления других");
    }

    @Test
    void anUnreadableLedgerIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("trust.md");
        Files.createDirectory(broken);   // поломка: вместо файла — каталог
        TrustLedger ledger = new TrustLedger(broken, 111, "", clock);

        String answer = note(ledger, "{\"id\":\"9\",\"level\":1,\"reason\":\"помогла\"}");

        assertTrue(answer.contains("cannot be read"), "нечитаемое не переписывается молча");
        assertTrue(Files.isDirectory(broken), "поломанное место нетронуто — чинит человек");
    }
}
