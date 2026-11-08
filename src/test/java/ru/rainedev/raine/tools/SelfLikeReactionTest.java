package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Реакция на собственное сообщение — самолайк: собеседнику он не говорит ничего,
 * а выглядит как ответ самой себе. Живой случай: снимок чата берётся на шаге
 * открытия, сообщение человека приходит секундой позже — и самым свежим на
 * экране остаётся её же реплика, на которую она и ставит сердечко.
 */
class SelfLikeReactionTest {

    private static final long CHAT = 100L;
    private static final long HERS = 468713472L;
    private static final long THEIRS = 469762048L;

    private final List<String> reacted = new ArrayList<>();

    private final TelegramActions actions = (TelegramActions) java.lang.reflect.Proxy.newProxyInstance(
            TelegramActions.class.getClassLoader(), new Class<?>[] {TelegramActions.class},
            (proxy, method, args) -> {
                if (method.getName().equals("react")) {
                    reacted.add(args[1] + " " + args[2]);
                }
                return null;
            });

    private final TelegramTools tools =
            new TelegramTools(null, actions, null, new Lockdown(Lockdown.Mode.NONE, CHAT), null);

    private static TdApi.Message message(long id, boolean outgoing) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.isOutgoing = outgoing;
        message.senderId = new TdApi.MessageSenderUser(outgoing ? 42L : 7L);
        return message;
    }

    /** Сообщения подаются подстановкой: клиента Telegram в этой проверке нет. */
    private Tool react() {
        return tools.react(CHAT, id -> id == HERS
                ? Optional.of(message(HERS, true))
                : id == THEIRS ? Optional.of(message(THEIRS, false)) : Optional.<TdApi.Message>empty());
    }

    private static JsonNode args(long messageId, String emoji) {
        return new ObjectMapper().createObjectNode().put("message_id", messageId).put("emoji", emoji);
    }

    @Test
    void herOwnMessageIsRefusedBeforeTelegramIsAsked() {
        String result = react().call(args(HERS, "❤️"));

        assertTrue(result.toLowerCase().contains("your own message"),
                "своё сообщение — не повод для реакции: " + result);
        assertTrue(reacted.isEmpty(), "до Telegram дело дойти не должно: " + reacted);
    }

    @Test
    void somebodyElsesMessagePassesTheCheck() {
        // тулза на чужом сообщении уходит дальше — в Telegram, которого тут нет,
        // поэтому проверяется само решение, а не его обход
        assertEquals("", TelegramTools.ownMessageRefusal(message(THEIRS, false)),
                "чужая реплика — как раз то, на что реагируют");
        assertTrue(TelegramTools.ownMessageRefusal(message(HERS, true)).toLowerCase().contains("your own message"));
    }

    @Test
    void aMessageThatIsNotThereKeepsTheOldRefusal() {
        String result = react().call(args(999L, "❤️"));

        assertTrue(result.contains("There is no message with id 999"), result);
        assertTrue(reacted.isEmpty(), reacted.toString());
    }
}
