package ru.rainedev.raine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

/**
 * Открытый чат Telegram считает прочитанным, и уведомление, до которого не
 * дошла очередь, пропало молча: догон после перезапуска смотрит на счётчик
 * непрочитанных и видит ноль. Со схемой TDLib 1.8 это не чинится счётчиком —
 * отметка «не прочитано» живёт отдельным флагом (chat.is_marked_as_unread), и
 * снимается она сама, стоит чат прочитать (updateChatIsMarkedAsUnread:
 * «a chat was marked as unread or was read»).
 */
class UnreadChatPolicyTest {

    private static TdApi.Chat chat(boolean hisLastWord, int unread, boolean markedAsUnread) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.unreadCount = unread;
        chat.isMarkedAsUnread = markedAsUnread;
        TdApi.Message last = new TdApi.Message();
        last.id = 42;
        last.isOutgoing = !hisLastWord;
        chat.lastMessage = last;
        return chat;
    }

    @Test
    void aChatWhoseLastWordIsHisStillWaitsForAnAnswer() {
        assertTrue(RaineApp.stillUnanswered(chat(true, 0, false)),
                "его реплика на конце чата — значит ответа нет и отметку снимать можно");
    }

    @Test
    void aChatSheAlreadyAnsweredIsLeftAlone() {
        assertFalse(RaineApp.stillUnanswered(chat(false, 0, false)),
                "последнее слово её: поднять чат значит ответить на вчерашнее сегодня");
    }

    @Test
    void aChatWithoutMessagesIsNotPulledIntoTheConversation() {
        TdApi.Chat empty = new TdApi.Chat();
        assertFalse(RaineApp.stillUnanswered(empty), "нечего и помечать — последнего сообщения нет");
    }

    @Test
    void theMarkCountsAsWaitingEvenWithAnEmptyCounter() {
        // здесь и держится весь почин: счётчик обнулён открытием чата, а
        // отметка — отдельный флаг, и догон обязан смотреть на него тоже
        assertTrue(RaineApp.awaitsAnswer(chat(true, 0, true)),
                "помеченный непрочитанным чат с нулевым счётчиком пропал бы для догона");
    }

    @Test
    void unreadMessagesAloneStillCounts() {
        assertTrue(RaineApp.awaitsAnswer(chat(true, 3, false)));
    }

    @Test
    void aReadChatWithAnAnswerIsLeftOutOfTheCatchUp() {
        assertFalse(RaineApp.awaitsAnswer(chat(false, 0, false)));
    }
}
