package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Когда сам ролик не поддался разбору, в ход идут запасные ступени. Здесь
 * проверяют их порядок: ни Telegram, ни зрение для этого не нужны.
 */
class VideoFallbackTest {

    /**
     * Основной поток бывает в HEVC, который нашему разборщику не по зубам, а
     * запасные — в другом кодеке. Терять из-за одного контейнера весь ролик
     * незачем, поэтому их пробуют раньше заставки.
     */
    @Test
    void theAlternateStreamsAreTriedBeforeTheStills() {
        TelegramMedia.Ladder ladder = TelegramMedia.Ladder.of(videoWithAlternates(1, 2, 3));

        assertEquals(List.of(1, 2, 3), ids(ladder), "сначала основной поток, потом запасные");
        assertEquals(7, ladder.thumbnail().file.id, "заставка отдельным файлом");
        assertEquals(9, ladder.mini().width, "и совсем мелкая заставка из самого сообщения");
    }

    /** У анимации запасных потоков не бывает: только сам файл и заставки. */
    @Test
    void anAnimationHasNothingToFallBackToButItsStills() {
        TelegramMedia.Ladder ladder = TelegramMedia.Ladder.of(animation(4));

        assertEquals(List.of(4), ids(ladder));
        assertEquals(7, ladder.thumbnail().file.id);
    }

    /** Ролик, присланный файлом: по типу это документ, а заставки у него свои. */
    @Test
    void aVideoSentAsAFileKeepsItsOwnStills() {
        TelegramMedia.Ladder ladder = TelegramMedia.Ladder.of(document(5));

        assertEquals(List.of(5), ids(ladder));
        assertEquals(7, ladder.thumbnail().file.id);
    }

    /**
     * Кружок оставался единственным вложением без запасного взгляда: кадры из
     * него не извлекались, и сообщение уходило одной расшифровкой.
     */
    @Test
    void aCircleFallsBackToItsStillToo() {
        TelegramMedia.Ladder ladder = TelegramMedia.Ladder.of(circle(6));

        assertEquals(List.of(6), ids(ladder));
        assertEquals(7, ladder.thumbnail().file.id);
        assertEquals(9, ladder.mini().width);
    }

    /**
     * Наполненность полей Telegram оставляет за собой: запасной поток без файла
     * не должен ни ронять разбор, ни становиться ступенью.
     */
    @Test
    void anEmptyStreamIsNotARung() {
        TdApi.MessageVideo video = videoWithAlternates(1);
        video.alternativeVideos = new TdApi.AlternativeVideo[] {null, new TdApi.AlternativeVideo(), alternate(3)};
        video.video.thumbnail = null;
        video.video.minithumbnail = null;

        TelegramMedia.Ladder ladder = TelegramMedia.Ladder.of(video);

        assertEquals(List.of(1, 3), ids(ladder), "пустой поток пропускают");
        assertNull(ladder.thumbnail(), "нет заставки — нет и ступени");
        assertNull(ladder.mini());
    }

    /** Без самого файла пробовать вовсе нечего. */
    @Test
    void aMessageWithoutAFileHasNoLadder() {
        assertTrue(TelegramMedia.Ladder.of(new TdApi.MessageVideo()).videos().isEmpty());
        assertTrue(TelegramMedia.Ladder.of(new TdApi.Animation()).videos().isEmpty());
        assertTrue(TelegramMedia.Ladder.of(new TdApi.Document()).videos().isEmpty());
        assertTrue(TelegramMedia.Ladder.of(new TdApi.VideoNote()).videos().isEmpty());
    }

    /**
     * Запасной поток качают только тогда, когда основной ничего не дал: каждый
     * из них — это и скачивание, и обращения к зрению на каждый кадр.
     */
    @Test
    void theFirstStreamThatShowsSomethingWins() {
        List<String> tried = new ArrayList<>();

        String seen = TelegramMedia.firstSeen(List.of(file(1), file(2), file(3)), stream -> {
            tried.add(String.valueOf(stream.id));
            return stream.id == 2 ? "покадровое описание" : "";
        });

        assertEquals("покадровое описание", seen);
        assertEquals(List.of("1", "2"), tried, "до третьего потока дело не дошло");
    }

