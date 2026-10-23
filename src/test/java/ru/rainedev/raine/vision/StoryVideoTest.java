package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Ролик истории смотрят не так, как ролик в переписке: у него есть запасной файл
 * и признак анимации.
 * <p>
 * Анимация звука не имеет вовсе, а распознавание речи на тишине не молчит —
 * оно выдумывает субтитры, и те ушли бы в разговор как услышанное. Основной файл
 * к тому же бывает в HEVC, который нашему разборщику не по зубам: на этот случай
 * Telegram кладёт рядом запасной, в H.264.
 */
class StoryVideoTest {

    /** Что пробовали смотреть и слушали ли при этом звук. */
    private record Attempt(Optional<Integer> file, boolean withSound) {}

    @Test
    void anAnimationIsWatchedWithoutSound() {
        List<Attempt> attempts = new ArrayList<>();

        TelegramMedia.storyVideo(content(story(file(5), true), null), (found, withSound) -> {
            attempts.add(new Attempt(found, withSound));
            return "видео";
        });

        assertEquals(List.of(new Attempt(Optional.of(5), false)), attempts);
    }

    @Test
    void anOrdinaryVideoKeepsItsSound() {
        List<Attempt> attempts = new ArrayList<>();

        TelegramMedia.storyVideo(content(story(file(5), false), null), (found, withSound) -> {
            attempts.add(new Attempt(found, withSound));
            return "видео";
        });

        assertEquals(List.of(new Attempt(Optional.of(5), true)), attempts);
    }

    @Test
    void theAlternativeIsTriedWhenTheMainOneYieldsNothing() {
        // так и выглядит HEVC: кадры не декодируются, и вместо ролика выходит пусто
        List<Attempt> attempts = new ArrayList<>();

        String seen = TelegramMedia.storyVideo(content(story(file(5), false), story(file(9), false)),
                (found, withSound) -> {
                    attempts.add(new Attempt(found, withSound));
                    return found.map(file -> file == 5 ? "" : "видео " + file).orElse("");
                });

        assertEquals("видео 9", seen);
        assertEquals(List.of(new Attempt(Optional.of(5), true), new Attempt(Optional.of(9), true)), attempts);
    }

    @Test
    void theAlternativeIsNotTriedWhenTheMainOneWorked() {
        // запасной ролик — лишний файл и лишние обращения к зрению
        List<Attempt> attempts = new ArrayList<>();

        String seen = TelegramMedia.storyVideo(content(story(file(5), false), story(file(9), false)),
                (found, withSound) -> {
                    attempts.add(new Attempt(found, withSound));
                    return "видео 5";
                });

        assertEquals("видео 5", seen);
        assertEquals(List.of(new Attempt(Optional.of(5), true)), attempts);
    }

    @Test
    void aMissingMainVideoLeavesTheAlternative() {
        // про запасной ролик ничего не известно — значит, и звук у него спрашивают
        List<Attempt> attempts = new ArrayList<>();

        String seen = TelegramMedia.storyVideo(content(null, story(file(9), true)), (found, withSound) -> {
            attempts.add(new Attempt(found, withSound));
            return found.map(file -> "видео " + file).orElse("");
        });

        assertEquals("видео 9", seen);
        assertEquals(List.of(new Attempt(Optional.empty(), true), new Attempt(Optional.of(9), true)), attempts);
    }

    private static TdApi.StoryContentVideo content(TdApi.StoryVideo main, TdApi.StoryVideo alternative) {
        TdApi.StoryContentVideo content = new TdApi.StoryContentVideo();
        content.video = main;
        content.alternativeVideo = alternative;
        return content;
    }

    /** Поля заполняем по одному: сигнатуры конструкторов TdApi меняются от версии к версии. */
    private static TdApi.StoryVideo story(TdApi.File file, boolean isAnimation) {
        TdApi.StoryVideo video = new TdApi.StoryVideo();
        video.video = file;
        video.isAnimation = isAnimation;
        return video;
    }

    private static TdApi.File file(int id) {
        TdApi.File file = new TdApi.File();
        file.id = id;
        return file;
    }
}
