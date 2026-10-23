package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

/**
 * Номер истории приходит от модели и вполне может оказаться длиннее, чем
 * Telegram умеет хранить. Обрезанный до короткого, он разворачивается в другой
 * номер — и просмотр засчитывается на чужой истории, которую автор видеть не
 * должна.
 */
class StoryIdArgumentTest {

    private static JsonNode with(String value) {
        return JsonNodeFactory.instance.objectNode().put("story_id", value);
    }

    @Test
    void anOrdinaryNumberIsRead() {
        assertEquals(12, TelegramTools.storyIdAt(with("12")));
        assertEquals(1, TelegramTools.storyIdAt(with("1")));
        assertEquals(Integer.MAX_VALUE, TelegramTools.storyIdAt(with("2147483647")));
    }

    @Test
    void aNumberTooLongIsRefused() {
        // 4294967300 обрезался бы в четвёрку: это совсем другая история,
        // и её автор увидел бы чужой просмотр
        assertEquals(0, TelegramTools.storyIdAt(with("4294967300")));
        assertEquals(0, TelegramTools.storyIdAt(with("2147483648")));
        assertEquals(0, TelegramTools.storyIdAt(
                JsonNodeFactory.instance.objectNode().put("story_id", 9999999999L)));
    }

    @Test
    void aNumberThatIsNotOneIsRefused() {
        assertEquals(0, TelegramTools.storyIdAt(with("0")));
        assertEquals(0, TelegramTools.storyIdAt(with("-3")));
        assertEquals(0, TelegramTools.storyIdAt(with("не число")));
    }

    @Test
    void aNumberGivenAsDigitsIsReadToo() {
        JsonNode arguments = JsonNodeFactory.instance.objectNode().put("story_id", 15L);

        assertEquals(15, TelegramTools.storyIdAt(arguments));
    }
}
