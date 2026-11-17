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
    private final StickerTools tools = new StickerTools(null,
            (TelegramActions) java.lang.reflect.Proxy.newProxyInstance(
                    TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
                    (proxy, method, args) -> null), null);

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
        AtomicInteger asked = new AtomicInteger();

        String out = StickerTools.describe(pack(10), (sticker, budget) -> {
            if (!budget.allows(false)) {
                return "";
            }
            asked.incrementAndGet();
            budget.spent("");          // зрение молчит: квота или ошибка
            spent.incrementAndGet();
            return "";
        });

        assertEquals(1, asked.get(), "после отказа в остальные файлы этого списка не стучатся");
        assertEquals(1, spent.get());
        assertFalse(out.contains("котик"), out);
        assertEquals(10, out.split("<sticker ", -1).length - 1, "стикеров в ответе меньше не стало");
    }

    /** Схема просит id текстом: числом он приезжает уже округлённым. */
    @Test
    void theIdIsDeclaredAsTextNotAsNumber() {
        for (Tool tool : List.of(tools.send(7L), tools.save())) {
            JsonNode schema = tool.asJson().path("function").path("parameters");
            assertEquals("string", schema.path("properties").path("sticker_id").path("type").asText(),
                    tool.name() + ": id должен приходить текстом");
        }
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

        // в 17:19:53 она прочитала старый «Sticker sent.» из более раннего шага
        // как итог и ушла в wait: отказ обязан называть отсутствие действия
        String refusal = tools.send(7L).call(rounded);
        assertTrue(refusal.startsWith("Sticker was NOT sent:"), "неотправленное начинается с этого: " + refusal);
        assertTrue(tools.save().call(rounded).contains("as text"), "сохранение тоже ничего не сохраняет");
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
