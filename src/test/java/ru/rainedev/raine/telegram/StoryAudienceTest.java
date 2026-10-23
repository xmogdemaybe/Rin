package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

/**
 * Кому видна её собственная история. Решает владелец одной строкой в настройках,
 * но ошибиться в этой строке безопаснее в сторону тишины, чем в сторону всей сети.
 */
class StoryAudienceTest {

    @Test
    void theKnownWordsAreUnderstood() {
        assertEquals(StoryAudience.CONTACTS, StoryAudience.of("contacts"));
        assertEquals(StoryAudience.EVERYONE, StoryAudience.of("everyone"));
        assertEquals(StoryAudience.CLOSE_FRIENDS, StoryAudience.of("close_friends"));
    }

    @Test
    void caseAndSpacesDoNotMatter() {
        assertEquals(StoryAudience.EVERYONE, StoryAudience.of(" Everyone "));
        assertEquals(StoryAudience.CONTACTS, StoryAudience.of("CONTACTS_ONLY"));
        assertEquals(StoryAudience.CLOSE_FRIENDS, StoryAudience.of("friends"));
    }

    @Test
    void anUnknownWordNarrowsTheAudienceInsteadOfWideningIt() {
        // «world» вместо «everyone» не должен оборачиваться снимком для всего Telegram
        assertEquals(StoryAudience.CONTACTS, StoryAudience.of("world"));
        assertEquals(StoryAudience.CONTACTS, StoryAudience.of(""));
        assertEquals(StoryAudience.CONTACTS, StoryAudience.of(null));
    }

    @Test
    void everyAudienceAsksForItsOwnSettings() {
        assertTrue(StoryAudience.EVERYONE.request() instanceof TdApi.StoryPrivacySettingsEveryone);
        assertTrue(StoryAudience.CONTACTS.request() instanceof TdApi.StoryPrivacySettingsContacts);
        assertTrue(StoryAudience.CLOSE_FRIENDS.request() instanceof TdApi.StoryPrivacySettingsCloseFriends);
    }

    @Test
    void nobodyIsExcludedFromTheStory() {
        // круг задаёт сам выбор аудитории; личного списка «кроме» у неё нет,
        // и вырезать кого-то в обход настройки она не вправе
        TdApi.StoryPrivacySettingsEveryone everyone =
                (TdApi.StoryPrivacySettingsEveryone) StoryAudience.EVERYONE.request();
        TdApi.StoryPrivacySettingsContacts contacts =
                (TdApi.StoryPrivacySettingsContacts) StoryAudience.CONTACTS.request();

        assertEquals(0, everyone.exceptUserIds.length);
        assertEquals(0, contacts.exceptUserIds.length);
    }

    @Test
    void theChoiceReachesTheWire() throws Exception {
        // тумблер, который не доходит до запроса, выглядит работающим и молча
        // показывает историю не тем людям; поэтому проверяем байты, а не имена классов
        java.util.Set<String> different = new java.util.HashSet<>();
        for (StoryAudience audience : StoryAudience.values()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            audience.request().serialize(new java.io.DataOutputStream(out));
            different.add(java.util.Arrays.toString(out.toByteArray()));
        }

        assertEquals(StoryAudience.values().length, different.size(), "аудитории слиплись в один запрос");
    }

    @Test
    void theWordingTellsHerExactlyWhoWillSeeIt() {
        for (StoryAudience audience : StoryAudience.values()) {
            assertFalse(audience.described().isBlank(), "модели нечего сказать о том, кто увидит историю");
        }
        assertTrue(StoryAudience.CONTACTS.described().contains("contacts"));
        assertTrue(StoryAudience.EVERYONE.described().contains("anyone"));
        assertNotEquals(StoryAudience.CONTACTS.described(), StoryAudience.EVERYONE.described());
    }
}
