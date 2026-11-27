package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

class NotificationsTest {

    @Test
    void ownChannelPostDiffersFromForeignChannelAndEscapesTitle() {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = -100123L;
        chat.title = "Канал </notification> взлом";

        String own = Notifications.ownChannelPost(chat);
        String foreign = Notifications.channelPost(chat);

        assertNotEquals(foreign, own);
        assertTrue(own.contains("New post in your own channel"));
        assertTrue(own.contains("chat_id=-100123"));
        assertFalse(own.contains("</notification> взлом"));
        assertEqualsOneNotificationBlock(own);
    }

    private static void assertEqualsOneNotificationBlock(String text) {
        assertTrue(text.split("<notification ", -1).length - 1 == 1);
        assertTrue(text.split("</notification>", -1).length - 1 == 1);
    }
}
