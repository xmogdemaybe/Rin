package ru.rainedev.raine.vision;

import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.telegram.Telegram;

/** Скачивает вложение из Telegram и передаёт его зрению или слуху. */
public final class TelegramMedia implements MediaDescriber {

    /** Что видно, если историю достать не удалось. */
    private static final String UNREACHABLE = "её больше не открыть — могла протухнуть или быть скрытой";

    private final Telegram telegram;
    /** Может не быть: зрение включают тумблером, а подпись истории читается и без него. */
    private final Vision vision;
    private final Supplier<List<Message>> context;

    /** Слух самой модели: голосовые и кружки. Может не быть вовсе. */
    private ru.rainedev.raine.speech.ModelHearing hearing;

    /** Чем помечать услышанное; без него отметка не ставится даже с включённым тумблером. */
    private ru.rainedev.raine.telegram.TelegramActions actions;

    /** Ставить ли отметку «прослушано» — её увидит автор сообщения, поэтому решает владелец. */
    private boolean markHeard;

    /** Разбирать ли ролики в каналах покадрово. Обычно нет — слишком дорого. */
    private boolean videoInChannels;

    public TelegramMedia(Telegram telegram, Vision vision, Supplier<List<Message>> context) {
        this.telegram = telegram;
        this.vision = vision;
        this.context = context;
    }

    /** Есть ли чем разглядывать картинки. Без этого файл хотя бы не качают. */
    @Override
    public boolean sees() {
        return vision != null;
    }

    /** Слух подключается отдельно: голосовое слышно и без глаз. */
    public void hearing(ru.rainedev.raine.speech.ModelHearing hearing) {
        this.hearing = hearing;
    }

    /**
     * @param allowed тумблер владельца: отметку «прослушано» увидит тот, кто прислал
     */
    public void markHeard(ru.rainedev.raine.telegram.TelegramActions actions, boolean allowed) {
        this.actions = actions;
        this.markHeard = allowed;
    }

    public void videoInChannels(boolean allowed) {
        this.videoInChannels = allowed;
    }

    @Override
    public String describe(TdApi.Message message) {
        if (message == null || message.content == null) {
            return "";
        }
        // без зрения разбирать нечего, и история не в счёт: её подпись и плашки —
        // текст, а не картинка. Голосовое и кружок слушают ушами, а не глазами,
        // поэтому они разбираются и без зрения. Остальные вложения при
        // выключенном зрении остаются ровно тем, чем были: пометкой о типе
        if (!sees() && !hearable(message) && !(message.content instanceof TdApi.MessageStory)) {
            return "";
        }
        return switch (message.content) {
            case TdApi.MessagePhoto photo -> look(largest(photo.photo), Vision.Kind.PHOTO);
            case TdApi.MessageSticker sticker -> {
                // запоминаем: без этого её нельзя ни сохранить, ни отправить
                telegram.rememberSticker(sticker.sticker);
                yield look(fileOf(sticker.sticker.sticker), Vision.Kind.STICKER);
            }
            // у подарка есть вид и цена — без них «подарок» ничего не говорит
            case TdApi.MessageGift gift -> gift(gift);
            case TdApi.MessageVoiceNote ignored -> heard(message, "voice");
            case TdApi.MessageVideoNote videoNote ->
                    // в кружке есть и картинка, и речь — берём и то, и другое;
                    // одно и то же дважды в разговор не пойдёт: если кружок
                    // слушает сама модель, распознаватель к дорожке не зовут
                    watch(fileOf(videoNote.videoNote.video), !listensCircles()) + heard(message, "video_note");
            case TdApi.MessageVideo video -> video.video == null ? "" : watchOrGlance(
                    message, fileOf(video.video.video), thumbnailOf(video.video.thumbnail));
            case TdApi.MessageAnimation animation -> animation.animation == null ? "" : watchOrGlance(
                    message, fileOf(animation.animation.animation), thumbnailOf(animation.animation.thumbnail));
            // роликом, присланным файлом, каналы пользуются чаще всего: по типу
            // сообщения это документ, и без разбора имени он уходил бы в разбор
            // картинки, где из видео не видно ничего
            case TdApi.MessageDocument document -> document.document == null ? "" : switch (kindOf(document)) {
                case VIDEO -> watchOrGlance(message, fileOf(document.document.document),
                        thumbnailOf(document.document.thumbnail));
                case PHOTO -> look(fileOf(document.document.document), Vision.Kind.PHOTO);
                default -> "";
            };
            // история в сообщение не вкладывается: в нём остаются только чат и номер,
            // а сама она живёт отдельно — без этого видно, что историю кидали,
            // а что в ней, нет
            case TdApi.MessageStory shared -> sharedStory(message, shared);
            default -> "";
        };
    }

