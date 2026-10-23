package ru.rainedev.raine.telegram;

import it.tdlight.jni.TdApi;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Лента историй: кто сейчас что выложил.
 * <p>
 * Запроса «покажи всю ленту» у Telegram нет: он сам присылает список активных
 * историй каждого чата, и держать его приходится у себя. Держим в памяти —
 * истории протухают за сутки-двое, помнить их дольше нечего.
 */
public final class Stories {

    /**
     * Один чат в ленте.
     *
     * @param total  сколько историй у него сейчас живо
     * @param unseen сколько из них она ещё не смотрела
     * @param first  номер первой непросмотренной: смотреть начинают с неё
     */
    public record Entry(long chatId, int total, int unseen, int first) {}

    private final Map<Long, TdApi.ChatActiveStories> active = new ConcurrentHashMap<>();

    /**
     * Обновление из Telegram: список историй чата изменился.
     * <p>
     * Пустой список — истории кончились или ушли в архив, и в ленте их больше нет.
     */
    public void onChanged(TdApi.ChatActiveStories stories) {
        if (stories == null) {
            return;
        }
        if (stories.stories == null || stories.stories.length == 0 || !inFeed(stories.list)) {
            active.remove(stories.chatId);
            return;
        }
        active.put(stories.chatId, stories);
    }

    /**
     * Лента от свежих к старым: выше тот, кто выложил историю позже.
     * Смотрят на дату самой истории, а не на служебную метку порядка Telegram —
     * та нужна его списку чатов, а здесь важнее «кто выложил только что».
     */
    public List<Entry> feed() {
        Comparator<TdApi.ChatActiveStories> newestFirst = Comparator.comparingInt(Stories::newest).reversed()
                .thenComparingLong(stories -> stories.chatId);
        return active.values().stream()
                .filter(stories -> inFeed(stories.list))
                .sorted(newestFirst)
                .map(Stories::entryOf)
                .flatMap(Optional::stream)
                .toList();
    }

    /** Дата самой поздней истории чата. */
    private static int newest(TdApi.ChatActiveStories stories) {
        int newest = 0;
        for (TdApi.StoryInfo info : stories.stories) {
            if (info != null) {
                newest = Math.max(newest, info.date);
            }
        }
        return newest;
    }

    /** В архивной ленте истории тоже живут, но их не показывают как свежие. */
    private static boolean inFeed(TdApi.StoryList list) {
        return list instanceof TdApi.StoryListMain;
    }

    /**
     * Непросмотренными считаются истории новее последней прочитанной — ровно так
     * их считает сам Telegram, и кружок на аватарке висит по тому же правилу.
     */
    static Optional<Entry> entryOf(TdApi.ChatActiveStories stories) {
        if (stories.stories == null || stories.stories.length == 0) {
            return Optional.empty();
        }
        int total = 0;
        int unseen = 0;
        int first = 0;
        for (TdApi.StoryInfo info : stories.stories) {
            if (info == null) {
                continue;
            }
            total++;
            if (info.storyId <= stories.maxReadStoryId) {
                continue;
            }
            unseen++;
            first = unseen == 1 ? info.storyId : Math.min(first, info.storyId);
        }
        return total == 0 ? Optional.empty() : Optional.of(new Entry(stories.chatId, total, unseen, first));
    }
}
