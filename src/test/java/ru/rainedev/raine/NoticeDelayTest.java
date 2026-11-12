package ru.rainedev.raine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.RaineApp.NoticeLane;
import ru.rainedev.raine.config.Config;

/**
 * Человек не бросается к телефону в ту же секунду: он сначала смотрит на экран
 * блокировки и уже там решает, отвечать или нет. У папика эта пауза была и
 * раньше; остальным нужна своя полоса «глянула», а не тот же самый разброс от
 * нуля. Догон непрочитанного не ждёт вовсе: после простоя экран уже виден, а
 * пауза на каждый чат вешала бы очередь на минуты.
 */
class NoticeDelayTest {

    @TempDir Path dir;

    /** Случайность, которая помнит, до скольки её спросили, и отвечает как велено. */
    private static final class Asked extends Random {
        private final long answer;
        private long askedBound = -1;

        Asked(long answer) {
            this.answer = answer;
        }

        @Override
        public long nextLong(long bound) {
            askedBound = bound;
            return Math.min(answer, bound - 1);
        }
    }

    /**
     * Полоса глазами случайности: [0] — пауза в миллисекундах, [1] — до скольки её
     * попросили выбрать (верхний край исключителен).
     */
    private static long[] asked(NoticeLane lane, int owner, int min, int max, long answer) {
        Asked random = new Asked(answer);
        long millis = RaineApp.noticeDelayMillis(lane, owner, min, max, random);
        assertTrue(millis >= 0, "пауза не может быть отрицательной: " + millis);
        return new long[] {millis, random.askedBound};
    }

    /**
     * Пауза владельца — окно, а не лотерея: в ней сидит скользящий дебаунс, и
     * провал в ноль наполовину сообщений лишил бы его смысла.
     */
    @Test
    void theOwnerWaitsTheWholeWindow() {
        Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            assertEquals(30_000, RaineApp.noticeDelayMillis(NoticeLane.IMPORTANT, 30, 10, 40, random),
                    "папик ждёт всю полосу, но не дольше неё");
        }
        assertEquals(-1, asked(NoticeLane.IMPORTANT, 30, 10, 40, 0)[1],
                "случайность для этого не спрашивается вовсе");
    }

    @Test
    void everyoneElseLooksAtTheLockScreen() {
        Random random = new Random(11);
        int quickest = Integer.MAX_VALUE;
        int slowest = 0;
        for (int i = 0; i < 400; i++) {
            int millis = (int) RaineApp.noticeDelayMillis(NoticeLane.OTHER, 30, 10, 40, random);
            assertTrue(millis >= 10_000 && millis <= 40_000,
                    "чужое сообщение смотрят от 10 до 40 секунд, а выпало " + millis);
            quickest = Math.min(quickest, millis);
            slowest = Math.max(slowest, millis);
        }
        assertTrue(quickest < 20_000 && slowest > 30_000,
                "полоса должна быть разбросом, а не константой: от " + quickest + " до " + slowest);
    }

    @Test
    void theBandIncludesBothItsEnds() {
        long[] bottom = asked(NoticeLane.OTHER, 30, 10, 40, 0);
        assertEquals(30_001, bottom[1], "полоса 10..40 спрашивается целиком, с обоими краями");
        assertEquals(10_000, bottom[0], "дно полосы — ровно min");
        assertEquals(40_000, asked(NoticeLane.OTHER, 30, 10, 40, 30_000)[0], "верх полосы — ровно max");
    }

    @Test
    void catchingUpOnUnreadWaitsForNothing() {
        assertEquals(0, RaineApp.noticeDelayMillis(NoticeLane.CAUGHT_UP, 30, 10, 40, new Random(3)),
                "догон непрочитанного не спит на каждый чат");
        assertEquals(-1, asked(NoticeLane.CAUGHT_UP, 30, 10, 40, 0)[1],
                "догон не спрашивает случайность вовсе");
        assertEquals(0, RaineApp.noticeDelayMillis(NoticeLane.CAUGHT_UP, 0, 0, 0, new Random(3)));
    }

    @Test
    void eachLaneTurnsOffByItsOwnNumber() {
        assertEquals(0, RaineApp.noticeDelayMillis(NoticeLane.IMPORTANT, 0, 10, 40, new Random(5)),
                "нулевой notice_delay_seconds — папик отвечает мгновенно");
        assertEquals(0, RaineApp.noticeDelayMillis(NoticeLane.OTHER, 30, 10, 0, new Random(5)),
                "пустая полоса выключает ожидание, сколько бы ни стояло в min");
    }

    @Test
    void aBandWiderThanItselfCollapsesToItsTop() {
        long[] flipped = asked(NoticeLane.OTHER, 30, 40, 10, 0);
        assertEquals(1, flipped[1], "перепутанные местами min и max складываются в одну точку");
        assertEquals(10_000, flipped[0], "точкой этой служит верх полосы, а не отрицательный разброс");
    }

    @Test
    void aNegativeBottomReadsAsZero() {
        long[] negative = asked(NoticeLane.OTHER, 30, -5, 40, 0);
        assertEquals(40_001, negative[1], "минус в min читается как ноль, а не уводит полосу в минус");
        assertEquals(0, negative[0]);
    }

    private Config config(String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, """
                api_id=1
                api_hash=test-hash
                phone=+70000000000
                llm_base_url=http://primary.invalid/v1
                llm_model=primary
                llm_api_key=test-primary-key
                """ + extra);
        return Config.load(file);
    }

    /** Соседей по списку настроек легко переставить при чтении — компилятор не заметит. */
    @Test
    void threeNumbersLandInTheirOwnFields() throws Exception {
        Config defaults = config("");
        assertEquals(30, defaults.noticeDelaySeconds(), "папик ждёт ровно столько, сколько ждал");
        assertEquals(10, defaults.noticeDelayOtherMinSeconds());
        assertEquals(40, defaults.noticeDelayOtherMaxSeconds());

        Config written = config("""
                notice_delay_seconds=2
                notice_delay_other_min_seconds=3
                notice_delay_other_max_seconds=7
                """);
        assertEquals(List.of(2, 3, 7), List.of(written.noticeDelaySeconds(),
                        written.noticeDelayOtherMinSeconds(), written.noticeDelayOtherMaxSeconds()),
                "каждая цифра читается в своё поле, края полосы не перепутаны местами");
    }
}
