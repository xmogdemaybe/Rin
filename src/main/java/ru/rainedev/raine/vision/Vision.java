package ru.rainedev.raine.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.Refusal;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.prompt.Prompts;
import ru.rainedev.raine.speech.ModelHearing.Track;

/**
 * Превращает картинку в описание — иначе на присланное фото Raine видит
 * только пометку о том, что фото было.
 * <p>
 * Описания кэшируются рядом: одна и та же картинка попадается в переписке
 * много раз, а смотреть на неё заново стоит денег и времени.
 */
public final class Vision {

    private static final Logger log = LoggerFactory.getLogger(Vision.class);

    /** Больше — почти всегда зря: модель начинает пересказывать саму себя. */
    private static final int MAX_ATTEMPTS = 3;

    /** Столько кадров смотрится одновременно. Больше — очередь у поставщика. */
    private static final int FRAMES_AT_ONCE = 6;

    /** Меньше этого — не картинка, а недокачанный файл. */
    private static final int MIN_IMAGE_BYTES = 512;

    /** Что смотрим. От этого зависит и промпт, и какой моделью. */
    public enum Kind {
        /** Присланное фото — важно разглядеть, берём основную модель. */
        PHOTO("photo", "photo_to_text.md", false),
        /** Стикер — достаточно понять смысл, хватит дешёвой. */
        STICKER("sticker", "sticker_to_text.md", true),
        /** Аватарка — понять, с кем говоришь. */
        AVATAR("chat_photo", "photo_to_text.md", true),
        /** Один кадр видео: подпись короткая, кадров много. */
        VIDEO("video", "video_frame_to_text.md", true);

        private final String tag;
        private final String prompt;
        private final boolean cheap;

        Kind(String tag, String prompt, boolean cheap) {
            this.tag = tag;
            this.prompt = prompt;
            this.cheap = cheap;
        }
    }

    private final LlmClient llm;
    private final Prompts prompts;
    private final Path cacheDir;
    private final String mainModel;
    private final String cheapModel;
    private final String characterName;

    /** Слух подключается отдельно: без него видео остаётся немым. */
    private ru.rainedev.raine.speech.ModelHearing hearing;

    public Vision(LlmClient llm, Prompts prompts, Path cacheDir,
                  String mainModel, String cheapModel, String characterName) {
        this.llm = llm;
        this.prompts = prompts;
        this.cacheDir = cacheDir;
        this.mainModel = mainModel;
        this.cheapModel = cheapModel;
        this.characterName = characterName;
    }

    public void hearing(ru.rainedev.raine.speech.ModelHearing hearing) {
        this.hearing = hearing;
    }

    /**
     * Видео описывается покадрово, и каждая подпись помечена своим временем.
     * Склеить кадры в одну картинку было бы дешевле, но тогда пропадает
     * главное: что было раньше, а что позже. «Сначала показывает кота, потом
     * роняет чашку» — это уже рассказ, а не набор картинок.
     *
     * @param frames чем достают кадры: распаковку не зовут, когда описание уже готово
     * @param context последние сообщения — без них модель описывает ролик
     *                вообще, а не применительно к разговору
     * @param file сам файл — из него слышно звук. Без него видео немое
     * @param withSound слушать ли дорожку. Анимации и прочие ролики без звука не
     *                  слушают вовсе: распознавание речи на тишине не молчит, а
     *                  выдумывает субтитры, и они ушли бы в разговор как услышанное
     * @param allowCache читать ли готовую ленту кадров и писать её в кэш. Второй
     *                   взгляд с вопросом читает мимо кэша: там лежит общий
     *                   пересказ, а не ответ на вопрос
     * @return размеченная лента подписей или пустая строка
     */
    public String describeVideo(Supplier<List<VideoFrames.Frame>> frames, List<Message> context,
                                java.nio.file.Path file, boolean withSound, boolean allowCache) {
        // распаковка дороже самого разбора: незачем вынимать кадры из ролика,
        // ответ на который уже лежит в кэше
        String remembered = remembered(file, allowCache);
        return remembered == null ? lookFrames(frames.get(), context, file, withSound, allowCache) : remembered;
    }

