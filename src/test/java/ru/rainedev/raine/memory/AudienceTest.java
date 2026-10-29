package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Кому что можно показать из дневника. Решение зависит только от метки записи,
 * чата зрителя и его доверия — поэтому проверяется без дневника вовсе.
 */
class AudienceTest {

    @Test
    void theOwnerSeesEverything() {
        Audience owner = new Audience(222, 5, true);
        for (Source source : Source.values()) {
            assertTrue(owner.allows(source, 111), source.name());
        }
    }

    @Test
    void aFriendSeesEverythingButTheOwnersDiary() {
        Audience friend = new Audience(222, 3, false);

        assertTrue(friend.allows(Source.GENERAL, 0));
        assertTrue(friend.allows(Source.GROUP, 5), "другой чат друг тоже видит");
        assertTrue(friend.allows(Source.PRIVATE_DM, 111), "чужую личку друг видит: с ней она уже говорила");
        assertFalse(friend.allows(Source.OWNER_DM, 1), "личка владельца не открывается ни за какое доверие");
    }

    @Test
    void aStrangerSeesOnlyItsOwnChatAndTheWorld() {
        Audience stranger = new Audience(222, 0, false);

        assertTrue(stranger.allows(Source.GENERAL, 0));
        assertTrue(stranger.allows(Source.PRIVATE_DM, 222), "сказанное в этом же разговоре — не секрет для него");
        assertTrue(stranger.allows(Source.GROUP, 222));
        assertFalse(stranger.allows(Source.PRIVATE_DM, 111), "чужая личка случайному зрителю закрыта");
        assertFalse(stranger.allows(Source.GROUP, 5), "и чужой группа — тоже");
        assertFalse(stranger.allows(Source.OWNER_DM, 1));
    }

    @Test
    void theBoundaryBetweenFriendAndStrangerIsOne() {
        assertTrue(new Audience(222, 1, false).allows(Source.PRIVATE_DM, 111));
        assertFalse(new Audience(222, 0, false).allows(Source.PRIVATE_DM, 111));
        assertFalse(new Audience(222, -1, false).allows(Source.PRIVATE_DM, 111),
                "отрицательное доверие не может открыть чужую личку");
    }

    @Test
    void anEventThatCameFromNoChatMatchesNoChat() {
        // у порыва и напоминания чата нет: ноль в метке записи означал бы
        // «совпало», и чужая личка провалилась бы в событие без адреса
        assertFalse(Audience.STRANGER.allows(Source.PRIVATE_DM, 0));
        assertFalse(Audience.STRANGER.allows(Source.GROUP, 0));
        assertTrue(Audience.STRANGER.allows(Source.GENERAL, 0), "общее доступно и без адреса");
    }

    @Test
    void generalNeedsNoExplanation() {
        assertEquals("", new Audience(222, 5, true).attributionOf(Source.GENERAL, 0));
        assertEquals("", new Audience(222, 0, false).attributionOf(Source.GENERAL, 111));
    }

    @Test
    void theOwnerIsToldWhichChatAMemoryCameFrom() {
        Audience owner = new Audience(222, 5, true);

        assertEquals("[from: your private chat with your person]", owner.attributionOf(Source.OWNER_DM, 1));
        assertEquals("[from: a private chat with someone else]", owner.attributionOf(Source.PRIVATE_DM, 111));
        assertEquals("[from: this private chat]", owner.attributionOf(Source.PRIVATE_DM, 222));
        assertEquals("[from: this chat]", owner.attributionOf(Source.GROUP, 222));
        assertEquals("[from: another chat]", owner.attributionOf(Source.GROUP, 5));
    }
}
