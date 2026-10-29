package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Какие адреса считать ссылкой на конкретный пост. Telegram для этого не нужен:
 * раскрывает адрес он сам, а вот выбирать, куда вообще смотреть, здесь.
 */
class TmeLinkTest {

    @Test
    void findsLinkToAPublicPost() {
        assertEquals(List.of("https://t.me/durov/123"),
                TmeLink.find("смотри https://t.me/durov/123 что пишут", 2));
    }

    @Test
    void findsLinkWrittenWithoutProtocol() {
        assertEquals(List.of("t.me/durov/123"), TmeLink.find("вот t.me/durov/123, читай", 2));
    }

    /** Номер сообщения — последняя часть адреса; всё, что после него, к запросу не относится. */
    @Test
    void dropsTheAlbumMarkerButKeepsThePost() {
        assertEquals(List.of("https://t.me/c/1001234567890/45"),
                TmeLink.find("https://t.me/c/1001234567890/45?a=1", 2));
    }

    @Test
    void knowsTheLegacyHost() {
        assertEquals(List.of("https://telegram.me/durov/123"),
                TmeLink.find("https://telegram.me/durov/123", 2));
    }

    /** Форма предпросмотра ведёт на тот же пост, и Telegram раскрывает её сам. */
    @Test
    void acceptsPreviewFormOfTheSamePost() {
        assertEquals(List.of("t.me/s/durov/123"), TmeLink.find("t.me/s/durov/123", 2));
    }

    @Test
    void linkToAChatIsNotALinkToAPost() {
        assertTrue(TmeLink.find("подпишись на t.me/durov", 2).isEmpty());
    }

    /** Приглашения, стикерпаки и чужие сайты раскрывать нечем и незачем. */
    @Test
    void ignoresInvitesStickersAndOtherSites() {
        assertTrue(TmeLink.find("телефонные приглашения не открываю: t.me/joinchat/AAAAAEgT", 2).isEmpty());
        assertTrue(TmeLink.find("набор татуировок: t.me/addstickers/MyPack", 2).isEmpty());
        assertTrue(TmeLink.find("https://youtube.com/watch?v=123", 2).isEmpty());
    }

    @Test
    void dotAtTheEndOfASentenceIsNotPartOfTheAddress() {
        assertEquals(List.of("t.me/durov/123"), TmeLink.find("смотри t.me/durov/123.", 2));
    }

    @Test
    void keepsTheOrderAndStopsAtTheLimit() {
        assertEquals(List.of("t.me/a/1", "t.me/b/2"), TmeLink.find("t.me/a/1 t.me/b/2 t.me/c/3", 2));
    }

    /** Ссылка приходит и в подписи к фото: разбор один на всё содержимое сообщения. */
    @Test
    void looksInsideCaptionsToo() {
        TdApi.MessagePhoto photo = new TdApi.MessagePhoto();
        photo.photo = new TdApi.Photo();
        photo.caption = new TdApi.FormattedText("тут t.me/durov/123", new TdApi.TextEntity[0]);

        TdApi.Message message = new TdApi.Message();
        message.chatId = 1L;
        message.id = 2L;
        message.date = 1_780_000_000;
        message.content = photo;

        assertEquals(List.of("t.me/durov/123"), TmeLink.in(message, 2));
    }
}