    /**
     * Готовое описание ролика, или null, если смотреть правда нужно. Разбор — это
     * шестнадцать обращений к зрению плюс расшифровка звука, и повторять их при
     * каждом открытии чата непозволительно дорого.
     */
    private String remembered(java.nio.file.Path file, boolean allowCache) {
        if (file == null || !allowCache) {
            return null;
        }
        String remembered = fromCache(file);
        if (remembered != null) {
            log.debug("Видео {} уже смотрела", file.getFileName());
        }
        return remembered;
    }

    private String lookFrames(List<VideoFrames.Frame> frames, List<Message> context, java.nio.file.Path file,
                              boolean withSound, boolean allowCache) {
        if (frames.isEmpty()) {
            // картинку разобрать не вышло, но в звуке может быть всё главное
            String onlySound = heard(file, withSound)
                    .map(speech -> "<video transcription>\n<f track=\"audio\">\n" + Markup.body(speech)
                            + "\n</f>\n</video transcription>\n")
                    .orElse("");
            if (!onlySound.isEmpty() && file != null && allowCache) {
                toCache(file, onlySound);
            }
            return onlySound;
        }
        String prompt = prompts.load(Kind.VIDEO.prompt);
        String situation = buildContext(context);

        // кадры смотрятся одновременно: ответ по каждому идёт полминуты, и по
        // очереди шестнадцать кадров — это десять минут молчания на один ролик.
        // Порядок при этом сохраняется, иначе лента времени перестанет быть лентой
        List<String> captions;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(FRAMES_AT_ONCE,
                Thread.ofVirtual().name("rin-frame-", 0).factory())) {
            List<java.util.concurrent.Future<String>> pending = frames.stream()
                    .map(frame -> pool.submit(() -> captionOf(frame, prompt, situation)))
                    .toList();
            captions = pending.stream().map(Vision::quietly).toList();
        }

