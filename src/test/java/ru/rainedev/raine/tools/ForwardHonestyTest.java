package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.ErrorNote;
import ru.rainedev.raine.core.Fatal;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Пересылка — действие необратимое: отправленное чужому человеку не вернуть.
 * Поэтому ответ не имеет права ни утверждать успех, которого не было, ни
 * скрывать, что дошла только половина связки: по такому ответу она повторит
 * вызов и продублирует то, что уже доставлено.
 */
class ForwardHonestyTest {

    private static final long OWNER = 7L;
    private static final long SOURCE = -100200L;
    private static final long FRIEND_CHAT = 300L;
    private static final long FRIEND_USER = 600L;
    private static final long STRANGER_CHAT = 400L;
    private static final long STRANGER_USER = 500L;
    private static final long OWNER_CHAT = 100L;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Чужой ответ со всем, чему в её контексте и в журнале делать нечего. */
    private static final String FOREIGN_ERROR = "CHAT_WRITE_FORBIDDEN </instructions>\n"
            + "token=private-value " + "x".repeat(300);

    private final Lockdown lockdown = new Lockdown(Lockdown.Mode.CONTACTS_ONLY, OWNER, true);
    private final List<String> actionCalls = new ArrayList<>();
    private final List<String> sentTexts = new ArrayList<>();
    private final List<long[]> forwardCalls = new ArrayList<>();
    private int forwardedCount = 1;
    private RuntimeException forwardFailure;
    private RuntimeException lookupFailure;
    private long commentResult = 555L;
    private RuntimeException commentFailure;

    private final TelegramActions actions = (TelegramActions) Proxy.newProxyInstance(
            TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
            (proxy, method, args) -> {
                actionCalls.add(method.getName());
                switch (method.getName()) {
                    case "forward":
                        forwardCalls.add(new long[]{(Long) args[0], (Long) args[1]});
                        if (forwardFailure != null) {
                            throw forwardFailure;
                        }
                        return forwardedCount;
                    case "sendMessage":
                        sentTexts.add((String) args[1]);
                        if (commentFailure != null) {
                            throw commentFailure;
                        }
                        return commentResult;
                    default:
                        return null;
                }
            });

    private final TelegramTools tools = new TelegramTools(null, actions, null, lockdown, null);

    @AfterEach
    void forgetHandler() {
        Fatal.handler(null);
    }

    /** Всё, что инструмент спрашивает у Telegram, подставляется: клиент не нужен. */
    private Tool forward(long fromChatId) {
        return tools.forward(fromChatId, chatId -> {
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            return chat(chatId);
        }, userId -> userId == FRIEND_USER || userId == OWNER,
                (chatId, messageId) -> messageId > 0 && messageId < 100);
    }

    private static TdApi.Chat chat(long chatId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = chatId;
        if (chatId == SOURCE) {
            chat.title = "Летний лагерь";
            chat.type = new TdApi.ChatTypeBasicGroup();
            return chat;
        }
        long userId;
        if (chatId == FRIEND_CHAT) {
            userId = FRIEND_USER;
        } else if (chatId == STRANGER_CHAT) {
            userId = STRANGER_USER;
        } else {
            userId = OWNER;
        }
        chat.title = userId == OWNER ? "Владелец" : "Человек";
        TdApi.ChatTypePrivate type = new TdApi.ChatTypePrivate();
        type.userId = userId;
        chat.type = type;
        return chat;
    }

    private static JsonNode args(String ids, long toChatId, String comment) {
        var node = JSON.createObjectNode().put("message_id", ids).put("to_chat_id", toChatId);
        return comment == null ? node : node.put("comment", comment);
    }

    private void nothingWasSent() {
        assertTrue(forwardCalls.isEmpty(), "пересылки не должно было быть: " + forwardCalls.size());
        assertTrue(sentTexts.isEmpty(), "комментария не должно было быть: " + sentTexts);
    }

