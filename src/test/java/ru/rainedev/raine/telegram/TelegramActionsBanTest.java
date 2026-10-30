package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.rainedev.raine.telegram.TelegramActions.Live;

/**
 * Блокировка — единственный жест, который бот не умеет отменять: разблокировки
 * в нём нет. Поэтому цель закрепления здесь — чтобы в блок-лист попадал именно
 * тот, кого закрывают. В личке это человек: с идентификатором чата Telegram
 * отвечает «ок», а собеседник продолжает писать.
 */
class TelegramActionsBanTest {

    private static final long CHAT = 555L;
    private static final long PEER = 777L;

    private static TdApi.Chat chat(TdApi.ChatType type) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = CHAT;
        chat.type = type;
        return chat;
    }

    @Test
    void privateChatBlocksThePersonBehindIt() {
        TdApi.MessageSender target = Live.blockTarget(CHAT, chat(new TdApi.ChatTypePrivate(PEER)));

        assertEquals(PEER, assertInstanceOf(TdApi.MessageSenderUser.class, target).userId,
                "в приватном чате блокируют собеседника, а не номер чата");
    }

    @Test
    void privateChatIsNotLeft() {
        // уходить из переписки один на один некуда: остаётся только блок
        assertFalse(Live.shouldLeaveChat(chat(new TdApi.ChatTypePrivate(PEER))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"group", "channel", "supergroup"})
    void sharedChatBlocksItselfAndIsLeft(String kind) {
        TdApi.MessageSender target = Live.blockTarget(CHAT, chat(typeOf(kind)));

        assertEquals(CHAT, assertInstanceOf(TdApi.MessageSenderChat.class, target).chatId,
                "общий чат закрывается по своему идентификатору");
        assertTrue(Live.shouldLeaveChat(chat(typeOf(kind))), "из группы и канала выходят");
    }

    private static TdApi.ChatType typeOf(String kind) {
        return switch (kind) {
            case "group" -> new TdApi.ChatTypeBasicGroup();
            case "channel" -> new TdApi.ChatTypeSupergroup(123L, true);
            default -> new TdApi.ChatTypeSupergroup(123L, false);
        };
    }
}
