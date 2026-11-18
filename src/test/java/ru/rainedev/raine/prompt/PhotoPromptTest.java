package ru.rainedev.raine.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Шаблон разбора фотографии не должен провоцировать выходной фильтр и не должен
 * превращать любого светловолосого персонажа в неё. Прежняя редакция просила
 * «age-range, sex-presenting», перечисляла части тела в поле NSFW и предлагала
 * называть людей по имени: на артах это давало «Two anime-style girls» и
 * «Rin sitting on a bus seat», а вечером 17 ноября такие описания ловил
 * контент-фильтр. Поля формата при этом обязаны остаться — по ним описание
 * ищется потом.
 * <p>
 * Вечер 18.11 показал дыру в первой редакции запрета: четыре описания, снятых
 * уже по новому шаблону, всё равно начинались с «Anime-style illustration of
 * Rin» и «Two anime-style characters» — запрет был сформулирован про ярлык
 * человека, а модель указывала манеру рисовки для всей картинки прямо в
 * заголовке. Теперь правило называет и заголовок.
 */
class PhotoPromptTest {

    private static final Path TEMPLATE = Path.of("prompts-example", "photo_to_text.md");

    private static String template() throws IOException {
        return Files.readString(TEMPLATE);
    }

    @Test
    void everyFieldOfTheFormatStays() throws IOException {
        String text = template();

        for (String field : new String[] {"Title", "DistinctiveFeatures", "ObjectsAndLayout", "Context", "TextInImage",
                "ColorsPatternsMaterials", "ActionsAndPoses", "CameraViewpoint", "Uncertainties", "Names", "NSFW"}) {
            assertTrue(text.contains("- " + field + ":"), "поле пропало: " + field);
        }
    }

    /** Имя из настроек — только когда вместе все опознавательные приметы. */
    @Test
    void namingTheOwnerRequiresEverySignatureTrait() throws IOException {
        String text = template();

        assertTrue(text.contains("<character>"), "правило должно опираться на внешность из настроек: " + text);
        assertTrue(text.contains("every distinctive signature trait"), text);
        assertTrue(text.contains("hair color") && text.contains("otherwise say \"person\" or \"character\""),
                "совпадение общего признака не должно превращать чужого человека в неё");
    }

    /** Поле про недопустимое остаётся, но ответ — одно слово, а не перечисление тел. */
    @Test
    void theNsfwFieldAsksForAVerdictNotAnAnatomyLesson() throws IOException {
        String text = template();

        assertTrue(text.contains("- NSFW:"), text);
        assertTrue(text.contains("yes, no, or unclear"), "вердикт должен быть одним словом: " + text);
        assertTrue(text.contains("do not list body parts"), text);
    }

    /** Слова, на которые режется фильтр, не должны вернуться в шаблон. */
    @Test
    void theTemplateCarriesNoTriggerVocabulary() throws IOException {
        String text = template().toLowerCase();

        for (String word : new String[] {"age-range", "sex-presenting", "nipples", "areola", "buttocks", "genitalia",
                "cleavage", "transparent clothes", "seductive", "attractive", "anime", "cartoon", "manga",
                "illustration", "anime-style girl", "school girl", "young girl"}) {
            assertFalse(text.contains(word), "триггерное слово вернулось в шаблон: " + word);
        }
    }

    @Test
    void theToneIsKeptNeutralAndUnlabeled() throws IOException {
        String text = template();

        assertTrue(text.contains("dry, neutral and matter-of-fact"),
                "тон разбора задан словами, а не надеждой: " + text);
        assertTrue(text.contains("Do not label a person by age"), text);
    }

    /**
     * Запрет на ярлык манеры рисовки должен покрывать заголовок: 18.11 модель
     * честно не звала людей «anime girl», но ставила «Anime-style illustration
     * of Rin» в Title — и этого фильтру хватало.
     */
    @Test
    void theMediumBanCoversTheWholeAnswerIncludingTheTitle() throws IOException {
        String text = template();

        assertTrue(text.contains("Say neither the medium nor the art style"), "правило пропадало: " + text);
        assertTrue(text.contains("not in the Title"), "заголовок должен быть назван прямо: " + text);
        assertTrue(text.contains("not in the overall description"), "и общее описание тоже: " + text);
        assertFalse(text.toLowerCase().contains("anime"), "сам шаблон не должен учить слову, которое запрещает: " + text);
    }

    /** Шаблон живёт в чужом форке: приметы конкретного персонажа в него зашивать нельзя. */
    @Test
    void theTemplateHardcodesNobodyAppearance() throws IOException {
        String text = template().toLowerCase();

        for (String trait : new String[] {"white hair", "cat ears", "red eyes", "barcode"}) {
            assertFalse(text.contains(trait), "примета конкретного персонажа в общем шаблоне: " + trait);
        }
    }
}