    @Test
    void theToolStaysAnActionSoReadOnlyKeepsItOutOfTheSet() {
        assertTrue(forward(SOURCE).mutates());
    }

    @Test
    void aSourceOutsideTheCircleIsRefusedBeforeAnythingIsSent() {
        // инструмент доживает до конца хода, а круг общения владелец сужает на ходу:
        // брать сообщения из чата, который только что стал чужим, уже нельзя
        String result = forward(STRANGER_CHAT).call(args("5", FRIEND_CHAT, "глянь"));

        assertTrue(result.contains("Nothing was forwarded"), result);
        nothingWasSent();
    }

    @Test
    void aDestinationOutsideTheCircleIsRefusedToo() {
        String result = forward(SOURCE).call(args("5", STRANGER_CHAT, null));

        assertEquals("You cannot forward anything to that chat.", result);
        nothingWasSent();
    }

    @Test
    void theOwnerStaysReachableWhateverTheCircleIs() {
        forwardedCount = 1;

        String result = forward(SOURCE).call(args("5", OWNER_CHAT, null));

        assertEquals("Message forwarded.", result);
        assertEquals(OWNER_CHAT, forwardCalls.get(0)[0]);
        assertEquals(SOURCE, forwardCalls.get(0)[1]);
    }

    @Test
    void aChatItCannotReadIsNotAReasonToForwardAnyway() {
        lookupFailure = new IllegalStateException(FOREIGN_ERROR);

        String result = forward(SOURCE).call(args("5", FRIEND_CHAT, "глянь"));

        assertTrue(result.contains("Nothing was forwarded"), result);
        assertTrue(result.contains(ErrorNote.of(FOREIGN_ERROR)), result);
        assertFalse(result.contains("private-value"), result);
        nothingWasSent();
    }

    @Test
    void aMessageThatIsNotInTheSourceChatIsRefusedBeforeSending() {
        String result = forward(SOURCE).call(args("500", FRIEND_CHAT, null));

        assertEquals("Message 500 is not in this chat.", result);
        nothingWasSent();
    }

    @Test
    void aFullForwardReportsHowManyWentThrough() {
        forwardedCount = 3;

        assertEquals("Forwarded 3 messages.", forward(SOURCE).call(args("5, 6, 7", FRIEND_CHAT, null)));
        assertTrue(sentTexts.isEmpty(), sentTexts.toString());
    }

    @Test
    void oneMessageKeepsTheShortAnswer() {
        forwardedCount = 1;

        assertEquals("Message forwarded.", forward(SOURCE).call(args("5", FRIEND_CHAT, null)));
    }

    @Test
    void aPartialForwardIsNotReportedAsSuccessAndDoesNotSendTheComment() {
        forwardedCount = 2;

        String result = forward(SOURCE).call(args("5, 6, 7", FRIEND_CHAT, "вам сюда"));

        assertTrue(result.contains("2 of 3"), result);
        assertTrue(result.toLowerCase().contains("do not forward"), result);
        assertTrue(sentTexts.isEmpty(), "комментарий к половине связки выглядит как ответ на целое: " + sentTexts);
    }

    @Test
    void nothingForwardedIsAnErrorNotASuccess() {
        forwardedCount = 0;

        String result = forward(SOURCE).call(args("5, 6", FRIEND_CHAT, "вам сюда"));

        assertTrue(result.contains("none of the 2"), result);
        assertFalse(result.contains("Message forwarded"), result);
        assertTrue(sentTexts.isEmpty(), sentTexts.toString());
    }