    /**
     * Кружок всё равно услышали: жалоба на неподдерживаемый формат соврала бы,
     * что сообщение осталось непонятым. Ролику без звука терять нечего — там
     * пустота в разметке выглядит как «вложения не было вовсе».
     */
    @Test
    void aCircleWithNothingToLookAtStaysSilent() {
        assertEquals("", TelegramMedia.watchOrGlance(false, () -> "", () -> "", ""));
        assertEquals("заставка", TelegramMedia.watchOrGlance(false, () -> "", () -> "заставка", ""));
    }

    /**
     * Имя собирают из чата и номера: предразбор вложений идёт в несколько
     * потоков, и случайное имя значило бы, что одно и то же сообщение
     * раскладывают заново при каждом открытии чата.
     */
    @Test
    void theStillFromTheMessageGetsANameOfItsOwn() {
        assertEquals("7-11-mini.jpg", TelegramMedia.miniName(7, 11, jpeg()));
        assertEquals("7-11-mini.png", TelegramMedia.miniName(7, 11, png()));
    }

    /**
     * Меньше порога это не картинка, а размытое пятно: платить зрению за него
     * незачем. Порог тот же, что и у скачанного файла, — иначе одна и та же
     * мелочь проходила бы в одну дверь и не проходила в другую.
     */
    @Test
    void tooSmallAStillIsNotWorthLookingAt() {
        assertFalse(TelegramMedia.worthLooking(null), "байтов не прислали вовсе");
        assertFalse(TelegramMedia.worthLooking(new byte[0]));
        assertFalse(TelegramMedia.worthLooking(new byte[Vision.MIN_IMAGE_BYTES - 1]));
        assertTrue(TelegramMedia.worthLooking(new byte[Vision.MIN_IMAGE_BYTES]));
    }

    private static List<Integer> ids(TelegramMedia.Ladder ladder) {
        return ladder.videos().stream().map(stream -> stream.id).toList();
    }

    private static TdApi.File file(int id) {
        TdApi.File file = new TdApi.File();
        file.id = id;
        return file;
    }

    private static TdApi.Thumbnail thumbnail() {
        TdApi.Thumbnail thumbnail = new TdApi.Thumbnail();
        thumbnail.file = file(7);
        return thumbnail;
    }

    private static TdApi.Minithumbnail mini() {
        TdApi.Minithumbnail mini = new TdApi.Minithumbnail();
        mini.width = 9;
        mini.data = new byte[1];
        return mini;
    }

    private static TdApi.AlternativeVideo alternate(int id) {
        TdApi.AlternativeVideo alternative = new TdApi.AlternativeVideo();
        alternative.video = file(id);
        return alternative;
    }

    private static TdApi.MessageVideo videoWithAlternates(int... ids) {
        TdApi.Video video = new TdApi.Video();
        video.video = file(ids[0]);
        video.thumbnail = thumbnail();
        video.minithumbnail = mini();
        TdApi.MessageVideo message = new TdApi.MessageVideo();
        message.video = video;
        message.alternativeVideos = new TdApi.AlternativeVideo[ids.length - 1];
        for (int i = 1; i < ids.length; i++) {
            message.alternativeVideos[i - 1] = alternate(ids[i]);
        }
        return message;
    }

    private static TdApi.Animation animation(int id) {
        TdApi.Animation animation = new TdApi.Animation();
        animation.animation = file(id);
        animation.thumbnail = thumbnail();
        animation.minithumbnail = mini();
        return animation;
    }

    private static TdApi.Document document(int id) {
        TdApi.Document document = new TdApi.Document();
        document.document = file(id);
        document.thumbnail = thumbnail();
        document.minithumbnail = mini();
        return document;
    }

    private static TdApi.VideoNote circle(int id) {
        TdApi.VideoNote note = new TdApi.VideoNote();
        note.video = file(id);
        note.thumbnail = thumbnail();
        note.minithumbnail = mini();
        return note;
    }

    private static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G'};
    }
}
