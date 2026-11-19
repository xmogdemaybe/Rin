package ru.rainedev.raine.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.LlmFailure;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.Refusal;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.prompt.Prompts;
import ru.rainedev.raine.speech.ModelHearing.Track;
import ru.rainedev.raine.storage.AtomicFiles;

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
    static final int MIN_IMAGE_BYTES = 512;

    /**
     * Сколько помнится неудача: файл мог докачаться, и через час его стоит
     * попробовать снова. До этого срока повтор заведомо тот же.
     */
    private static final long FAILURE_MEMORY_MS = java.time.Duration.ofHours(1).toMillis();

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

    /** Строка вердикта: в кэше и в разговоре ей делать нечего. */
    private static final java.util.regex.Pattern NSFW_LINE =
            java.util.regex.Pattern.compile("(?im)^[\\t ]*(?:[-*][\\t ]*)?NSFW[\\t ]*:.*(?:\\R|$)");

    /** Заголовок описания: единственное поле, где чистим слова манеры рисовки. */
    private static final java.util.regex.Pattern TITLE_LINE = java.util.regex.Pattern.compile(
            "(?im)^([\\t ]*[-*][\\t ]*(?:\\*\\*)?Title(?:\\*\\*)?[\\t ]*:[\\t ]*)([^\\r\\n]*)");

    private static final java.util.regex.Pattern STYLE_WORD = java.util.regex.Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:anime-style|chibi-style|anime|chibi|manga|illustration|cartoon)"
                    + "(?![\\p{L}\\p{N}])");

    private final LlmClient llm;
    private final Prompts prompts;
    private final Path cacheDir;
    /** Куда ложатся описания стикеров: их не сносит уборка временных файлов. */
    private final Path stickerDir;
    private final String mainModel;
    private final String cheapModel;
    private final String characterName;

    /** Слух подключается отдельно: без него видео остаётся немым. */
    private ru.rainedev.raine.speech.ModelHearing hearing;

    public Vision(LlmClient llm, Prompts prompts, Path cacheDir, Path stickerDir,
                  String mainModel, String cheapModel, String characterName) {
        this.llm = llm;
        this.prompts = prompts;
        this.cacheDir = cacheDir;
        this.stickerDir = stickerDir;
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
            return remembered;
        }
        if (failedRecently(file)) {
            // распаковка и полтора десятка кадров уже оплачены однажды
            log.debug("Ролик {} недавно не разгляделся — смотреть заново незачем", file.getFileName());
            return "";
        }
        return null;
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
                toCache(file, onlySound, Kind.VIDEO);
            }
            if (onlySound.isEmpty() && file != null && allowCache) {
                rememberFailure(file);
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
            if (file != null && allowCache) {
                rememberFailure(file);
            }
            return "";
        }
        heard(file, withSound).ifPresent(speech ->
                timeline.append("<f track=\"audio\">\n").append(Markup.body(speech)).append("\n</f>\n"));
        log.info("Просмотрено кадров: {}", frames.size());
        String described = "<video transcription>\n" + timeline
                + "</video transcription instructions=\"You finished watching this video and should "
                + "acknowledge its contents shown above.\">\n";
        if (file != null && allowCache) {
            toCache(file, described, Kind.VIDEO);
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
                    String cleaned = sanitize(caption);
                    if (!cleaned.isBlank()) {
                        return cleaned;
                    }
                }
                // пустой ответ обычно значит, что модель захлебнулась рассуждениями
                log.debug("Пустая подпись к кадру на {} с, попытка {}", frame.from(), attempt);
            } catch (LlmFailure failure) {
                if (!failure.retryable()) {
                    // ошибка полезной нагрузки: тот же кадр не примут ни со
                    // второй попытки, ни другой моделью
                    log.warn("Кадр на {} секунде не разглядеть ({}): {}", frame.from(), failure.status(),
                            failure.getMessage());
                    return "";
                }
                log.warn("Кадр на {} секунде не разглядеть, попытка {} из {}: {}", frame.from(), attempt,
                        MAX_ATTEMPTS, failure.getMessage());
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

    public String describe(Path file, Kind kind, List<Message> context, boolean allowCache) {
        return describe(file, kind, context, allowCache, false);
    }

    /**
     * @param allowCache читать ли готовое описание из кэша и писать туда новое.
     *                   Второй взгляд с вопросом — нет: в кэше лежит общий
     *                   пересказ, ответа на вопрос в нём нет, а узкий ответ не
     *                   должен вытеснить обычный пересказ картинки
     * @param preview    заставка вместо самого ролика. Помечают отдельно: по
     *                   одному кадру не узнать, что в ролике было дальше, и
     *                   догадка не должна уходить в разговор как увиденное
     */
    public String describe(Path file, Kind kind, List<Message> context, boolean allowCache, boolean preview) {
        String body = words(file, kind, context, allowCache);
        return body.isEmpty() ? "" : wrap(kind, body, preview);
    }

    /**
     * Что видно на вложении — текстом, без обёртки. Нужен компактным спискам:
     * размеченный блок им мешает, а расщеплять чужую разметку обратно регуляркой
     * значит завести вторую копию знания о формате.
     */
    /** Разгляден ли файл уже: по этому список решает, тратить ли свежий взгляд. */
    public boolean known(Path file) {
        return fromCache(file) != null;
    }

    /**
     * Готовое описание текстом или пусто, если его нет. Ни скачивания, ни
     * обращения к модели: так читают полку, которая пересобирается на ходу
     * разговора и ждать не имеет права.
     */
    public String cachedDescription(Path file) {
        String cached = fromCache(file, Kind.STICKER);
        return cached == null ? "" : Markup.body(cached).strip();
    }

    public String words(Path file, Kind kind, List<Message> context) {
        return words(file, kind, context, true);
    }

    private String words(Path file, Kind kind, List<Message> context, boolean allowCache) {
        String cached = allowCache ? fromCache(file, kind) : null;
        if (cached != null) {
            return Markup.body(cached).strip();
        }
        // MediaAhead разбирает вложение заранее, а MessageFormatter тут же
        // просит его снова: без памяти о неудаче второй запрос оплачивается
        // впустую и обрывается тем же отказом
        if (allowCache && failedRecently(file)) {
            log.debug("{} недавно не разгляделся — смотреть заново незачем", file.getFileName());
            return "";
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

        boolean technicalFailure = false;
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
                    String cleaned = sanitize(description);
                    if (cleaned.isBlank()) {
                        log.debug("После чистки не осталось описания, попытка {} из {}", attempt, MAX_ATTEMPTS);
                        continue;
                    }
                    if (allowCache) {
                        toCache(file, cleaned, kind);
                    }
                    log.info("Разглядела {}: {}", kind.tag, cleaned.lines().findFirst().orElse(""));
                    return Markup.body(cleaned).strip();
                }
                // пустой ответ обычно значит, что модель захлебнулась рассуждениями
                log.debug("Пустое описание, попытка {} из {}", attempt, MAX_ATTEMPTS);
            } catch (LlmFailure failure) {
                technicalFailure = true;
                if (!failure.retryable()) {
                    // 400 и прочие невозвратные 4xx — ошибка самой картинки:
                    // повтор и эскалация на основную модель оплачены, а исход
                    // тот же, поэтому попытки кончаются здесь же
                    log.warn("Не удалось разглядеть {} ({}): {}", file.getFileName(), failure.status(),
                            failure.getMessage());
                    if (allowCache) {
                        rememberFailure(file);
                    }
                    return "";
                }
                log.warn("Не удалось разглядеть {}, попытка {} из {}: {}", file.getFileName(), attempt,
                        MAX_ATTEMPTS, failure.getMessage());
            } catch (RuntimeException e) {
                // сбой обращения к модели — это не отказ: попытки нужны все,
                // иначе одна сетевая ошибка обнуляет весь цикл
                technicalFailure = true;
                log.warn("Не удалось разглядеть {}, попытка {} из {}: {}", file.getFileName(), attempt,
                        MAX_ATTEMPTS, e.getMessage());
            }
        }
        // отказ модели — не наша неудача: его переспрашивают сразу, а вот
        // исчерпанные технические попытки второй раз оплачивать незачем
        if (allowCache && technicalFailure) {
            rememberFailure(file);
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
                .append(prompts.characterIdentity())
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

    /**
     * Описание рисует модель по чужому вложению: границы блоков оно ломать не
     * должно. Заставку отмечают в открывающем теге, чтобы модель отличала один
     * кадр от просмотра всего ролика.
     */
    private static String wrap(Kind kind, String description, boolean preview) {
        return "<%s description%s>\n%s\n</%s>\n".formatted(kind.tag, preview ? " preview=\"true\"" : "",
                Markup.body(description), kind.tag);
    }

    /**
     * Убирает вердикт и слова манеры рисовки: вердикт — внутренняя пометка,
     * а не часть описания, и в кэше ему не место. Пустая строка после чистки
     * означает, что описания не осталось.
     */
    static String sanitize(String description) {
        if (description == null) {
            return "";
        }
        return cleanTitles(withoutVerdict(description));
    }

    /** Вырезает строку вердикта целиком: с дефисом или без, в любом регистре. */
    static String withoutVerdict(String text) {
        return text == null ? "" : NSFW_LINE.matcher(text).replaceAll("");
    }

    private static String cleanTitles(String text) {
        var matcher = TITLE_LINE.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        do {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + cleanTitle(matcher.group(2))));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    private static String cleanTitle(String title) {
        String cleaned = STYLE_WORD.matcher(title).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\s+", " ").strip();
        cleaned = cleaned.replaceAll("^[,;:.\\-–—|]+\\s*", "");
        cleaned = cleaned.replaceAll("\\s*[,;:\\-–—|]+$", "");
        return cleaned.isBlank() ? "Untitled" : cleaned;
    }

    /**
     * Готовое описание или null, если смотреть правда нужно.
     * <p>
     * Пустое содержимое — промах, а не описание: такой файл остаётся от записи,
     * прерванной на середине. Принятая за описание пустота выглядела бы как
     * просмотренная картинка без единого слова о ней.
     */
    private String fromCache(Path file) {
        return fromCache(file, null);
    }

    /**
     * @param kind описание стикера, найденное в папке под сроком, переезжает в
     *             вечную: уборка сносит её раз в две недели по времени записи,
     *             а любимым стикером пользуются каждый день
     */
    private String fromCache(Path file, Kind kind) {
        String kept = readCache(stickerCacheFor(file));
        if (kept != null) {
            return kept;
        }
        String keptAside = readCache(cacheFor(file));
        if (keptAside != null && kind == Kind.STICKER) {
            moveUp(file);
        }
        return keptAside;
    }

    private static String readCache(Path cache) {
        try {
            String raw = Files.readString(cache);
            String cleaned = sanitize(raw);
            if (!cleaned.equals(raw)) {
                // старый кэш переписываем на месте, чтобы вердикт не вернулся
                // при следующем чтении; пустой файл — просто промах
                try {
                    if (cleaned.isEmpty()) {
                        Files.deleteIfExists(cache);
                    } else {
                        AtomicFiles.writeString(cache, cleaned);
                    }
                } catch (IOException e) {
                    log.debug("Кэш {} не переписался: {}", cache.getFileName(), e.getMessage());
                }
            }
            return cleaned.isBlank() ? null : cleaned;
        } catch (IOException e) {
            return null;   // файла нет или он не прочитался — смотрят заново
        }
    }

    /**
     * Перенос один на файл: дальше описание читается из вечной папки, а старое
     * место освободится самой уборкой. Не переехало — читаем и прежнее.
     */
    private void moveUp(Path file) {
        try {
            Files.createDirectories(stickerDir);
            Files.move(cacheFor(file), stickerCacheFor(file), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.debug("Описание стикера {} не переехало в вечную папку: {}", file.getFileName(), e.getMessage());
        }
    }

    /**
     * Замена идёт через временный файл: прежний кэш либо остаётся целым, либо
     * уступает место целому новому — но никогда не оборванному на середине.
     */
    private void toCache(Path file, String description, Kind kind) {
        String cleaned = sanitize(description);
        if (cleaned.isBlank()) {
            // пустое описание не должно заслонять картинку навсегда
            return;
        }
        try {
            AtomicFiles.writeString(cacheFor(file, kind), cleaned);
            // раз описание есть, память о неудаче больше не о чём: её маркер
            // лежит в папке под сроком и без ухода накопился бы за все годы
            Files.deleteIfExists(failureFor(file));
        } catch (IOException e) {
            log.debug("Описание не сохранилось в кэш: {}", e.getMessage());
        }
    }

    private Path cacheFor(Path file) {
        return cacheDir.resolve(file.getFileName() + ".md");
    }

    private Path cacheFor(Path file, Kind kind) {
        return (kind == Kind.STICKER ? stickerDir : cacheDir).resolve(file.getFileName() + ".md");
    }

    private Path stickerCacheFor(Path file) {
        return stickerDir.resolve(file.getFileName() + ".md");
    }

    /**
     * Смотреть нечем без всякого обращения к модели: файл недавно не разгляделся,
     * его нет на диске или он недокачан. Это пропуск, а не отказ зрения, — поэтому
     * списки на нём не обрываются, а бюджет свежих просмотров не тратится.
     */
    public boolean unusable(Path file) {
        return failedRecently(file) || !readable(file);
    }

    private static boolean readable(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) >= MIN_IMAGE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Неудача бывает не у картинки, а у связи или у самого файла. Помнить её
     * надо недолго: истёкшая запись значит, что пора попробовать снова.
     */
    private boolean failedRecently(Path file) {
        try {
            long when = Long.parseLong(Files.readString(failureFor(file)).strip());
            return System.currentTimeMillis() - when < FAILURE_MEMORY_MS;
        } catch (IOException | NumberFormatException e) {
            return false;   // записи нет или она испорчена — смотрят заново
        }
    }

    private void rememberFailure(Path file) {
        try {
            AtomicFiles.writeString(failureFor(file), Long.toString(System.currentTimeMillis()));
        } catch (IOException e) {
            log.debug("Неудача не запомнилась: {}", e.getMessage());
        }
    }

    private Path failureFor(Path file) {
        return cacheDir.resolve(file.getFileName() + ".failed");
    }

    /**
     * Перенос прежних описаний стикеров в вечную папку — один раз на запуск.
     * <p>
     * Уборка сносит кэш картинок по времени последней записи, а стикер
     * разглядывают однажды и пользуются им годами: без переноса описание
     * пропало бы через две недели после просмотра, и полка снова платила бы
     * зрением. Берём только растровые стикерные файлы — фото и ролики под
     * сроком остаться должны.
     *
     * @return сколько описаний переехало
     */
    public static int moveStickerCache(Path cacheDir, Path stickerDir) {
        if (!Files.isDirectory(cacheDir)) {
            return 0;   // кэша ещё не было: переносить нечего
        }
        try (java.util.stream.Stream<Path> files = Files.list(cacheDir)) {
            List<Path> stale = files.filter(Vision::stickerDescription)
                    .filter(file -> !Files.exists(stickerDir.resolve(file.getFileName())))
                    .toList();
            if (stale.isEmpty()) {
                return 0;
            }
            Files.createDirectories(stickerDir);
            int moved = 0;
            for (Path file : stale) {
                try {
                    Files.move(file, stickerDir.resolve(file.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } catch (IOException e) {
                    log.warn("Описание стикера {} не переехало: {}", file.getFileName(), e.getMessage());
                }
            }
            log.info("Описаний стикеров перенесено в {}: {}", stickerDir, moved);
            return moved;
        } catch (IOException e) {
            log.warn("Кэш стикеров не удалось разобрать: {}", e.getMessage());
            return 0;
        }
    }

    /** Описание стикера: растровый файл. Миниатюры паков тоже растровые. */
    private static boolean stickerDescription(Path file) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(".webp.md");
    }
}
