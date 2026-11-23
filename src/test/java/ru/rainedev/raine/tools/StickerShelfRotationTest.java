package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.vision.TelegramMedia;

/**
 * Полка не повторяет недавно отправленное и двигает оба окна независимо.
 * Проверки идут по чистой сборке кандидатов и по состоянию после отправки —
 * без TDLib и без зрения.
 */
class StickerShelfRotationTest {

    private static TdApi.Sticker sticker(long id) {
        TdApi.Sticker sticker = new TdApi.Sticker();
        sticker.id = id;
        sticker.emoji = "🙂";
        return sticker;
    }

    private static List<TdApi.Sticker> stickers(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(id -> sticker(id * 10L)).toList();
    }

    private static void seed(StickerTools tools, String field, Object value) throws Exception {
        Field target = StickerTools.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(tools, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, String> descriptions(StickerTools tools) throws Exception {
        Field target = StickerTools.class.getDeclaredField("descriptions");
        target.setAccessible(true);
        return (Map<Long, String>) target.get(tools);
    }

    private static List<TdApi.Sticker> saved(StickerTools tools) throws Exception {
        Field target = StickerTools.class.getDeclaredField("saved");
        target.setAccessible(true);
        return (List<TdApi.Sticker>) target.get(tools);
    }

    private static Telegram telegram() throws Exception {
        var constructor = Telegram.class.getDeclaredConstructor(
                SimpleTelegramClient.class, String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, "Rin", 1L);
    }

    @Test
    void aSentStickerLeavesTheShelfWithoutTdlib() throws Exception {
        StickerTools tools = new StickerTools(null, null, null);
        seed(tools, "saved", stickers(1, 12));

        tools.noteSent(10);

        assertFalse(tools.favourites().contains("sticker_id=\"10\""),
                "отправленный стикер с полки ушёл: " + tools.favourites());
        assertTrue(tools.favourites().contains("sticker_id=\"20\""),
                "соседний стикер остался: " + tools.favourites());
    }

    @Test
    void theHeadWindowMovesPastTheFirstTen() {
        List<TdApi.Sticker> saved = stickers(1, 25);

        List<TdApi.Sticker> candidates = StickerTools.shelfCandidates(
                saved, List.of(), List.of(), StickerTools.SHELF_HEAD, 0);

        assertEquals(110L, candidates.getFirst().id,
                "окно головы начинается со сдвига, а не с первого стикера");
        assertEquals(25, candidates.size(), "остаток своих не теряется");
    }

    @Test
    void aSentIdDoesNotComeBackThroughTheTailOfASmallShelf() {
        List<TdApi.Sticker> saved = stickers(1, 12);
        List<Long> recent = List.of(10L);

        List<TdApi.Sticker> candidates = StickerTools.shelfCandidates(
                saved, List.of(), recent, StickerTools.SHELF_HEAD, 0);

        assertTrue(candidates.stream().noneMatch(sticker -> sticker.id == 10L),
                "недавно отправленный не возвращается хвостом: ids="
                        + candidates.stream().map(sticker -> sticker.id).toList());
    }

    @Test
    void everythingRecentlySentFallsBackToTheWholeShelf() {
        List<TdApi.Sticker> saved = stickers(1, 3);

        List<TdApi.Sticker> candidates = StickerTools.shelfCandidates(
                saved, List.of(), List.of(10L, 20L, 30L), 0, 0);

        assertEquals(3, candidates.size(), "пустая полка хуже повтора: ids="
                + candidates.stream().map(sticker -> sticker.id).toList());
    }

    @Test
    void packRotationContinuesFromTheOffset() {
        List<TdApi.Sticker> pool = stickers(1, 30);

        List<TdApi.Sticker> candidates = StickerTools.shelfCandidates(
                List.of(), pool, List.of(), 0, 1);

        assertEquals(StickerTools.ROTATION, candidates.size());
        assertEquals(20L, candidates.getFirst().id,
                "следующая сборка берёт следующую восьмёрку: ids="
                        + candidates.stream().map(sticker -> sticker.id).toList());
    }

    @Test
    void theShelfSaysHowManyMoreAreLeft() {
        List<TdApi.Sticker> saved = stickers(1, 25);
        List<TdApi.Sticker> candidates = StickerTools.shelfCandidates(
                saved, List.of(), List.of(), 0, 0);

        String block = StickerTools.favouritesBlock(candidates, StickerTools.FAVOURITES, sticker -> "");

        assertEquals(StickerTools.FAVOURITES, block.split("sticker_id=").length - 1);
        assertTrue(block.contains("7 more are yours too"),
                "оставшиеся считаются по полному списку кандидатов: " + block);
    }

    @Test
    void aLaterShelfRebuildKeepsTheSentList() throws Exception {
        StickerTools tools = new StickerTools(null, null, null);
        seed(tools, "saved", stickers(1, 3));

        tools.noteSent(10);
        tools.noteSent(20);

        assertFalse(tools.favourites().contains("sticker_id=\"10\""), tools.favourites());
        assertFalse(tools.favourites().contains("sticker_id=\"20\""), tools.favourites());
        assertTrue(tools.favourites().contains("sticker_id=\"30\""), tools.favourites());
    }

    @Test
    void anOlderSavedSnapshotCannotOverwriteANewerOne() throws Exception {
        StickerTools tools = new StickerTools(null, null, null);

        tools.install(Map.of(), stickers(10, 3), List.of(), "", false, 2, 0);
        tools.install(Map.of(), stickers(1, 3), List.of(), "", false, 1, 0);

        assertEquals(List.of(100L, 110L, 120L),
                saved(tools).stream().map(sticker -> sticker.id).toList(),
                "свежий список своих стикеров не затирается старым build");
    }

    @Test
    void refreshKeepsPoolDescriptions() throws Exception {
        StickerTools tools = new StickerTools(telegram(), null, null);
        tools.media(new TelegramMedia(null, null, () -> List.of()));
        seed(tools, "pool", List.of(sticker(1)));
        seed(tools, "descriptions", Map.of(1L, "pool words"));

        tools.refresh();

        assertEquals("pool words", descriptions(tools).get(1L),
                "refresh без паков не теряет описания стикеров из pool");
    }

    @Test
    void reshelveReplacesAPreviouslyEmptyPoolDescription() throws Exception {
        StickerTools tools = new StickerTools(telegram(), null, null);
        tools.media(new TelegramMedia(null, null, () -> List.of()));
        TdApi.Sticker poolSticker = sticker(1);
        seed(tools, "descriptions", Map.of(1L, ""));

        tools.install(Map.of(1L, "fresh words"), List.of(), List.of(poolSticker), "", true, 1, 1);

        assertEquals("fresh words", descriptions(tools).get(1L),
                "пустое описание стикера из pool заменяется свежим при пересборке паков");
    }
}
