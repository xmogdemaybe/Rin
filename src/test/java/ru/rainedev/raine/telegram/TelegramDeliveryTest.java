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
}
