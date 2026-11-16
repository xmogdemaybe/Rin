package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

/**
 * Кто считается подписанным на канал. Администратор и создатель — тоже
 * участники: иначе свой канал с админкой выглядел бы чужим, и его посты
 * отсекались бы фильтром уведомлений.
 */
class ChannelStatusTest {

    @Test
    void memberAdministratorAndCreatorAreSubscribed() {
        assertTrue(Telegram.isMemberStatus(new TdApi.ChatMemberStatusMember()));
        assertTrue(Telegram.isMemberStatus(new TdApi.ChatMemberStatusAdministrator()));
        assertTrue(Telegram.isMemberStatus(new TdApi.ChatMemberStatusCreator()));
    }

    @Test
    void restrictedCountsOnlyWhenStillAMember() {
        TdApi.ChatMemberStatusRestricted inside = new TdApi.ChatMemberStatusRestricted();
        inside.isMember = true;
        TdApi.ChatMemberStatusRestricted outside = new TdApi.ChatMemberStatusRestricted();
        outside.isMember = false;

        assertTrue(Telegram.isMemberStatus(inside));
        assertFalse(Telegram.isMemberStatus(outside));
    }

    @Test
    void leftBannedAndUnknownAreNotSubscribed() {
        assertFalse(Telegram.isMemberStatus(new TdApi.ChatMemberStatusLeft()));
        assertFalse(Telegram.isMemberStatus(new TdApi.ChatMemberStatusBanned()));
        assertFalse(Telegram.isMemberStatus(null));
    }
}