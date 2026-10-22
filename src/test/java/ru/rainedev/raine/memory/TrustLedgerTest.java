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
import java.util.List;
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
    void colonInNameDoesNotBlindTheLedger(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);

        note(ledger, "{\"id\":\"4567\",\"name\":\"Маша: соседка\",\"level\":4,\"reason\":\"проводила домой\"}");

        assertEquals(List.of(new TrustLedger.Entry(4567, "Маша соседка", 4)), ledger.entries(),
                "двоеточие — разделитель формата строки; в имени оно сломало бы чтение");
        assertTrue(ledger.aboutPerson(4567).contains("<person_right_now>"),
                "непрочитанная запись означала бы, что о человеке она не знает вовсе");
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
    void trustLevelReachesThePromptAsBehaviour(@TempDir Path dir) throws Exception {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "", clock);
        note(ledger, "{\"id\":\"4567\",\"name\":\"Вика\",\"level\":4,\"reason\":\"вытащила просто так\"}");
        note(ledger, "{\"id\":\"9\",\"level\":-3,\"reason\":\"грубит\"}");

        String close = ledger.aboutPerson(4567);
        assertTrue(close.contains("<person_right_now>"), close);
        assertTrue(close.contains("id 4567 (Вика)"), "она видит, кто перед ней, а не голый id");
        assertTrue(close.contains("+4"), close);
        assertTrue(close.contains("Be warm"), "уровень звучит как поведение, а не как цифра");

        String crossed = ledger.aboutPerson(9);
        assertTrue(crossed.contains("-3"), crossed);
        assertTrue(crossed.contains("do not trust them at all"), crossed);
        assertFalse(crossed.contains("Be warm"), "с тем, кто перегнул, тёплый тон неуместен");
    }

    @Test
    void unknownPersonAndOwnerGetNoLine(@TempDir Path dir) {
        TrustLedger ledger = new TrustLedger(dir.resolve("trust.md"), 111, "Владелец", clock);

        assertEquals("", ledger.aboutPerson(9999), "отношение ещё не сложилось — подсказывать нечего");
        assertEquals("", ledger.aboutPerson(111), "строка владельца и так в промпте навсегда");
        assertEquals("", ledger.aboutPerson(0), "событие не про человека");
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

    @Test
    void entriesAreReadBackExactlyAsWritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trust.md");
        Files.writeString(file, """
                - id 42, Вика: +3 — выручала (2026-10-21)
                - id 77: +5 — свой человек (2026-10-20)
                - id 9, Тоня: -1 — интригует (2026-10-19)
                ручная строка без формата
                """);
        TrustLedger ledger = new TrustLedger(file, 111, "", clock);

        List<TrustLedger.Entry> entries = ledger.entries();

        assertEquals(3, entries.size(), "строка без формата — не запись");
        assertEquals(new TrustLedger.Entry(42, "Вика", 3), entries.get(0));
        assertEquals(new TrustLedger.Entry(77, "", 5), entries.get(1));
        assertEquals(new TrustLedger.Entry(9, "Тоня", -1), entries.get(2));
    }

    @Test
    void emptyOrMissingLedgerMeansNobody(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), new TrustLedger(dir.resolve("trust.md"), 111, "", clock).entries(),
                "файла нет — писать первой некому");
        Files.writeString(dir.resolve("trust.md"), "");
        assertEquals(List.of(), new TrustLedger(dir.resolve("trust.md"), 111, "", clock).entries(),
                "файл пуст — тоже некому");
    }
}
