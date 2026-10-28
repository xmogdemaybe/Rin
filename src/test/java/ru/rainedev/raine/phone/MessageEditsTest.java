package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Прежние слова сообщений. Решают только вместимость и срок — Telegram для
 * этого не нужен.
 */
class MessageEditsTest {

    @Test
    void remembersWhatItShowedLastTime() {
        MessageEdits edits = new MessageEdits();
        edits.remember(1L, 7L, "привет");

        assertEquals("привет", edits.previous(1L, 7L).orElse(""), "второе чтение видит первое");
    }

    @Test
    void aMessageNobodyHasReadYetHasNoPreviousVersion() {
        assertTrue(new MessageEdits().previous(1L, 7L).isEmpty(),
                "первое чтение не должно притворяться правкой");
    }

    /** Номера сообщений в чатах повторяются: семёрка одного чата — не семёрка другого. */
    @Test
    void theSameNumberInAnotherChatIsAnotherMessage() {
        MessageEdits edits = new MessageEdits();
        edits.remember(1L, 7L, "из одного чата");

        assertTrue(edits.previous(2L, 7L).isEmpty(), "чужой текст не переезжает в другой чат");
    }

    /** Прошлое не вечно: вчерашними словами сегодняшнюю правку не объяснить. */
    @Test
    void yesterdayIsNoLongerEvidence() {
        MessageEdits edits = new MessageEdits(512, -1L);
        edits.remember(1L, 7L, "привет");

        assertTrue(edits.previous(1L, 7L).isEmpty(), "просроченное читается как «не помню»");
    }

    /** Переписка длиннее памяти: самое старое вытесняют, а не растут вместе с ней. */
    @Test
    void theOldestVersionMakesRoomForANewOne() {
        MessageEdits edits = new MessageEdits(2, 60_000L);
        edits.remember(1L, 1L, "первое");
        edits.remember(1L, 2L, "второе");
        edits.remember(1L, 3L, "третье");

        assertTrue(edits.previous(1L, 1L).isEmpty(), "первая запись вытеснена");
        assertEquals("второе", edits.previous(1L, 2L).orElse(""));
        assertEquals("третье", edits.previous(1L, 3L).orElse(""));
    }

    /** Без памяти тоже можно жить: правка тогда видна лишь пометкой. */
    @Test
    void memorylessEditsKeepNothing() {
        MessageEdits.NONE.remember(1L, 7L, "привет");

        assertTrue(MessageEdits.NONE.previous(1L, 7L).isEmpty());
    }
}
