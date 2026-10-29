package ru.rainedev.raine.vision;

import it.tdlight.jni.TdApi;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.speech.ModelHearing;
import ru.rainedev.raine.speech.ModelHearing.Track;
import ru.rainedev.raine.storage.AtomicFiles;
import ru.rainedev.raine.telegram.Telegram;

/** Скачивает вложение из Telegram и передаёт его зрению или слуху. */
public final class TelegramMedia implements MediaDescriber {

    private static final Logger log = LoggerFactory.getLogger(TelegramMedia.class);

    /** Что видно, если историю достать не удалось. Текст читает модель, поэтому он английский. */
    private static final String UNREACHABLE = "This story could not be opened — it may have expired or been hidden.";

    /**
     * Куда кладут заставку, присланную байтами вместе с сообщением: файл нужен
     * только как имя для кэша описания.
     */
    private static final Path PREVIEW_DIR = Path.of(System.getProperty("java.io.tmpdir"), "raine-preview");

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

    /** До скольких секунд дотягивать отметку «прослушано»; ноль — отмечать сразу. */
    private int markHeardDelaySeconds;

    /**
     * Сообщения, которые уже помечены прослушанными. Вложение разбирают дважды:
     * сначала греют, потом рисуют разметку, — и без этой памяти отметка вместе
     * с паузой выстрелила бы повторно, уже когда собеседник ждёт ответа.
     * Мест ровно столько, сколько нужно одному ходу: бот живёт неделями.
     */
    private static final int MARKED_CAP = 512;
    private final java.util.Set<String> marked = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > MARKED_CAP;
                }
            }));

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

    /**
     * Есть ли чем слушать. Зрение для этого не нужно: голосовое и кружок
     * разбираются ушами. Без слуха предразбор качал бы файл впустую.
     */
    @Override
    public boolean hears() {
        return hearing != null && (hearing.hears(Track.VOICE) || hearing.hears(Track.CIRCLE));
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

    /**
     * @param seconds минимальное время от начала разбора до отметки «прослушано».
     *                Человек слушает запись столько, сколько она звучит, и галочка
     *                в ту же секунду выдала бы, что её не слушали вовсе
     */
    public void markHeardDelay(int seconds) {
        this.markHeardDelaySeconds = Math.max(0, seconds);
    }

    public void videoInChannels(boolean allowed) {
        this.videoInChannels = allowed;
    }

    @Override
    public String describe(TdApi.Message message) {
        if (message == null || message.content == null) {
            return "";
        }
        // без зрения разбирать нечего, и история не в счёт: её подпись и стикеры —
        // текст, а не картинка. Голосовое и кружок слушают ушами, а не глазами,
        // поэтому они разбираются и без зрения. Остальные вложения при
        // выключенном зрении остаются ровно тем, чем были: пометкой о типе
        if (!sees() && !hearable(message) && !(message.content instanceof TdApi.MessageStory)) {
            return "";
        }
        // с этого места считают, сколько длилось прослушивание: отметка
        // «прослушано» должна падать не раньше, чем запись успели бы услышать
        long startedAt = System.currentTimeMillis();
        return switch (message.content) {
            case TdApi.MessagePhoto photo -> look(largest(photo.photo), Vision.Kind.PHOTO);
            case TdApi.MessageSticker sticker -> {
                // запоминаем: без этого её нельзя ни сохранить, ни отправить
                telegram.rememberSticker(sticker.sticker);
                yield look(fileOf(sticker.sticker.sticker), Vision.Kind.STICKER);
            }
            // у подарка есть вид и цена — без них «подарок» ничего не говорит
            case TdApi.MessageGift gift -> gift(gift);
            case TdApi.MessageVoiceNote ignored -> heard(message, "voice", startedAt);
            case TdApi.MessageVideoNote videoNote ->
                    // у кружка есть и картинка, и речь, но дорога к звуку
                    // должна быть одна: через зрение он слушался бы по
                    // разрешению ролика, а тумблер кружка там не спрашивают
                    circle(message, videoNote.videoNote) + heard(message, "video_note", startedAt);
            case TdApi.MessageVideo video -> video.video == null ? ""
                    : watchOrGlance(message, Ladder.of(video), true);
            case TdApi.MessageAnimation animation -> animation.animation == null ? ""
                    : watchOrGlance(message, Ladder.of(animation.animation), true);
            // роликом, присланным файлом, каналы пользуются чаще всего: по типу
            // сообщения это документ, и без разбора имени он уходил бы в разбор
            // картинки, где из видео не видно ничего
            case TdApi.MessageDocument document -> document.document == null ? "" : switch (kindOf(document)) {
                case VIDEO -> watchOrGlance(message, Ladder.of(document.document), true);
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
     * История — не вложение: картинка, подпись и прикреплённые стикеры лежат
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
        return describeStory(story, onlyGlance, null);
    }

    /**
     * Тот же взгляд вторым проходом — ради одного вопроса.
     * <p>
     * Кэш при этом не трогают: в нём лежит общий пересказ, и ответа на вопрос в
     * нём нет. Сам пересказ от вопроса не портится: узкий ответ в кэш не пишется.
     *
     * @param question на что именно смотреть; пусто — обычный пересказ
     */
    public String describeStory(TdApi.Story story, boolean onlyGlance, String question) {
        // вслепую разбирать нечего, и жалобу на формат из пустоты не собрать:
        // ролик цел, просто глаз нет — тогда остаются подпись и стикеры
        String seen = sees() ? seen(story.content,
                file -> look(file, Vision.Kind.PHOTO, question),
                video -> storyVideo(video, onlyGlance,
                        (file, sound) -> watch(file, sound, question),
                        () -> glanceOf(video,
                                (file, isStill) -> look(file, Vision.Kind.VIDEO, question, isStill)))) : "";
        return storyBlock(story, loss(seen, story.content, sees()));
    }

    /** Файл был, ответа нет: модель отказалась смотреть или разбор не удался. */
    private static final String NO_ANSWER =
            "Looked at the picture and got no answer back — the vision model refused or the look failed. "
                    + "Say you did not get to see it instead of describing it.";

    /** История обещает картинку, а качать нечего: Telegram не прислал файл. */
    private static final String NO_FILE =
            "The story announced a picture but carried no file, so there was nothing to look at. "
                    + "Say you did not get to see it instead of describing it.";

    /**
     * Пустота, которую нельзя оставить без слова: модель дописала бы её сама.
     * <p>
     * Причины разные — разная и фраза. Файла не оказалось — винить разбор нельзя;
     * файл был, но ответа нет — тут отказ модели или сбой, и это другой разговор.
     * <p>
     * Иначе обходятся с историей, в которой смотреть было нечего: Telegram сам
     * не разобрал её содержимое. Сбоя не было, и жалоба на него была бы такой же
     * выдумкой, как дорисованный кот, — пустота здесь честнее. Вслепую тоже не
     * жалуются: там картинку не звали вовсе.
     * <p>
     * Видно пакету: подстановку проверяют без зрения и без Telegram.
     */
    static String loss(String seen, TdApi.StoryContent content, boolean hasEyes) {
        if (!hasEyes || !wasPicture(content) || (seen != null && !seen.isBlank())) {
            return seen;
        }
        return hasFile(content) ? NO_ANSWER : NO_FILE;
    }

    /** История, где смотреть было бы на что: фото или ролик. */
    private static boolean wasPicture(TdApi.StoryContent content) {
        return content instanceof TdApi.StoryContentPhoto || content instanceof TdApi.StoryContentVideo;
    }

    /** Есть ли что скачивать: файл у фото, у ролика — основной или запасной поток. */
    private static boolean hasFile(TdApi.StoryContent content) {
        return switch (content) {
            case TdApi.StoryContentPhoto photo -> largest(photo.photo).isPresent();
            case TdApi.StoryContentVideo video ->
                    !storyFile(video.video).isEmpty() || !storyFile(video.alternativeVideo).isEmpty();
            case null, default -> false;
        };
    }

    /**
     * Заставка ролика истории: один кадр вместо всего разбора.
     * <p>
     * Кадр помечают заставкой — по одному кадру о всём ролике не рассказывают,
     * и модель не должна думать, что посмотрела его.
     * <p>
     * Видно пакету: и выбор файла, и пометку учат проверять без Telegram и без
     * зрения.
     *
     * @param still    взгляд на кадр; вторым аргументом ему говорят, что это
     *                 заставка, а не просмотренный ролик
     */
    static String glanceOf(TdApi.StoryContentVideo content,
                           java.util.function.BiFunction<Optional<Integer>, Boolean, String> still) {
        return content.video == null
                ? ""
                : still.apply(thumbnailOf(content.video.thumbnail), true);
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
     * Подпись и стикеры — чужой текст, поэтому они уходят атрибутами: ни кавычек,
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
     * Стикеры на истории рисует сам клиент поверх картинки: ни в файле, ни в кадрах
     * их нет. Ссылка, погода или репост поста видны только здесь.
     * <p>
     * Наполненность полей Telegram оставляет за собой, а слово «null» в описании
     * читалось бы как часть содержания, поэтому при пустом значении называют
     * только тип стикера.
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
     *
     * @param withSound слушать ли дорожку ролика
     */
    private String watchOrGlance(TdApi.Message message, Ladder ladder, boolean withSound) {
        return watchOrGlance(onlyGlanceIn(message),
                () -> firstSeen(ladder.videos(), stream -> watch(fileOf(stream), withSound)),
                () -> glance(message, ladder.thumbnail(), ladder.mini()));
    }

    /**
     * Кружок смотрят целиком и в канале тоже: он короткий, и покадровый разбор
     * его не дороже одного взгляда. Когда кадры не извлеклись, берут заставку.
     * <p>
     * Жалобы на неподдерживаемый формат здесь нет: кружок всё равно услышали, и
     * «не поддерживается» соврало бы, что сообщение осталось непонятым.
     */
    private String circle(TdApi.Message message, TdApi.VideoNote note) {
        if (note == null) {
            return "";
        }
        Ladder ladder = Ladder.of(note);
        return watchOrGlance(false,
                () -> firstSeen(ladder.videos(), stream -> watch(fileOf(stream), false)),
                () -> glance(message, ladder.thumbnail(), ladder.mini()),
                "");
    }

    /**
     * Первый поток, в котором что-то видно. Каждый следующий стоит и
     * скачивания, и обращения к зрению на каждый кадр, поэтому до него дело
     * доходит только тогда, когда предыдущий ничего не дал.
     * Видно пакету: порядок проб проверяют без Telegram.
     */
    static String firstSeen(List<TdApi.File> streams, Function<TdApi.File, String> watch) {
        for (TdApi.File stream : streams) {
            String seen = watch.apply(stream);
            if (seen != null && !seen.isBlank()) {
                return seen;
            }
        }
        return "";
    }

    /**
     * @param onlyGlance смотреть лишь на заставку, не трогая сам ролик
     */
    static String watchOrGlance(boolean onlyGlance, Supplier<String> watch, Supplier<String> glance) {
        return watchOrGlance(onlyGlance, watch, glance, NOT_SUPPORTED);
    }

    /**
     * @param whenNothing чем заменить пустоту: для ролика это жалоба на формат,
     *                    а для кружка молчание — его всё равно услышали
     */
    static String watchOrGlance(boolean onlyGlance, Supplier<String> watch, Supplier<String> glance,
                                String whenNothing) {
        if (onlyGlance) {
            return atLeast(glance.get(), whenNothing);
        }
        String seen = watch.get();
        if (!seen.isBlank()) {
            return seen;
        }
        // формат не поддался разбору — хотя бы заставка
        return atLeast(glance.get(), whenNothing);
    }

    /**
     * Пустота в разметке выглядит так, будто вложения не было вовсе, поэтому
     * пропущенное молча не оставляют.
     */
    private static final String NOT_SUPPORTED =
            "<video description>\nThis media type is not supported\n</video>\n";

    private static String atLeast(String seen, String whenNothing) {
        return seen.isBlank() ? whenNothing : seen;
    }

    /**
     * Один кадр вместо всего ролика.
     * <p>
     * Заставку отдельным файлом ещё нужно скачать, а мелкая приходит байтами
     * вместе с сообщением: её кладут в файл с именем по чату и номеру, чтобы
     * одно и то же сообщение не раскладывало её заново, а разные сообщения не
     * писали в один файл — предразбор вложений идёт в несколько потоков.
     */
    private String glance(TdApi.Message message, TdApi.Thumbnail thumbnail, TdApi.Minithumbnail mini) {
        if (!sees()) {
            return "";   // без зрения и мелкую заставку незачем класть на диск
        }
        String seen = look(thumbnailOf(thumbnail), Vision.Kind.VIDEO, null, true);
        if (!seen.isBlank()) {
            return seen;
        }
        return miniFile(message, mini)
                .map(still -> lookAt(still, Vision.Kind.VIDEO, null, true))
                .orElse("");
    }

    /** Совсем мелкая заставка: байты уже на руках, а файл нужен как имя для кэша. */
    private static Optional<Path> miniFile(TdApi.Message message, TdApi.Minithumbnail mini) {
        if (mini == null || !worthLooking(mini.data)) {
            return Optional.empty();
        }
        Path file = PREVIEW_DIR.resolve(miniName(message.chatId, message.id, mini.data));
        try {
            AtomicFiles.writeBytes(file, mini.data);
            return Optional.of(file);
        } catch (IOException e) {
            log.debug("Заставку {} не удалось положить: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Меньше порога это не картинка, а размытое пятно: платить зрению за него
     * незачем. Порог тот же, что и у скачанного файла, — иначе одна и та же
     * мелочь проходила бы в одну дверь и не проходила в другую.
     */
    static boolean worthLooking(byte[] data) {
        return data != null && data.length >= Vision.MIN_IMAGE_BYTES;
    }

    /** Имя собирают из чата и номера: номера сообщений в каждом чате свои. */
    static String miniName(long chatId, long messageId, byte[] data) {
        return "%d-%d-mini.%s".formatted(chatId, messageId, extensionOf(data));
    }

    /** Заставка бывает и не JPEG: расширение угадывают по первым байтам. */
    private static String extensionOf(byte[] data) {
        return data.length > 3 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                ? "png"
                : "jpg";
    }

    /**
     * Ступени разбора ролика: что пробовать, когда предыдущее не дало картинки.
     * <p>
     * Основной поток бывает в HEVC, который нашему разборщику не по зубам, а
     * запасные — в другом кодеке: терять из-за одного контейнера весь ролик
     * незачем. Не поддались и они — берут заставку, которую Telegram приготовил
     * сам: один кадр лучше, чем ничего.
     * <p>
     * Видно пакету: порядок ступеней учат без Telegram и без зрения.
     *
     * @param videos    потоки в порядке пробы
     * @param thumbnail заставка отдельным файлом
     * @param mini      совсем мелкая заставка: она лежит байтами в самом сообщении
     */
    record Ladder(List<TdApi.File> videos, TdApi.Thumbnail thumbnail, TdApi.Minithumbnail mini) {

        static Ladder of(TdApi.MessageVideo message) {
            List<TdApi.File> videos = new ArrayList<>();
            add(videos, message.video == null ? null : message.video.video);
            for (TdApi.AlternativeVideo alternative : streamsOf(message.alternativeVideos)) {
                // HLS-поток не берут: это плейлист, а не файл, который можно разобрать
                add(videos, alternative == null ? null : alternative.video);
            }
            return new Ladder(List.copyOf(videos),
                    message.video == null ? null : message.video.thumbnail,
                    message.video == null ? null : message.video.minithumbnail);
        }

        static Ladder of(TdApi.Animation animation) {
            return new Ladder(one(animation.animation), animation.thumbnail, animation.minithumbnail);
        }

        static Ladder of(TdApi.Document document) {
            return new Ladder(one(document.document), document.thumbnail, document.minithumbnail);
        }

        static Ladder of(TdApi.VideoNote note) {
            return new Ladder(one(note.video), note.thumbnail, note.minithumbnail);
        }

        private static void add(List<TdApi.File> into, TdApi.File file) {
            if (file != null) {
                into.add(file);
            }
        }

        private static List<TdApi.File> one(TdApi.File file) {
            List<TdApi.File> videos = new ArrayList<>();
            add(videos, file);
            return List.copyOf(videos);
        }

        /** Наполненность массива Telegram оставляет за собой, и дыр в нём не ждут. */
        private static List<TdApi.AlternativeVideo> streamsOf(TdApi.AlternativeVideo[] alternatives) {
            return alternatives == null ? List.of() : Arrays.asList(alternatives);
        }
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
     *
     * @param startedAt когда начали разбирать вложение: по нему отмечают
     *                  «прослушано» не раньше, чем запись успели бы услышать
     */
    private String heard(TdApi.Message message, String tag, long startedAt) {
        Optional<String> speech = telegram.recognizeSpeech(message.chatId, message.id);
        boolean fresh = speech.isPresent();   // премиум-распознавание слушает запись прямо сейчас
        if (speech.isEmpty()) {
            Optional<ModelHearing.Heard> byModel = heardByModel(message);
            speech = byModel.map(ModelHearing.Heard::words);
            fresh = byModel.map(ModelHearing.Heard::fresh).orElse(false);
        }
        if (speech.isEmpty()) {
            return "";
        }
        markListened(message, startedAt, fresh);
        return transcription(tag, speech.get());
    }

    /**
     * Отметить прослушанным, выждав то, чего не хватило самому прослушиванию.
     * <p>
     * Пауза нужна, потому что галочка в ту же секунду, когда прислали запись,
     * выдаёт, что её не слушали вовсе. Готовая расшифровка из кэша паузы не
     * заслуживает: время на неё потратили раньше.
     */
    private void markListened(TdApi.Message message, long startedAt, boolean fresh) {
        if (actions == null) {
            return;
        }
        Mark mark = markOf(message, markHeard, marked, System.currentTimeMillis() - startedAt,
                markHeardDelaySeconds * 1000L, fresh);
        if (!mark.set()) {
            return;
        }
        if (!sleep(mark.waitMs())) {
            // останавливают — отметка не важнее остановки
            marked.remove(keyOf(message));
            return;
        }
        actions.openContent(message.chatId, message.id);
    }

    /**
     * @param set отмечать ли это сообщение на этот раз
     * @param waitMs сколько ещё выждать до отметки
     */
    record Mark(boolean set, long waitMs) {}

    /**
     * Отмечают один раз на сообщение: вложение разбирают дважды — сначала греют,
     * потом рисуют разметку, — и второй разбор идёт по кэшу мгновенно. Без этой
     * памяти отметка вместе с паузой выстрелила бы повторно, уже когда
     * собеседник ждёт ответа.
     * <p>
     * Видно пакету: проверять можно без Telegram.
     */
    static Mark markOf(TdApi.Message message, boolean allowed, java.util.Set<String> marked,
                       long elapsedMs, long maxMs, boolean fresh) {
        if (!shouldMarkHeard(message, allowed) || !marked.add(keyOf(message))) {
            return new Mark(false, 0);
        }
        return new Mark(true, remainingMs(elapsedMs, durationSeconds(message), maxMs, fresh));
    }

    /**
     * Сколько ещё ждать до отметки. Цель — минимальное общее время от начала
     * разбора: длинная запись стоит дольше короткой, но у потолка.
     */
    static long remainingMs(long elapsedMs, int durationSeconds, long maxMs, boolean fresh) {
        if (!fresh || maxMs <= 0) {
            return 0;
        }
        long target = Math.min(Math.max(durationSeconds * 350L, 1000L), maxMs);
        return Math.max(0, target - elapsedMs);
    }

    /** Сколько звучит запись: по ней и считают, сколько её слушают. */
    static int durationSeconds(TdApi.Message message) {
        return switch (message.content) {
            case TdApi.MessageVoiceNote voice -> voice.voiceNote == null ? 0 : voice.voiceNote.duration;
            case TdApi.MessageVideoNote note -> note.videoNote == null ? 0 : note.videoNote.duration;
            default -> 0;
        };
    }

    /** Номера сообщений повторяются в каждом чате, поэтому ключ — с чатом. */
    private static String keyOf(TdApi.Message message) {
        return message.chatId + ":" + message.id;
    }

    /** @return false, если ждать не дали: прерванный сон значит остановку */
    private static boolean sleep(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Есть чем слушать это вложение вовсе: зрение для голосового не нужно. */
    private static boolean hearable(TdApi.Message message) {
        return message.content instanceof TdApi.MessageVoiceNote
                || message.content instanceof TdApi.MessageVideoNote;
    }

    /**
     * Что модель слышит из файла сама. Пусто, если слуха нет или тумблер снят:
     * качать ради этого файл незачем.
     */
    private Optional<ModelHearing.Heard> heardByModel(TdApi.Message message) {
        if (hearing == null) {
            return Optional.empty();
        }
        return switch (message.content) {
            case TdApi.MessageVoiceNote voice when hearing.hears(Track.VOICE) ->
                    soundOf(voice.voiceNote.voice).flatMap(sound -> hearing.listenMarked(sound, Track.VOICE));
            case TdApi.MessageVideoNote note when hearing.hears(Track.CIRCLE) ->
                    soundOf(note.videoNote.video).flatMap(sound -> hearing.listenMarked(sound, Track.CIRCLE));
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
     * Видео описывается покадрово, с отметками времени.
     *
     * @param withSound слушать ли дорожку. У ролика без звука её не слушают:
     *                  распознавание речи на тишине выдумывает субтитры, и они
     *                  ушли бы в разговор как услышанное
     */
    private String watch(Optional<Integer> fileId, boolean withSound) {
        return watch(fileId, withSound, null);
    }

    /**
     * @param question взгляд ради одного вопроса: он идёт мимо кэша, где лежит
     *                 общий пересказ, и в кэш не пишется
     */
    private String watch(Optional<Integer> fileId, boolean withSound, String question) {
        if (!sees()) {
            return "";
        }
        return fileId.flatMap(telegram::download)
                .map(file -> vision.describeVideo(() -> VideoFrames.sample(file), asking(context.get(), question),
                        file, withSound, plain(question)))
                .orElse("");
    }

    /**
     * Разговор плюс вопрос, ради которого смотрят снова. Без вопроса это просто
     * разговор: взгляд по умолчанию пересказывает всё.
     * <p>
     * Видно пакету: привязку вопроса проверяют без Telegram.
     */
    static List<Message> asking(List<Message> around, String question) {
        List<Message> messages = new ArrayList<>(around);
        if (!plain(question)) {
            messages.add(Message.user("You are looking at this again, for one question. Answer only it, "
                    + "from what is really in the picture: " + question.strip()));
        }
        return messages;
    }

    /** Обычный пересказ, а не взгляд ради вопроса — только он и живёт в кэше. */
    static boolean plain(String question) {
        return question == null || question.isBlank();
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
        return look(fileId, kind, null);
    }

    /**
     * @param question взгляд ради одного вопроса: мимо кэша, где лежит общий
     *                 пересказ, и не оставляя в нём узкий ответ
     */
    private String look(Optional<Integer> fileId, Vision.Kind kind, String question) {
        return look(fileId, kind, question, false);
    }

    /**
     * @param preview заставка вместо самого файла: модель должна отличать её от
     *                просмотра, иначе расскажет о ролике то, чего в кадре не было
     */
    private String look(Optional<Integer> fileId, Vision.Kind kind, String question, boolean preview) {
        if (!sees()) {
            return "";
        }
        return fileId.flatMap(telegram::download)
                .map((Path file) -> lookAt(file, kind, question, preview))
                .orElse("");
    }

    /** Файл уже на руках: скачивать нечего, осталось только посмотреть. */
    private String lookAt(Path file, Vision.Kind kind, String question, boolean preview) {
        return vision.describe(file, kind, asking(context.get(), question), plain(question), preview);
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
