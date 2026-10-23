package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Telegram не отказывает в выкладывании истории ошибкой — он отвечает перечнем
 * случаев, и каждый значит своё: где-то надо подождать сутки, где-то не ждать
 * вовсе. Без перевода она гадала бы и пробовала снова и снова.
 */
class StoryPostingTest {

    private static final List<TdApi.CanPostStoryResult> REFUSALS = List.of(
            new TdApi.CanPostStoryResultPremiumNeeded(),
            new TdApi.CanPostStoryResultBoostNeeded(),
            new TdApi.CanPostStoryResultActiveStoryLimitExceeded(),
            new TdApi.CanPostStoryResultWeeklyLimitExceeded(),
            new TdApi.CanPostStoryResultMonthlyLimitExceeded(),
            new TdApi.CanPostStoryResultLiveStoryIsActive());

    @Test
    void okMeansNothingInHerWay() {
        assertEquals("", TelegramTools.postRefusal(new TdApi.CanPostStoryResultOk()));
    }

    @Test
    void premiumIsAskedForInPlainWords() {
        // вот то самое отличие просмотра от выкладывания: смотреть можно без
        // премиума, а выкладывать — нельзя, и ей это нужно знать своими словами
        assertTrue(TelegramTools.postRefusal(new TdApi.CanPostStoryResultPremiumNeeded()).contains("Premium"));
    }

    @Test
    void aStoryAlreadyUpMeansWaitingNotRetrying() {
        String refusal = TelegramTools.postRefusal(new TdApi.CanPostStoryResultActiveStoryLimitExceeded());

        assertTrue(refusal.contains("already"), refusal);
        assertTrue(refusal.contains("expire"), "ждать нужно сутки, а не пробовать снова: " + refusal);
    }

    @Test
    void everyRefusalIsToldApart() {
        List<String> answers = REFUSALS.stream().map(TelegramTools::postRefusal).toList();

        assertEquals(REFUSALS.size(), answers.stream().distinct().count(),
                "разные причины должны звучать по-разному: " + answers);
        answers.forEach(answer -> assertFalse(answer.isBlank(), "промолчать — значит оставить её гадать"));
    }

    @Test
    void aRefusalDoesNotLeakIntoTheMarkup() {
        // ответ уходит в разметку, которую читает модель: скобок в нём быть не должно
        REFUSALS.forEach(result -> {
            String refusal = TelegramTools.postRefusal(result);
            assertFalse(refusal.contains("<"), refusal);
            assertFalse(refusal.contains(">"), refusal);
        });
    }

    @Test
    void silenceFromTelegramIsNotTakenForPermission() {
        // спросить не вышло — значит, и выкладывать наугад не стоит
        String refusal = TelegramTools.postRefusal(null);

        assertFalse(refusal.isBlank(), refusal);
        assertTrue(refusal.contains("later"), refusal);
    }
}