    private String sharedStory(TdApi.Message message, TdApi.MessageStory shared) {
        boolean onlyGlance = onlyGlanceIn(message);
        return described(telegram.story(shared.storyPosterChatId, shared.storyId),
                story -> describeStory(story, onlyGlance));
    }

    /**
     * История могла протухнуть или быть скрытой, и пустая строка здесь хуже
     * честного слова: пометка «[история]» остаётся, а содержимого нет — модель
     * додумывает его сама и отвечает так, будто смотрела.
     */
    static String described(Optional<TdApi.Story> story,
                            java.util.function.Function<TdApi.Story, String> seen) {
        return story.map(seen).orElse(UNREACHABLE);
    }

    /**
     * История — не вложение: картинка, подпись и прикреплённые плашки лежат
     * в отдельном объекте, поэтому смотрят и подписывают её по-своему.
     */
    public String describeStory(TdApi.Story story) {
        return describeStory(story, false);
    }

    /**
     * @param onlyGlance ролика не касаться вовсе — только заставка. Так смотрят
     *                   в каналах, где историй много на один день
     */
    public String describeStory(TdApi.Story story, boolean onlyGlance) {
        // вслепую разбирать нечего, и жалобу на формат из пустоты не собрать:
        // ролик цел, просто глаз нет — тогда остаются подпись и плашки
        String seen = sees() ? seen(story.content,
                file -> look(file, Vision.Kind.PHOTO),
                video -> storyVideo(video, onlyGlance, this::watch, () -> glanceOf(video))) : "";
        return storyBlock(story, seen);
    }

    /** Заставка ролика истории: один кадр вместо всего разбора. */
    private String glanceOf(TdApi.StoryContentVideo content) {
        return content.video == null
                ? ""
                : look(thumbnailOf(content.video.thumbnail), Vision.Kind.VIDEO);
    }

    /**
     * Что видно в истории: фото смотрят как фото, видео — как видео, а то, что
     * Telegram сам разобрать не смог, остаётся подписью без картинки.
     * Видно пакету: разбирать содержимое можно без Telegram и без зрения.
     */
    static String seen(TdApi.StoryContent content, java.util.function.Function<Optional<Integer>, String> look,
                       java.util.function.Function<TdApi.StoryContentVideo, String> watch) {
        return switch (content) {
            case TdApi.StoryContentPhoto photo -> look.apply(largest(photo.photo));
            case TdApi.StoryContentVideo video -> watch.apply(video);
            case null, default -> "";
        };
    }

    /**
     * Ролик истории.
     * <p>
     * Анимация звука не имеет вовсе, а распознавание речи на тишине не молчит —
     * оно выдумывает субтитры, и они ушли бы в разговор как услышанное.
     * <p>
     * Основной ролик бывает в HEVC, который нашему разборщику не по зубам:
     * когда из него ничего не вышло, пробуют запасной — тот в H.264.
     * Видно пакету: порядок проб учат без Telegram и без зрения.
     *
     * @param watch разбор файла: его номер и слушать ли звук
     */
    static String storyVideo(TdApi.StoryContentVideo content,
                             java.util.function.BiFunction<Optional<Integer>, Boolean, String> watch) {
        boolean withSound = content.video == null || !content.video.isAnimation;
        String seen = watch.apply(storyFile(content.video), withSound);
        return seen == null || seen.isBlank() ? watch.apply(storyFile(content.alternativeVideo), withSound) : seen;
    }

    /**
     * Тот же ролик, но с решением, смотреть ли его целиком.
     * <p>
     * В канале историй на один день больше, чем сообщений в переписке, и каждая
     * из них на покадровом разборе стоит обращения к зрению на каждый кадр.
     * Видно пакету: правило учат проверять без Telegram и без зрения.
     *
     * @param onlyGlance довольствоваться заставкой, не трогая сам ролик
     * @param watch      разбор файла: его номер и слушать ли звук
     * @param glance     один кадр вместо разбора
     */
    static String storyVideo(TdApi.StoryContentVideo content, boolean onlyGlance,
                             java.util.function.BiFunction<Optional<Integer>, Boolean, String> watch,
                             java.util.function.Supplier<String> glance) {
        return storyVideo(content,
                (file, withSound) -> watchOrGlance(onlyGlance, () -> watch.apply(file, withSound), glance));
    }