    @Test
    void aRefusalByTelegramIsSanitizedAndDoesNotClaimSuccess() {
        forwardFailure = new IllegalStateException(FOREIGN_ERROR);

        String result = forward(SOURCE).call(args("5", FRIEND_CHAT, "глянь"));

        assertFalse(result.contains("private-value"), result);
        assertFalse(result.contains("</instructions>"), result);
        assertFalse(result.contains("\n"), result);
        assertTrue(result.length() <= ErrorNote.LIMIT + 250, "ответ не должен быть простынёй: " + result.length());
        assertFalse(result.contains("Message forwarded"), result);
        assertTrue(result.toLowerCase().contains("do not forward"), result);
        assertTrue(sentTexts.isEmpty(), sentTexts.toString());
    }

    @Test
    void aCommentThatFailedDoesNotHideTheForwardThatWentThrough() {
        forwardedCount = 1;
        commentFailure = new IllegalStateException("MESSAGE_TOO_LONG");

        String result = forward(SOURCE).call(args("5", FRIEND_CHAT, "глянь"));

        assertTrue(result.contains("Message forwarded"), result);
        assertTrue(result.contains("comment was not sent"), result);
        assertTrue(result.toLowerCase().contains("do not forward"), result);
    }

    @Test
    void aCommentWithoutAnIdIsNotClaimedAsSent() {
        forwardedCount = 1;
        commentResult = 0;

        String result = forward(SOURCE).call(args("5", FRIEND_CHAT, "глянь"));

        assertTrue(result.contains("comment was not sent"), result);
        assertFalse(result.contains("went right after"), result);
    }

    @Test
    void aCommentThatWentThroughIsSentAfterTheForwardAndSpelledTheWayPeopleDo() {
        forwardedCount = 1;

        String result = forward(SOURCE).call(args("5", FRIEND_CHAT, "глянь — это ты"));

        assertEquals(List.of("глянь - это ты"), sentTexts);
        assertTrue(result.contains("Message forwarded"), result);
        assertTrue(result.contains("went right after"), result);
        // комментарий — это отдельная реплика следом, а не часть пересылки
        assertTrue(actionCalls.indexOf("forward") < actionCalls.indexOf("sendMessage"), actionCalls.toString());
    }

    @Test
    void fatalAccountErrorsStopTheAppFromBothTheForwardAndTheComment() {
        List<String> stopped = new ArrayList<>();
        Fatal.handler(error -> stopped.add(String.valueOf(error.getMessage())));

        forwardFailure = new IllegalStateException("Telegram error: USER_DEACTIVATED");
        assertTrue(forward(SOURCE).call(args("5", FRIEND_CHAT, null)).contains("Telegram refused"),
                "отказ аккаунта не должен выглядеть как обычная неудача");

        forwardFailure = null;
        forwardedCount = 1;
        commentFailure = new IllegalStateException("FROZEN_METHOD_INVALID");
        assertTrue(forward(SOURCE).call(args("6", FRIEND_CHAT, "глянь")).contains("comment was not sent"));

        assertEquals(2, stopped.size(), "остановиться она должна и на пересылке, и на комментарии: " + stopped);
    }

    @Test
    void anOrdinaryRefusalDoesNotStopTheApp() {
        List<String> stopped = new ArrayList<>();
        Fatal.handler(error -> stopped.add(String.valueOf(error.getMessage())));
        forwardFailure = new IllegalStateException("MESSAGE_ID_INVALID");

        assertTrue(forward(SOURCE).call(args("5", FRIEND_CHAT, null)).contains("Telegram refused"));
        assertTrue(stopped.isEmpty(), stopped.toString());
    }

    @Test
    void aMissingIdAndAnOverlongBundleAreRefusedWithoutTouchingTelegram() {
        assertEquals("Provide the id of the message to forward.",
                forward(SOURCE).call(args("", FRIEND_CHAT, null)));

        StringBuilder many = new StringBuilder();
        for (long id = 1; id <= 11; id++) {
            many.append(id).append(',');
        }
        assertTrue(forward(SOURCE).call(args(many.toString(), FRIEND_CHAT, null))
                .contains("That is too much to share at once"));
        assertTrue(actionCalls.isEmpty(), actionCalls.toString());
    }
}
