package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Безопасный режим держится на правах из набора инструментов, а не на том, что
 * слой действий сделает вид. Пока запись возвращает ноль как настоящий
 * идентификатор сообщения, ход не может отличить «не отправлено» от «отправлено»
 * и правит сообщение с номером ноль.
 */
class ReadOnlyActionsTest {

    private static final TelegramActions SAFE = new TelegramActions.ReadOnly();

    private static final Path PHOTO = Path.of("data/gallery/123.webp");
    private static final Path VOICE = Path.of("data/voice/123.ogg");

    private static Telegram.StickerRef sticker() {
        return new Telegram.StickerRef(7, "remote", "😀", 128, 128);
    }

    @Test
    void aWriteRefusesInsteadOfComingBackAsFakeSuccess() {
        List<String> refused = new ArrayList<>();
        for (Runnable write : List.<Runnable>of(
                () -> SAFE.sendMessage(1, "привет", null),
                () -> SAFE.sendPhoto(1, PHOTO, "подпись"),
                () -> SAFE.sendVoice(1, VOICE, 4),
                () -> SAFE.sendSticker(1, sticker(), null),
                () -> SAFE.saveSticker(sticker()),
                () -> SAFE.react(1, 7, "👍"),
                () -> SAFE.forward(2, 1, new long[] {7}),
                () -> SAFE.deleteMessage(1, 7),
                () -> SAFE.editText(1, 7, "новый текст", false),
                () -> SAFE.blockAndLeave(1),
                () -> SAFE.saveContact(9, "Ира", ""),
                () -> SAFE.postStory(1, PHOTO, "подпись", 86_400, StoryAudience.CONTACTS))) {
            RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class, write::run,
                    "запись должна отказываться, а не делать вид");
            refused.add(String.valueOf(failure.getMessage()));
        }

        assertTrue(refused.stream().allMatch(text -> text.contains("Режим только чтения")),
                "в журнале должно быть видно, чей это отказ, а не обрывок: " + refused);
    }

    @Test
    void readingStaysQuietBecauseThatIsWhatTheModeIsFor() {
        // эти вызовы достаются из инструментов, которые остаются в наборе:
        // «open» читает переписку, «watch_story» смотрит историю, а слух отмечает
        // прослушанное. Отказ здесь означал бы, что в безопасном режиме не видно ничего
        assertDoesNotThrow(() -> {
            SAFE.setOnline(true);
            SAFE.openChat(1);
            SAFE.closeChat(1);
            SAFE.markRead(1, new long[] {7});
            SAFE.markUnread(1);
            SAFE.openContent(1, 7);
            SAFE.openStory(1, 3);
            SAFE.closeStory(1, 3);
            SAFE.typing(1);
            SAFE.stopTyping(1);
            SAFE.recordingVoice(1);
            SAFE.uploadingPhoto(1);
        }, "чтение не должно наталкиваться на отказ");
    }
}
