package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.vision.Glimpses;

/**
 * Список стикеров и его цена: сколько файлов за один вызов смотрятся зрением и
 * что происходит, когда зрение молчит.
 */
class StickerListBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static TdApi.Sticker sticker(long id) {
        TdApi.Sticker sticker = new TdApi.Sticker();
        sticker.id = id;
        sticker.emoji = ":)";
        return sticker;
    }

    private static List<TdApi.Sticker> pack(int size) {
        return java.util.stream.LongStream.rangeClosed(1, size).map(id -> 1000L + id).boxed()
                .map(StickerListBudgetTest::sticker).toList();
    }

    /** Как ведёт себя настоящий смотритель: спросил бюджет, потратил, ответил. */
    private static String look(TdApi.Sticker sticker, Glimpses budget, AtomicInteger spent, boolean refuses) {
        if (!budget.allows(false)) {
            return "";
        }
        String text = refuses ? "" : ("котик " + sticker.id);
        budget.spent(text);
        if (!refuses) {
            spent.incrementAndGet();
        }
        return text;
    }

    @Test
    void theListLooksFreshAtSixStickersOnly() {
        AtomicInteger spent = new AtomicInteger();

        String out = StickerTools.describe(pack(34), (sticker, budget) -> look(sticker, budget, spent, false));

        assertEquals(6, spent.get(), "остальные дождутся следующего списка или кэша");
        assertTrue(out.contains("котик 1001"), out.substring(0, 200));
        assertTrue(out.contains(StickerTools.UNSEEN), "без описания в списке остаётся эмодзи и пометка");
        assertEquals(34, out.split("<sticker ", -1).length - 1, "стикеров в ответе всё равно тридцать четыре");
    }

    @Test
    void theFirstRefusalClosesTheRestOfTheList() {
        AtomicInteger spent = new AtomicInteger();

        String out = StickerTools.describe(pack(10), (sticker, budget) -> look(sticker, budget, spent, true));

        assertEquals(0, spent.get(), "после отказа зрение больше не дёргают");
        assertFalse(out.contains("котик"), out);
        assertEquals(10, out.split("<sticker ", -1).length - 1);
    }

    @Test
    void idsAreAskedAsTextBecauseNumbersLoseDigits() {
        TelegramActions never = (TelegramActions) java.lang.reflect.Proxy.newProxyInstance(
                TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
                (proxy, method, args) -> {
                    throw new AssertionError("за округлённым id идти в Telegram некуда: " + method.getName());
                });
        StickerTools tools = new StickerTools(null, never, null);
        tools.allowedTo(chatId -> true);

        JsonNode rounded = parse("{\"sticker_id\":5.4117508770590394e+17}");
        assertTrue(tools.send(7L).call(rounded).contains("as text"), "просим прислать id как напечатан");
        assertTrue(tools.save().call(rounded).contains("as text"));

        assertTrue(tools.send(7L).call(parse("{}")).contains("as text"), "пустой id — тот же ответ");
    }

    @Test
    void theShelfIsOfferedBeforeTheList() {
        TelegramActions none = (TelegramActions) java.lang.reflect.Proxy.newProxyInstance(
                TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
                (proxy, method, args) -> null);
        StickerTools tools = new StickerTools(null, none, null);

        String send = tools.send(7L).description();
        String list = tools.list().description();

        assertTrue(send.contains("<favourite_stickers>"), send);
        assertFalse(send.contains("Call sticker_list first"), "полка уже перед глазами, список — запасной");
        assertTrue(list.contains("only when the shelf has nothing that fits"), list);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
