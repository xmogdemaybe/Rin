package ru.rainedev.raine.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Шаблон разбора стикера должен оставаться общим для любого персонажа и не
 * провоцировать фильтр. Прошлый вариант требовал «Name real people or
 * characters» и описывать позу — на аниме-артах это давало то «Seductive Anime
 * Girl in Bed», то «Smug Cat-Smile Rin» на чужом паке: светлые волосы модель
 * считала достаточным знаком, а триггерные заголовки потом ездили по полке и
 * пересказу.
 */
class StickerPromptTest {

    private static final Path TEMPLATE = Path.of("prompts-example", "sticker_to_text.md");

    private static String template() throws IOException {
        return Files.readString(TEMPLATE);
    }

    @Test
    void everyFieldOfTheFormatStays() throws IOException {
        String text = template();

        for (String field : new String[] {"Title", "VisibleContent", "TextInSticker", "Emotion",
                "Intensity", "CommunicativeMeaning", "UsageExamples", "MemeReference"}) {
            assertTrue(text.contains("- " + field + ":"), "поле пропало: " + field);
        }
    }

    @Test
    void theBluntNamingRuleIsGone() throws IOException {
        assertFalse(template().contains("Name real people or characters"),
                "это правило и просило называть людей по имени");
    }

    /** Имя персонажа из настроек — только когда совпали все его приметы, а не цвет волос. */
    @Test
    void namingTheOwnerRequiresEverySignatureTrait() throws IOException {
        String text = template();

        assertTrue(text.contains("<character>"), "правило должно опираться на внешность из настроек: " + text);
        assertTrue(text.contains("every distinctive signature trait"), text);
        assertTrue(text.contains("hair color") && text.contains("otherwise say \"character\""),
                "совпадение общего признака не должно превращать чужого персонажа в неё");
    }

    /** Шаблон живёт в чужом форке: чужие приметы в него зашивать нельзя. */
    @Test
    void theTemplateHardcodesNobodyAppearance() throws IOException {
        String text = template().toLowerCase();

        for (String trait : new String[] {"white hair", "cat ears", "red eyes", "barcode"}) {
            assertFalse(text.contains(trait), "примета конкретного персонажа в общем шаблоне: " + trait);
        }
    }

    @Test
    void theToneIsKeptNeutralAndShort() throws IOException {
        String text = template();

        assertTrue(text.contains("PG-rated"), "тон разбора задан словами, а не надеждой");
        assertTrue(text.contains("Target length: 40-80 words."),
                "объём ответа из шаблона не должен исчезать: без него разбор разрастается до абзаца");
    }
}