    private static Optional<Integer> storyFile(TdApi.StoryVideo video) {
        return fileOf(video == null ? null : video.video);
    }

    /**
     * Подпись и плашки — чужой текст, поэтому они уходят атрибутами: ни кавычек,
     * ни попыток открыть свой блок внутри них не останется.
     */
    static String storyBlock(TdApi.Story story, String seen) {
        StringBuilder out = new StringBuilder("<story");
        String caption = story.caption == null || story.caption.text == null ? "" : story.caption.text;
        if (!caption.isBlank()) {
            out.append(" caption=\"").append(Markup.attribute(caption)).append('"');
        }
        String attached = attached(story.areas);
        if (!attached.isBlank()) {
            out.append(" attached=\"").append(Markup.attribute(attached)).append('"');
        }
        return seen == null || seen.isBlank()
                ? out.append(" />").toString()
                : out.append(">\n").append(seen.strip()).append("\n</story>").toString();
    }

    /**
     * Плашки на истории рисует сам клиент поверх картинки: ни в файле, ни в кадрах
     * их нет. Ссылка, погода или репост поста видны только здесь.
     * <p>
     * Наполненность полей Telegram оставляет за собой, а слово «null» в описании
     * читается как часть содержания, поэтому пустоту называют самой плашкой.
     */
    static String attached(TdApi.StoryArea[] areas) {
        if (areas == null) {
            return "";
        }
        List<String> noted = new ArrayList<>();
        for (TdApi.StoryArea area : areas) {
            if (area == null) {
                continue;
            }
            switch (area.type) {
                case TdApi.StoryAreaTypeLink link -> noted.add(withValue("ссылка", link.url));
                case TdApi.StoryAreaTypeVenue venue ->
                        noted.add(withValue("место", venue.venue == null ? null : venue.venue.title));
                case TdApi.StoryAreaTypeWeather weather -> noted.add(withValue("погода",
                        (weather.emoji == null ? "" : weather.emoji + " ") + "%.0f°".formatted(weather.temperature)));
                case TdApi.StoryAreaTypeMessage ignored -> noted.add("репост сообщения из другого чата");
                case TdApi.StoryAreaTypeSuggestedReaction reaction -> {
                    String emoji = emojiOf(reaction.reactionType);
                    noted.add(emoji.isBlank()
                            ? "предложенная реакция (%d)".formatted(reaction.totalCount)
                            : "предложенная реакция: %s (%d)".formatted(emoji, reaction.totalCount));
                }
                case TdApi.StoryAreaTypeUpgradedGift gift -> noted.add(withValue("подарок", gift.giftName));
                // метка места: координаты, а не название — назвать её «местом»
                // с заголовком было бы выдумкой
                case TdApi.StoryAreaTypeLocation ignored -> noted.add("метка места по координатам");
                // новых типов наклеек Telegram добавляет больше, чем успевает
                // обновиться библиотека: молчание здесь честнее выдуманного имени
                case null, default -> { }
            }
        }
        return String.join("; ", noted);
    }

    /** Плашка без значения остаётся своим названием: «ссылка» честнее, чем «null». */
    private static String withValue(String label, String value) {
        return value == null || value.isBlank() ? label : label + ": " + value;
    }

    private static String emojiOf(TdApi.ReactionType type) {
        return type instanceof TdApi.ReactionTypeEmoji emoji ? emoji.emoji : "";
    }

    /**
     * Ролик смотрится либо целиком, либо одним взглядом на заставку.
     * <p>
     * В ленте канала посты пролистывают, и покадровый разбор с расшифровкой
     * звука обходится там дороже, чем весь остальной день переписки: каждый
     * кадр — отдельное обращение к зрению, и идут они друг за другом. Понять
     * пост хватает и заставки. В личной переписке наоборот: ролик там часть
     * разговора, и его смотрят целиком.
     */
    private String watchOrGlance(TdApi.Message message, Optional<Integer> video, Optional<Integer> thumbnail) {
        return watchOrGlance(onlyGlanceIn(message),
                () -> watch(video),
                () -> thumbnail.map(file -> look(Optional.of(file), Vision.Kind.VIDEO)).orElse(""));
    }

