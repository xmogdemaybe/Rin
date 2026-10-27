package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

/**
 * На пересылку TDLib отвечает списком, в котором вместо сообщения, которое
 * переслать не удалось, стоит null. Отсутствие ошибки здесь ещё не успех:
 * считать надо полученные сообщения, а не запрошенные номера.
 */
class ForwardCountTest {

    @Test
    void nullsInsteadOfMessagesAreNotCountedAsForwarded() {
        assertEquals(2, TelegramActions.Live.forwarded(
                messages(new TdApi.Message(), null, new TdApi.Message())));
    }

    @Test
    void anEmptyOrMissingAnswerMeansNothingWasForwarded() {
        assertEquals(0, TelegramActions.Live.forwarded(messages()));
        assertEquals(0, TelegramActions.Live.forwarded(null));

        TdApi.Messages noList = new TdApi.Messages();
        noList.messages = null;
        assertEquals(0, TelegramActions.Live.forwarded(noList));
    }

    private static TdApi.Messages messages(TdApi.Message... got) {
        TdApi.Messages result = new TdApi.Messages();
        result.totalCount = got.length;
        result.messages = got;
        return result;
    }
}
