package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Сколько ссылок раскрывать на экране и как часто для этого обращаться к
 * Telegram. Сам Telegram подменён счётчиком обращений.
 */
class TmeLinksTest {

    private final Map<String, TdApi.MessageLinkInfo> known = new HashMap<>();
    private final AtomicInteger asked = new AtomicInteger();
    private final TmeLinks links = new TmeLinks(url -> {
        asked.incrementAndGet();
        return Optional.ofNullable(known.get(url));
    });

    @Test
    void nothingIsResolvedBeforeTheScreenLooksAtIt() {
        known.put("t.me/durov/123", post(5L, 123L, "пост"));

        assertTrue(links.peek("t.me/durov/123").isEmpty(), "до просмотра чата ссылка не раскрыта");
    }

    @Test
    void showsWhatThisScreenWarmed() {
        known.put("t.me/durov/123", post(5L, 123L, "пост"));
        links.lookAhead(List.of(message(1L, "глянь t.me/durov/123")));

        assertEquals(123L, links.peek("t.me/durov/123").orElseThrow().message.id);
    }

    @Test
    void noMoreThanTwoLinksPerMessage() {
        links.lookAhead(List.of(message(1L, "t.me/a/1 t.me/b/2 t.me/c/3")));

        assertEquals(2, asked.get(), "третья ссылка того же сообщения уже не разбор");
    }

    @Test
    void noMoreThanThreeLinksPerScreen() {
        links.lookAhead(List.of(
                message(1L, "t.me/a/1"), message(2L, "t.me/b/2"),
                message(3L, "t.me/c/3"), message(4L, "t.me/d/4")));

        assertEquals(3, asked.get(), "экран не превращается в ленту пересказов");
    }

    /** Битый или закрытый адрес: спросили один раз и больше не долбят. */
    @Test
    void deadLinkIsNotAskedAgain() {
        links.lookAhead(List.of(message(1L, "t.me/dead/1")));
        links.lookAhead(List.of(message(1L, "t.me/dead/1")));

        assertEquals(1, asked.get(), "отрицательный ответ — тоже память");
    }

    @Test
    void resolvedLinkIsRememberedForTheNextScreen() {
        known.put("t.me/a/1", post(5L, 1L, "пост"));
        links.lookAhead(List.of(message(1L, "t.me/a/1")));
        links.lookAhead(List.of(message(1L, "t.me/a/1")));

        assertEquals(1, asked.get(), "час живёт — весь час не спрашиваем");
        assertTrue(links.peek("t.me/a/1").isPresent());
    }

    private static TdApi.Message message(long id, String text) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.chatId = 1L;
        message.date = 1_780_000_000;
        message.content = new TdApi.MessageText(new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, null);
        return message;
    }

    private static TdApi.MessageLinkInfo post(long chatId, long messageId, String text) {
        TdApi.Message found = message(messageId, text);
        found.chatId = chatId;
        TdApi.MessageLinkInfo info = new TdApi.MessageLinkInfo();
        info.isPublic = true;
        info.message = found;
        return info;
    }
}