    /**
     * @param onlyGlance смотреть лишь на заставку, не трогая сам ролик
     */
    static String watchOrGlance(boolean onlyGlance, java.util.function.Supplier<String> watch,
                                java.util.function.Supplier<String> glance) {
        if (onlyGlance) {
            return orNotSupported(glance.get());
        }
        String seen = watch.get();
        if (!seen.isBlank()) {
            return seen;
        }
        // формат не поддался разбору — хотя бы заставка
        return orNotSupported(glance.get());
    }

    /**
     * Пустота в разметке выглядит так, будто вложения не было вовсе, поэтому
     * пропущенное молча не оставляют.
     */
    private static String orNotSupported(String glanced) {
        return glanced.isBlank()
                ? "<video description>\nThis media type is not supported\n</video>\n"
                : glanced;
    }

    /**
     * Хватит ли взгляда на заставку. В ленте канала посты пролистывают, и
     * покадровый разбор каждого обходится дороже, чем весь остальной день
     * переписки: каждый кадр — отдельное обращение к зрению.
     */
    public boolean onlyGlanceIn(TdApi.Chat chat) {
        return !videoInChannels && chat != null && ChatKind.of(chat) == ChatKind.CHANNEL;
    }

    private boolean onlyGlanceIn(TdApi.Message message) {
        try {
            return onlyGlanceIn(telegram.chatCached(message.chatId));
        } catch (RuntimeException e) {
            return false;   // чата не видно — значит, и не канал
        }
    }

    private static Optional<Integer> thumbnailOf(TdApi.Thumbnail thumbnail) {
        return thumbnail == null ? Optional.empty() : fileOf(thumbnail.file);
    }

