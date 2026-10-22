package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.Telegram;

/**
 * Круг общения проверяется и на входящих, и при открытии чата — но поиск
 * обходил обе проверки: {@code get_telegram_chats} чаты вне круга прячет, а
 * {@code search_chats} выдавал их название и идентификатор любому, с кем
 * говорить разрешено. Идентификатор здесь — не просто число: по нему открывают
 * чат, и показывать его человеку, до которого ей нет дела, значит показывать
 * сам круг её знакомых.
 */
class SearchCircleFilterTest {

    private static final long OWNER = 7L;
    private static final long STRANGER = 500L;
    private static final long FRIEND = 600L;

    /** Ровно то, что передаёт инструмент: тот же Lockdown, тот же признак контакта. */
    private static java.util.function.Predicate<TdApi.Chat> circle(
            Lockdown.Mode mode, boolean channels, long... contacts) {
        Lockdown lockdown = new Lockdown(mode, OWNER, channels);
        Set<Long> known = java.util.Arrays.stream(contacts).boxed()
                .collect(java.util.stream.Collectors.toSet());
        return chat -> lockdown.allows(chat, known.contains(userIdOf(chat)));
    }

    private static long userIdOf(TdApi.Chat chat) {
        return chat.type instanceof TdApi.ChatTypePrivate priv ? priv.userId : 0;
    }

    private static TdApi.Chat person(long id, String title, long userId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = id;
        chat.title = title;
        TdApi.ChatTypePrivate type = new TdApi.ChatTypePrivate();
        type.userId = userId;
        chat.type = type;
        return chat;
    }

    private static TdApi.Chat group(long id, String title) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = id;
        chat.title = title;
        chat.type = new TdApi.ChatTypeBasicGroup();
        return chat;
    }

    private static TdApi.Chat channel(long id, String title) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = id;
        chat.title = title;
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.isChannel = true;
        chat.type = type;
        return chat;
    }

    private static Telegram.Found found(List<TdApi.Chat> mine, List<TdApi.Chat> elsewhere) {
        return new Telegram.Found(mine, elsewhere);
    }

    @Test
    void aPersonOutsideTheCircleIsNotNamedBySearch() {
        // только с владельцем: чат Пети вне круга, и показывать его некому
        String answer = TelegramTools.foundAnswer("П",
                found(List.of(person(1L, "Владелец", OWNER), person(2L, "Петя", STRANGER)), List.of()),
                circle(Lockdown.Mode.OWNER_ONLY, true));

        assertTrue(answer.contains("Владелец"), answer);
        assertFalse(answer.contains("Петя"), "название человека вне круга утекло в переписку");
        assertFalse(answer.contains("\"2\""), "по идентификатору чат открывают — его тоже показывать нечем");
    }

    @Test
    void aContactInsideTheCircleIsStillFindable() {
        // круг по контактам: с ними говорить можно, и искать их — смысл этого
        // инструмента (писать первым по-прежнему разрешает отдельная настройка)
        String answer = TelegramTools.foundAnswer("М",
                found(List.of(person(3L, "Маша", FRIEND)), List.of()),
                circle(Lockdown.Mode.CONTACTS_ONLY, true, FRIEND));

        assertTrue(answer.contains("Маша"), answer);
    }

    @Test
    void aStrangerInACommonGroupIsNotTheReasonToHideTheGroup() {
        // групповой чат — круг знакомых по определению: её туда позвали
        String answer = TelegramTools.foundAnswer("Л",
                found(List.of(group(4L, "Летний лагерь")), List.of()),
                circle(Lockdown.Mode.CONTACTS_ONLY, true));

        assertTrue(answer.contains("Летний лагерь"), answer);
    }

    @Test
    void channelsAnswerTheSameWayTheChatListDoes() {
        Telegram.Found withChannel = found(List.of(), List.of(channel(5L, "Новости")));

        assertFalse(TelegramTools.foundAnswer("Н", withChannel, circle(Lockdown.Mode.OWNER_ONLY, false))
                .contains("Новости"), "каналы выключены — и в поиске их быть не должно");
        assertTrue(TelegramTools.foundAnswer("Н", withChannel, circle(Lockdown.Mode.OWNER_ONLY, true))
                .contains("Новости"), "каналы включены — лента осталась доступна");
    }

    @Test
    void everythingHiddenLooksExactlyLikeNothingFound() {
        // ответ не должен подсказывать, что такой чат всё-таки существует
        String answer = TelegramTools.foundAnswer("Пет",
                found(List.of(person(2L, "Петя", STRANGER)), List.of()),
                circle(Lockdown.Mode.OWNER_ONLY, true));

        assertEquals("Nothing found for: Пет", answer);
    }

    @Test
    void theTwoListsStayApart() {
        String answer = TelegramTools.foundAnswer("В",
                found(List.of(person(1L, "Владелец", OWNER)), List.of(channel(6L, "Ветер"))),
                circle(Lockdown.Mode.NONE, true));

        assertTrue(answer.contains("<chats_you_are_already_in>"), answer);
        assertTrue(answer.contains("<chats_that_do_not_know_you>"), answer);
    }
}