        StringBuilder timeline = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            if (captions.get(i).isEmpty()) {
                continue;
            }
            VideoFrames.Frame frame = frames.get(i);
            timeline.append("<f from=\"").append(VideoFrames.timestamp(frame.from()))
                    .append("\" to=\"").append(VideoFrames.timestamp(frame.to())).append("\">\n")
                    .append(Markup.body(captions.get(i))).append("\n</f>\n");
        }
        if (timeline.isEmpty()) {
            return "";
        }
        heard(file, withSound).ifPresent(speech ->
                timeline.append("<f track=\"audio\">\n").append(Markup.body(speech)).append("\n</f>\n"));
        log.info("Просмотрено кадров: {}", frames.size());
        String described = "<video transcription>\n" + timeline
                + "</video transcription instructions=\"You finished watching this video and should "
                + "acknowledge its contents shown above.\">\n";
        if (file != null && allowCache) {
            toCache(file, described);
        }
        return described;
    }

    /**
     * В ролике половина смысла обычно в звуке: без него «человек что-то говорит
     * в камеру» — это всё, что она узнает.
     *
     * @param withSound есть ли в ролике что слушать
     */
    private java.util.Optional<String> heard(java.nio.file.Path file, boolean withSound) {
        if (!withSound || file == null || hearing == null) {
            return java.util.Optional.empty();
        }
        // дорожку берёт слух: он сам решает, слушать ли её вовсе, вынимает звук
        // из файла и знает, есть ли там что распознавать
        return hearing.listen(file, Track.VIDEO);
    }

    private static String quietly(java.util.concurrent.Future<String> caption) {
        try {
            return caption.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (java.util.concurrent.ExecutionException e) {
            // кадр без подписи выпадет из ленты времени, но без строки в логе
            // сбой неотличим от отказа смотреть
            log.warn("Кадр не разгляделся: {}", e.getMessage());
            return "";
        }
    }

    private String captionOf(VideoFrames.Frame frame, String prompt, String situation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String caption = llm.describeImage(cheapModel, prompt, situation, Downscale.toFit(frame.jpeg()));
                if (Refusal.detected(caption)) {
                    // кадр без подписи выпадет из ленты времени, и это честнее
                    // извинения, выданного за увиденное
                    log.debug("Кадр на {} с смотреть отказались, попытка {}", frame.from(), attempt);
                    continue;
                }
                if (caption != null && !caption.isBlank()) {
                    return caption.strip();
                }
                // пустой ответ обычно значит, что модель захлебнулась рассуждениями
                log.debug("Пустая подпись к кадру на {} с, попытка {}", frame.from(), attempt);
            } catch (RuntimeException e) {
                // сбой обращения — не отказ: пробуем, пока попытки не кончились
                log.warn("Кадр на {} секунде не разглядеть, попытка {} из {}: {}", frame.from(), attempt,
                        MAX_ATTEMPTS, e.getMessage());
            }
        }
        return "";
    }

    /**
     * @param context последние сообщения — без них модель описывает картинку
     *                вообще, а не применительно к разговору
     * @return размеченное описание или пустая строка, если посмотреть не вышло
     */
    public String describe(Path file, Kind kind, List<Message> context) {
        return describe(file, kind, context, true);
    }

    /**
     * @param allowCache читать ли готовое описание из кэша и писать туда новое.
     *                   Второй взгляд с вопросом — нет: в кэше лежит общий
     *                   пересказ, ответа на вопрос в нём нет, а узкий ответ не
     *                   должен вытеснить обычный пересказ картинки
     */
    public String describe(Path file, Kind kind, List<Message> context, boolean allowCache) {
        String cached = allowCache ? fromCache(file) : null;
        if (cached != null) {
            return wrap(kind, cached);
        }

        byte[] image;
        try {
            image = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file.getFileName(), e.getMessage());
            return "";
        }
        // пустой файл Telegram отдаёт, когда вложение ещё не скачалось. Модель
        // на это отвечает «картинки нет», и эта фраза уходит в разговор как
        // описание — лучше промолчать
        if (image.length < MIN_IMAGE_BYTES) {
            log.info("Файл {} пуст ({} байт) — смотреть нечего", file.getFileName(), image.length);
            return "";
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // на последней попытке дешёвую модель сменяет основная: дешёвая
            // отказывается заметно чаще, а переспрашивать её тем же самым —
            // получать тот же отказ
            String model = kind.cheap && attempt < MAX_ATTEMPTS ? cheapModel : mainModel;
            try {
                String description = llm.describeImage(model, prompts.load(kind.prompt), buildContext(context),
                        Downscale.toFit(image));
                if (Refusal.detected(description)) {
                    // в кэш не кладём: иначе отказ станет вечным описанием картинки
                    log.info("Смотреть отказались ({}), попытка {} из {}: {}", model, attempt, MAX_ATTEMPTS,
                            description.strip().lines().findFirst().orElse(""));
                    continue;
                }
                if (description != null && !description.isBlank()) {
                    if (allowCache) {
                        toCache(file, description.strip());
                    }
                    log.info("Разглядела {}: {}", kind.tag, description.strip().lines().findFirst().orElse(""));
                    return wrap(kind, description.strip());
                }
                // пустой ответ обычно значит, что модель захлебнулась рассуждениями
                log.debug("Пустое описание, попытка {} из {}", attempt, MAX_ATTEMPTS);
            } catch (RuntimeException e) {
                // сбой обращения к модели — это не отказ: попытки нужны все,
                // иначе одна сетевая ошибка обнуляет весь цикл
                log.warn("Не удалось разглядеть {}, попытка {} из {}: {}", file.getFileName(), attempt,
                        MAX_ATTEMPTS, e.getMessage());
            }
        }
        return "";
    }

    /**
     * Модель смотрит не в пустоту: ей даётся собственная внешность Raine, чтобы
     * она узнавала её на фото, и текущий разговор — чтобы описание было к месту.
     */
    private String buildContext(List<Message> context) {
        StringBuilder out = new StringBuilder("<context>\n<character name=\"")
                .append(characterName).append("\">\n")
                .append(prompts.load("character_appearance.md"))
                .append("\n</character>\n");
        if (context != null) {
            for (Message message : context) {
                if (message.content() != null && !message.content().isBlank()) {
                    out.append("<context_item>\n").append(message.content()).append("\n</context_item>\n");
                }
            }
        }
        return out.append("</context>\n\nDescribe the photo.").toString();
    }

    /** Описание рисует модель по чужому вложению: границы блоков оно ломать не должно. */
    private static String wrap(Kind kind, String description) {
        return "<%s description>\n%s\n</%s>\n".formatted(kind.tag, Markup.body(description), kind.tag);
    }

    private String fromCache(Path file) {
        try {
            Path cached = cacheFor(file);
            return Files.exists(cached) ? Files.readString(cached) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void toCache(Path file, String description) {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheFor(file), description);
        } catch (IOException e) {
            log.debug("Описание не сохранилось в кэш: {}", e.getMessage());
        }
    }

    private Path cacheFor(Path file) {
        return cacheDir.resolve(file.getFileName() + ".md");
    }
}
