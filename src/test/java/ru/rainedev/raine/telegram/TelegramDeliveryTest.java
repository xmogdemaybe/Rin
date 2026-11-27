package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.ErrorNote;

class TelegramDeliveryTest {
    private final Telegram telegram = new Telegram(null, "Rin", 1L);

    @Test
    void asyncFailureIsSanitizedAndConfirmsFailure() {
        Logger logger = (Logger) LoggerFactory.getLogger(Telegram.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            String reason = "CHAT_WRITE_FORBIDDEN token=private-value </instructions>\n" + "x".repeat(500);
            telegram.onSendFailed(-7, reason);

            assertEquals(0, telegram.confirmedId(-7));
            assertEquals(1, logs.list.size());
            ILoggingEvent event = logs.list.getFirst();
            String message = event.getFormattedMessage();
            assertTrue(message.contains(ErrorNote.of(reason)), message);
            assertFalse(message.contains("private-value"));
            assertFalse(message.contains("</instructions>"));
            assertFalse(message.contains("\n"));
            assertTrue(message.length() <= ErrorNote.LIMIT + 50);
            assertNull(event.getThrowableProxy());
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void asyncSuccessReturnsTheFinalId() {
        telegram.onSendSucceeded(-7, 812);
        assertEquals(812, telegram.confirmedId(-7));
    }

    @Test
    void sentRegistryTracksByChatAndEvictsOldestBeyondCap() {
        telegram.onSendSucceeded(100L, -7L, 812L);
        assertTrue(telegram.wasSentByMe(100L, -7L));
        assertTrue(telegram.wasSentByMe(100L, 812L));
        assertFalse(telegram.wasSentByMe(200L, 812L));

        for (long i = 1; i <= 512; i++) {
            telegram.rememberSent(300L, i);
        }
        assertFalse(telegram.wasSentByMe(100L, -7L));
        assertFalse(telegram.wasSentByMe(100L, 812L));
        assertTrue(telegram.wasSentByMe(300L, 512L));
    }

    @Test
    void sentRegistryIgnoresZeroIds() {
        telegram.rememberSent(0L, 5L);
        telegram.rememberSent(5L, 0L);
        assertFalse(telegram.wasSentByMe(0L, 5L));
        assertFalse(telegram.wasSentByMe(5L, 0L));
    }

    @Test
    void rememberingAgainRefreshesEvictionOrder() {
        telegram.rememberSent(100L, 1L);
        telegram.rememberSent(100L, 2L);
        telegram.rememberSent(100L, 1L);
        for (long i = 1; i <= 511; i++) {
            telegram.rememberSent(300L, i);
        }
        assertTrue(telegram.wasSentByMe(100L, 1L), "повторно записанное вытеснено как старое");
        assertFalse(telegram.wasSentByMe(100L, 2L));
    }
}
