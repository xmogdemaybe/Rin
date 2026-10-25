package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Состояния распознавания речи Telegram разбираются без Telegram и без таймера:
 * у ожидания три исхода — готовый текст, отказ и оборванный кусок, и путать их
 * нельзя. Отказ приходится ждать дольше всего ровно потому, что он неотличим от
 * «ещё не готово».
 */
class SpeechWaitTest {

    private static TdApi.Message voice(TdApi.SpeechRecognitionResult result) {
        TdApi.VoiceNote note = new TdApi.VoiceNote();
        note.speechRecognitionResult = result;
        TdApi.MessageVoiceNote content = new TdApi.MessageVoiceNote();
        content.voiceNote = note;
        TdApi.Message message = new TdApi.Message();
        message.id = 7;
        message.chatId = 1;
        message.content = content;
        return message;
    }

    @Test
    void aFinishedTranscriptIsTakenWithoutWaiting() {
        Telegram.Wait wait = Telegram.Wait.of(voice(new TdApi.SpeechRecognitionResultText("привет, папик")));

        assertEquals(Optional.of("привет, папик"), wait.heard());
        assertFalse(wait.givenUp(), "готовая расшифровка — не отказ");
    }

    @Test
    void aRefusedRecognitionIsNotWaitedOut() {
        TdApi.Error reason = new TdApi.Error();
        reason.code = 400;
        reason.message = "BAD_REQUEST: nothing to recognize";
        TdApi.SpeechRecognitionResultError refused = new TdApi.SpeechRecognitionResultError();
        refused.error = reason;

        Telegram.Wait wait = Telegram.Wait.of(voice(refused));

        assertTrue(wait.givenUp(), "отказ не превратится в текст: полчаса на него не тратят");
        assertEquals(Optional.empty(), wait.heard());
    }

    @Test
    void aPartialTranscriptIsKeptAsReserve() {
        TdApi.SpeechRecognitionResultPending pending = new TdApi.SpeechRecognitionResultPending();
        pending.partialText = "он говорил про";

        Telegram.Wait wait = Telegram.Wait.of(voice(pending));

        assertEquals(Optional.empty(), wait.heard(), "оборванный кусок — не готовая расшифровка");
        assertFalse(wait.givenUp(), "распознавание ещё идёт: сдаваться рано");
        assertEquals("он говорил про", wait.promised(), "начало честнее молчания, когда время вышло");
    }

    @Test
    void nothingAnnouncedIsStillNothing() {
        assertEquals(Telegram.Wait.NOTHING, Telegram.Wait.of(voice(null)), "расознавания не было вовсе");

        TdApi.SpeechRecognitionResultPending blank = new TdApi.SpeechRecognitionResultPending();
        blank.partialText = "   ";
        assertEquals("", Telegram.Wait.of(voice(blank)).promised(), "пустая обещалка — не резерв");
    }
}
