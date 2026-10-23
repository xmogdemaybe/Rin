package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Лента историй собирается из того, что Telegram присылает сам: запроса
 * «покажи всю ленту» у него нет. Значит, список живёт у нас, и ошибиться здесь —
 * значит либо показывать протухшее, либо звать смотреть то, что она уже смотрела.
 */
class StoriesTest {

    private static TdApi.StoryInfo story(int storyId, int date) {
        TdApi.StoryInfo info = new TdApi.StoryInfo();
        info.storyId = storyId;
        info.date = date;
        return info;
    }

    private static TdApi.ChatActiveStories main(long chatId, int maxRead, TdApi.StoryInfo... stories) {
        TdApi.ChatActiveStories active = new TdApi.ChatActiveStories();
        active.chatId = chatId;
        active.list = new TdApi.StoryListMain();
        active.maxReadStoryId = maxRead;
        active.stories = stories;
        return active;
    }

    private static TdApi.ChatActiveStories archived(long chatId, TdApi.StoryInfo... stories) {
        TdApi.ChatActiveStories active = main(chatId, 0, stories);
        active.list = new TdApi.StoryListArchive();
        return active;
    }

    @Test
    void beforeTheFirstUpdateThereIsNothingToWatch() {
        assertTrue(new Stories().feed().isEmpty());
    }

    @Test
    void anEmptyUpdateChangesNothing() {
        Stories feed = new Stories();
        feed.onChanged(null);
        assertTrue(feed.feed().isEmpty());
    }

    @Test
    void unseenAreOnlyThoseNewerThanTheLastReadOne() {
        // 3 и 5 уже смотрела, 7 и 9 — нет: ровно то, за что в клиенте висит кружок
        Stories.Entry entry = Stories.entryOf(main(11L, 5, story(3, 100), story(5, 200),
                story(7, 300), story(9, 400))).orElseThrow();

        assertEquals(4, entry.total());
        assertEquals(2, entry.unseen());
        assertEquals(7, entry.first());
    }

    @Test
    void theFirstUnseenIsTheOldestOfThem() {
        // Telegram присылает истории не по порядку — начинать нужно с самой ранней,
        // иначе она пропустит середину и ответит невпопад
        Stories.Entry entry = Stories.entryOf(main(12L, 5, story(9, 400), story(6, 300), story(8, 350)))
                .orElseThrow();

        assertEquals(6, entry.first());
        assertEquals(3, entry.unseen());
    }

    @Test
    void aChatSheHasAlreadyReadOffersNothingToStartWith() {
        Stories.Entry entry = Stories.entryOf(main(13L, 9, story(7, 300), story(9, 400))).orElseThrow();

        assertEquals(0, entry.unseen());
        assertEquals(0, entry.first(), "ноль — значит начинать не с чего");
    }

    @Test
    void aHoleInTheListDoesNotBreakTheRest() {
        Stories.Entry entry = Stories.entryOf(main(14L, 0, story(2, 100), null, story(1, 50))).orElseThrow();

        assertEquals(2, entry.total(), "в списке и правда две истории, а не три");
        assertEquals(1, entry.first());
    }

    @Test
    void aChatThatRanOutOfStoriesLeavesTheFeed() {
        Stories feed = new Stories();
        feed.onChanged(main(15L, 0, story(1, 50)));
        assertEquals(1, feed.feed().size());

        feed.onChanged(main(15L, 0));
        assertTrue(feed.feed().isEmpty(), "истории кончились — в ленте ему делать нечего");
    }

    @Test
    void archivedStoriesAreNotFreshOnes() {
        Stories feed = new Stories();
        feed.onChanged(archived(16L, story(1, 50)));

        assertTrue(feed.feed().isEmpty(), "архив — это воспоминания, а не лента");
    }

    @Test
    void theFeedStartsWithWhoeverPostedLatest() {
        Stories feed = new Stories();
        feed.onChanged(main(21L, 0, story(1, 100)));
        feed.onChanged(main(22L, 0, story(1, 900)));
        feed.onChanged(main(23L, 0, story(1, 500)));

        List<Long> order = feed.feed().stream().map(Stories.Entry::chatId).toList();
        assertEquals(List.of(22L, 23L, 21L), order);
    }

    @Test
    void anUpdateReplacesThePreviousStateOfTheSameChat() {
        Stories feed = new Stories();
        feed.onChanged(main(24L, 0, story(1, 100)));
        feed.onChanged(main(24L, 1, story(1, 100), story(2, 200)));

        List<Stories.Entry> entries = feed.feed();
        assertEquals(1, entries.size(), "один чат — одна строка ленты");
        assertEquals(1, entries.getFirst().unseen());
    }
}
