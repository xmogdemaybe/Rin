package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Подсказка в описании реакции. Вечер 17 ноября: на «Хааа бебебе» она дважды
 * звала 😛 и 😜, которых в наборе Telegram нет, платила за это отказными
 * шагами и в итоге поставила 🤪 — а текст, который собиралась написать, так и
 * не написал: к четвёртому шагу ход уже казался закрытым.
 */
class ReactionHintTest {

    private static TelegramTools toolsWithoutClient() {
        TelegramActions none = (TelegramActions) Proxy.newProxyInstance(
                TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
                (proxy, method, args) -> null);
        return new TelegramTools(null, none, null,
                new Lockdown(Lockdown.Mode.OWNER_ONLY, 1L, true), null);
    }

    @Test
    void theEmojiParameterNamesTheUnsupportedFaces() {
        String described = toolsWithoutClient().react(7L).asJson().path("function").path("parameters")
                .path("properties").path("emoji").path("description").asText();

        assertTrue(described.contains("😛") && described.contains("😜") && described.contains("not supported"),
                "неподдерживаемые гримасы надо называть прямо: " + described);
        assertTrue(described.contains("🤪") && described.contains("😁"),
                "и чем их заменить: " + described);
    }

    @Test
    void theAllowedListIsStillInTheDescription() {
        String described = toolsWithoutClient().react(7L).description();

        assertTrue(described.contains("Allowed:"), "полный набор по-прежнему виден: " + described);
        assertTrue(described.contains("❤"), described);
    }
}
