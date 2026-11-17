package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /** Объекты раскрываются своим бюджетом и запоминаются, как и посты. */
    @Test
    void objectsAreResolvedOnceAndRemembered() {
        AtomicInteger objectAsked = new AtomicInteger();
        TmeLinks both = new TmeLinks(url -> Optional.empty(), username -> {
            objectAsked.incrementAndGet();
            return Optional.of(object(username));
        });

        both.lookAhead(List.of(message(1L, "глянь t.me/blameyourselfandnotme")));
        both.lookAhead(List.of(message(1L, "глянь t.me/blameyourselfandnotme")));

        assertEquals(1, objectAsked.get(), "и положительный, и отрицательный ответ живут час");
        assertTrue(both.peekObject("blameyourselfandnotme").isPresent());
    }

    @Test
    void noMoreThanTwoObjectsPerScreen() {
        AtomicInteger objectAsked = new AtomicInteger();
        TmeLinks both = new TmeLinks(url -> Optional.empty(), username -> {
            objectAsked.incrementAndGet();
            return Optional.of(object(username));
        });

        both.lookAhead(List.of(message(1L, "t.me/aaa11"), message(2L, "t.me/bbb22"),
                message(3L, "t.me/ccc33")));

        assertEquals(TmeLinks.OBJECTS_PER_SCREEN, objectAsked.get(), "экран не превращается в ленту каналов");
    }

    @Test
    void objectBudgetDoesNotEatThePostBudget() {
        AtomicInteger objectAsked = new AtomicInteger();
        TmeLinks both = new TmeLinks(url -> {
            asked.incrementAndGet();
            return Optional.empty();
        }, username -> {
            objectAsked.incrementAndGet();
            return Optional.of(object(username));
        });

        both.lookAhead(List.of(message(1L, "t.me/aaa11 t.me/durov/1 t.me/bbb22 t.me/durov/2")));

        assertEquals(2, objectAsked.get(), "объекты по своему бюджету");
        assertEquals(2, asked.get(), "посты по своему");
    }

    /** Объект, найденный в ходу владельца, — его наводка на весь разговор. */
    @Test
    void aLinkFromTheOwnersTurnBecomesHisLead() {
        ru.rainedev.raine.core.OwnerLeads leads = new ru.rainedev.raine.core.OwnerLeads();
        leads.ownerTurn(() -> true);
        TmeLinks both = new TmeLinks(url -> Optional.empty(), username -> Optional.of(object(username)));
        both.leads(leads);

        both.lookAhead(List.of(message(1L, "глянь t.me/blameyourselfandnotme")));

        assertEquals(1, leads.size(), "номер чата запомнен: «да, напиши» придёт следующим сообщением");
    }

    /** Ссылка в чужом ходу наводкой не становится. */
    @Test
    void aLinkFromAStrangerTurnIsNotALead() {
        ru.rainedev.raine.core.OwnerLeads leads = new ru.rainedev.raine.core.OwnerLeads();
        TmeLinks both = new TmeLinks(url -> Optional.empty(), username -> Optional.of(object(username)));
        both.leads(leads);

        both.lookAhead(List.of(message(1L, "t.me/blameyourselfandnotme")));

        assertEquals(0, leads.size(), "иначе любой пост в группе открывал бы путь в личку");
    }

    private static ru.rainedev.raine.phone.TelegramObject object(String username) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = -100L;
        chat.title = username;
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.supergroupId = 7L;
        type.isChannel = true;
        chat.type = type;
        return new ru.rainedev.raine.phone.TelegramObject(chat, "", 0, List.of());
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
