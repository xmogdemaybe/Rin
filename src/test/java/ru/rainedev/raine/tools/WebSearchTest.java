package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class WebSearchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fivePagesDoNotOccupyTheWholeContext() {
        // эндпоинт отвечает за то, что вернёт, а простыня в контексте стоит ей
        // всего разговора: переполнение сбрасывает ход в дневник
        ArrayNode results = MAPPER.createArrayNode();
        for (int i = 0; i < WebSearch.MAX_RESULTS + 5; i++) {
            ObjectNode result = results.addObject();
            result.put("title", "Сайт " + i);
            result.put("url", "https://example.com/" + i);
            result.put("content", "воздух ".repeat(1000) + "и вот чем кончается страница");
        }

        String rendered = WebSearch.render(results, "погода в Сочи");

        assertEquals(WebSearch.MAX_RESULTS, rendered.split("<web_search_result").length - 1,
                "просила пять — столько и уходит, сколько бы ни вернул эндпоинт");
        assertFalse(rendered.contains("и вот чем кончается"), "хвост чужой страницы в контекст не едет");
        assertTrue(rendered.length() <= WebSearch.MAX_RESULTS * (WebSearch.MAX_SNIPPET + 120L),
                "размер " + rendered.length());
    }

    @Test
    void aShortAnswerIsPassedThroughWhole() {
        ArrayNode results = MAPPER.createArrayNode();
        ObjectNode result = results.addObject();
        result.put("title", "Прогноз");
        result.put("url", "https://example.com/1");
        result.put("content", "завтра плюс двенадцать, без осадков");

        String rendered = WebSearch.render(results, "погода в Сочи");

        assertTrue(rendered.contains("завтра плюс двенадцать, без осадков"), rendered);
        assertFalse(rendered.contains("…"), "короткое не резать: обрыв без причины её запутает");
    }

    @Test
    void noResultsSaysSoInsteadOfReturningNothing() {
        assertEquals("Nothing found on the web for: погода в Сочи",
                WebSearch.render(MAPPER.createArrayNode(), "погода в Сочи"),
                "пустой ответ эндпоинта модель должна отличить от своей ошибки");
    }
}
