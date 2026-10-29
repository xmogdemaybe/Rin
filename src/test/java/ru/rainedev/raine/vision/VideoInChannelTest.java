package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class VideoInChannelTest {

    @Test
    void inChannelTheVideoItselfIsNotTouched() {
        // покадровый разбор с расшифровкой звука стоит там дороже, чем весь
        // остальной день переписки, а понять пост хватает и заставки
        AtomicBoolean watched = new AtomicBoolean();

        String described = TelegramMedia.watchOrGlance(true,
                () -> {
                    watched.set(true);
                    return "покадровое описание";
                },
                () -> "заставка");

        assertEquals("заставка", described);
        assertFalse(watched.get(), "к самому ролику в канале даже прикасаться незачем");
    }

    @Test
    void inConversationTheVideoIsWatchedWhole() {
        assertEquals("покадровое описание",
                TelegramMedia.watchOrGlance(false, () -> "покадровое описание", () -> "заставка"));
    }

    @Test
    void whenTheFormatIsUnknownTheThumbnailSaves() {
        assertEquals("заставка", TelegramMedia.watchOrGlance(false, () -> "", () -> "заставка"));
    }

    @Test
    void whenNothingWorkedItSaysSoInsteadOfStayingSilent() {
        // пустота в разметке выглядит так, будто вложения не было вовсе
        String described = TelegramMedia.watchOrGlance(false, () -> "", () -> "");

        assertTrue(described.contains("not supported"), described);
    }

    @Test
    void videoSentAsAFileIsRecognisedByItsName() {
        assertEquals(Vision.Kind.VIDEO, TelegramMedia.kindOf(document("Обзор.MP4")));
        assertEquals(Vision.Kind.VIDEO, TelegramMedia.kindOf(document("clip.webm")));
        assertEquals(Vision.Kind.PHOTO, TelegramMedia.kindOf(document("снимок.jpg")));
        assertEquals(Vision.Kind.STICKER, TelegramMedia.kindOf(document("отчёт.pdf")));
        assertEquals(Vision.Kind.STICKER, TelegramMedia.kindOf(document(null)));
    }

    @Test
    void aChannelIsGlancedWhileChannelVideosAreOff() {
        // правило читается из одного места: и когда историю кинули в канал, и
        // когда её открывают из ленты
        assertTrue(media(false).onlyGlanceIn(channel()), "в канале хватит заставки");
        assertFalse(media(true).onlyGlanceIn(channel()), "разрешено — значит смотрим целиком");
    }

    @Test
    void anyOtherChatIsWatchedWhole() {
        TelegramMedia media = media(false);

        assertFalse(media.onlyGlanceIn(privateChat()), "в переписке ролик — часть разговора");
        assertFalse(media.onlyGlanceIn(group()), "группа — не канал");
        assertFalse(media.onlyGlanceIn(null), "неизвестного чата как канала считать нельзя");
    }

    @Test
    void aGlanceThatSawNothingSaysSoWithoutTouchingTheVideo() {
        // заставки может и не быть: молчание тогда выглядит как «вложения нет»,
        // а лезть за кадром в канал ровно тогда, когда его запретили, глупо
        AtomicBoolean watched = new AtomicBoolean();

        String described = TelegramMedia.watchOrGlance(true,
                () -> {
                    watched.set(true);
                    return "покадровое описание";
                },
                () -> "");

        assertTrue(described.contains("not supported"), described);
        assertFalse(watched.get(), "сам ролик так и не тронут");
    }

    @Test
    void aStoryVideoInAChannelStopsAtTheThumbnail() {
        // история из канала приходит потоком: покадрово разбирать каждую —
        // платить зрению за каждый кадр каждого поста
        AtomicBoolean watched = new AtomicBoolean();

        String seen = TelegramMedia.storyVideo(videoStory(), true,
                (file, withSound) -> {
                    watched.set(true);
                    return "покадровое описание";
                },
                () -> "заставка");

        assertEquals("заставка", seen);
        assertFalse(watched.get(), "к самому ролику даже прикасаться незачем");
    }

    @Test
    void aStoryVideoInAChatIsWatchedWhole() {
        assertEquals("покадровое описание",
                TelegramMedia.storyVideo(videoStory(), false,
                        (file, withSound) -> "покадровое описание", () -> "заставка"));
    }

    /**
     * Заставку истории помечают как заставку: по одному кадру о всём ролике не
     * рассказывают, а без пометки модель именно это и сделает.
     */
    @Test
    void aStoryStillIsLookedAtAsASingleFrame() {
        List<Integer> seen = new ArrayList<>();
        List<Boolean> marked = new ArrayList<>();

        String described = TelegramMedia.glanceOf(stillStory(), (file, isStill) -> {
            file.ifPresent(id -> seen.add(id));
            marked.add(isStill);
            return "заставка";
        });

        assertEquals("заставка", described);
        assertEquals(List.of(7), seen, "кадр берут из заставки ролика");
        assertEquals(List.of(true), marked, "взгляд на заставку помечен как один кадр, а не как просмотр");
    }

    /** Без самого ролика заставки нет и смотреть нечего. */
    @Test
    void aStoryWithoutAVideoIsNotGlancedAt() {
        List<Boolean> marked = new ArrayList<>();

        assertEquals("", TelegramMedia.glanceOf(new TdApi.StoryContentVideo(), (file, isStill) -> {
            marked.add(isStill);
            return "заставка";
        }));
        assertTrue(marked.isEmpty(), "без файла незачем обращаться и к зрению");
    }

    /** Зрение и Telegram здесь не нужны: решает только тип чата да тумблер. */
    private static TelegramMedia media(boolean videoInChannels) {
        TelegramMedia media = new TelegramMedia(null, null, () -> List.of());
        media.videoInChannels(videoInChannels);
        return media;
    }

    private static TdApi.Chat chat(TdApi.ChatType type) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = 1L;
        chat.type = type;
        return chat;
    }

    private static TdApi.Chat channel() {
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.isChannel = true;
        return chat(type);
    }

    private static TdApi.Chat group() {
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.isChannel = false;
        return chat(type);
    }

    private static TdApi.Chat privateChat() {
        return chat(new TdApi.ChatTypePrivate());
    }

    private static TdApi.MessageDocument document(String fileName) {
        TdApi.MessageDocument message = new TdApi.MessageDocument();
        message.document = new TdApi.Document();
        message.document.fileName = fileName;
        return message;
    }

    /** Ролик истории: сам файл есть, а зрения и Telegram рядом не требуется. */
    private static TdApi.StoryContentVideo videoStory() {
        TdApi.File file = new TdApi.File();
        file.id = 9;
        TdApi.StoryVideo video = new TdApi.StoryVideo();
        video.video = file;
        TdApi.StoryContentVideo content = new TdApi.StoryContentVideo();
        content.video = video;
        return content;
    }

    /** Тот же ролик, но с заставкой, присланной отдельным файлом. */
    private static TdApi.StoryContentVideo stillStory() {
        TdApi.StoryContentVideo content = videoStory();
        TdApi.File still = new TdApi.File();
        still.id = 7;
        TdApi.Thumbnail thumbnail = new TdApi.Thumbnail();
        thumbnail.file = still;
        content.video.thumbnail = thumbnail;
        return content;
    }
}