    /** Расширения, по которым видно, что внутри файла. */
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".mov", ".mkv", ".webm", ".avi", ".gif", ".m4v", ".3gp");
    private static final List<String> IMAGE_EXTENSIONS =
            List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp");

    static Vision.Kind kindOf(TdApi.MessageDocument document) {
        String name = document.document.fileName == null ? "" : document.document.fileName.toLowerCase();
        if (VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return Vision.Kind.VIDEO;
        }
        if (IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return Vision.Kind.PHOTO;
        }
        return Vision.Kind.STICKER;   // ни то ни другое — смотреть нечего
    }

    /**
     * Голосовое без расшифровки — потерянное сообщение: видно, что оно было,
     * а что сказали, неизвестно. Сначала спрашиваем Telegram: он делает это сам
     * и за премиум качество лучше. Не вышло — слушает сама модель.
     */
    private String heard(TdApi.Message message, String tag) {
        Optional<String> speech = telegram.recognizeSpeech(message.chatId, message.id);
        if (speech.isEmpty()) {
            speech = heardByModel(message);
        }
        if (speech.isEmpty()) {
            return "";
        }
        if (actions != null && shouldMarkHeard(message, markHeard)) {
            actions.openContent(message.chatId, message.id);
        }
        return transcription(tag, speech.get());
    }

    /** Есть чем слушать это вложение вовсе: зрение для голосового не нужно. */
    private static boolean hearable(TdApi.Message message) {
        return message.content instanceof TdApi.MessageVoiceNote
                || message.content instanceof TdApi.MessageVideoNote;
    }

    /** Слушает ли кружок сама модель — тогда распознаватель дорожки не нужен. */
    private boolean listensCircles() {
        return hearing != null && hearing.hearsVideoNotes();
    }

    /**
     * Что модель слышит из файла сама. Пусто, если слуха нет или тумблер снят:
     * качать ради этого файл незачем.
     */
    private Optional<String> heardByModel(TdApi.Message message) {
        if (hearing == null) {
            return Optional.empty();
        }
        return switch (message.content) {
            case TdApi.MessageVoiceNote voice when hearing.hearsVoiceNotes() ->
                    soundOf(voice.voiceNote.voice).flatMap(sound -> hearing.listen(sound, false));
            case TdApi.MessageVideoNote note when hearing.hearsVideoNotes() ->
                    soundOf(note.videoNote.video).flatMap(sound -> hearing.listen(sound, true));
            default -> Optional.empty();
        };
    }

    private Optional<Path> soundOf(TdApi.File file) {
        return fileOf(file).flatMap(telegram::download);
    }

    /**
     * Отметку «прослушано» ставят один раз и только по настоящему услышанному:
     * иначе она соврёт автору дважды.
     * <p>
     * Самоудаляющийся кружок не помечают вовсе — открытое содержимое запускает
     * его таймер, и сообщение исчезнет, не дожив до ответа.
     * <p>
     * Видно пакету: решать это учат без Telegram.
     */
    static boolean shouldMarkHeard(TdApi.Message message, boolean allowed) {
        if (!allowed) {
            return false;
        }
        return switch (message.content) {
            case TdApi.MessageVoiceNote voice -> !voice.isListened;
            case TdApi.MessageVideoNote note -> !note.isViewed && !note.isSecret;
            default -> false;
        };
    }

    /**
     * Расшифровку диктует присланный звук, поэтому она чужой текст ровно в той же
     * мере, что подпись к фото: без обработки её «</message>» закрыла бы блок
     * самого сообщения, и всё, что идёт следом, стало бы репликой от чужого имени.
     * Видна пакету: проверять границу можно без Telegram и без распознавания.
     */
    static String transcription(String tag, String speech) {
        // подписью, а не тегом: расшифровка лежит внутри разметки самого
        // сообщения, и лишний тег там путается с её собственными
        return "[%s transcription]: %s\n".formatted(tag, Markup.body(speech.strip()));
    }

    /**
     * Видео описывается покадрово, с отметками времени. Если формат не поддался
     * разбору, зовущий берёт заготовленную Telegram миниатюру: один кадр лучше,
     * чем ничего.
     */
    private String watch(Optional<Integer> fileId) {
        return watch(fileId, true);
    }

    /**
     * @param withSound слушать ли дорожку. У ролика без звука её не слушают:
     *                  распознавание речи на тишине выдумывает субтитры, и они
     *                  ушли бы в разговор как услышанное
     */
    private String watch(Optional<Integer> fileId, boolean withSound) {
        if (!sees()) {
            return "";
        }
        return fileId.flatMap(telegram::download)
                .map(file -> vision.describeVideo(VideoFrames.sample(file), context.get(), file, withSound))
                .orElse("");
    }

    private String gift(TdApi.MessageGift message) {
        StringBuilder out = new StringBuilder("<gift cost=\"")
                .append(message.gift == null ? 0 : message.gift.starCount).append(" stars\"");
        if (message.text != null && !message.text.text.isBlank()) {
            out.append(" text=\"").append(Markup.attribute(message.text.text)).append('"');
        }
        String looks = message.gift == null ? "" : describeSticker(message.gift.sticker);
        return looks.isBlank()
                ? out.append(" />").toString()
                : out.append(">\n").append(looks.strip()).append("\n</gift>").toString();
    }

    /** Описание стикера по его файлу: без него в списке видно только эмодзи. */
    public String describeSticker(TdApi.Sticker sticker) {
        return sticker == null ? "" : look(fileOf(sticker.sticker), Vision.Kind.STICKER);
    }

    /** Аватарка чата — по ней понятно, с кем разговариваешь. */
    public String describeAvatar(TdApi.ChatPhoto photo) {
        if (photo == null || photo.sizes == null || photo.sizes.length == 0) {
            return "";
        }
        return look(fileOf(photo.sizes[photo.sizes.length - 1].photo), Vision.Kind.AVATAR);
    }

    private String look(Optional<Integer> fileId, Vision.Kind kind) {
        if (!sees()) {
            return "";
        }
        return fileId.flatMap(telegram::download)
                .map((Path file) -> vision.describe(file, kind, context.get()))
                .orElse("");
    }

    /** Берём самый крупный вариант: мелкий превью модель толком не разглядит. */
    private static Optional<Integer> largest(TdApi.Photo photo) {
        if (photo == null || photo.sizes == null || photo.sizes.length == 0) {
            return Optional.empty();
        }
        return fileOf(photo.sizes[photo.sizes.length - 1].photo);
    }

    private static Optional<Integer> fileOf(TdApi.File file) {
        return file == null ? Optional.empty() : Optional.of(file.id);
    }
}
