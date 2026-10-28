package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.ErrorNote;
import ru.rainedev.raine.core.Fatal;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.core.Numbers;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.telegram.StoryAudience;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;

/** Инструменты для работы с Telegram. Состав зависит от того, где Raine сейчас находится. */
public final class TelegramTools {

    private static final Logger log = LoggerFactory.getLogger(TelegramTools.class);

    /** Длиннее — уже не разговорное сообщение, а простыня. */
    private static final int MAX_MESSAGE_LENGTH = 700;

    /** Столько сообщений подряд — уже не разговор, а поток. */
    private static final int MAX_IN_A_ROW = 10;

    /**
     * Столько идентификаторов принимаем за один вызов. Правка и удаление
     * обратимы лишь частично, а список на сотню номеров — это зачистка
     * переписки, до которой легко дойти одной фразой постороннего.
     */
    private static final int MAX_IDS_IN_ONE_GO = 10;

    /** Насколько настойчиво просить разбивать длинные реплики. */
    private static final double SPLIT_NUDGE = 0.3;

    /** Больше этого числа сообщений при открытии чата не подгружается никогда. */
    private static final int HISTORY_DEPTH = 30;

    /** Сколько чатов показывать в списке. */
    private static final int CHAT_LIST_DEPTH = 30;

    private static final int SEARCH_LIMIT = 20;

    /** Больше уже не поиск, а вываливание переписки в контекст. */
    private static final int MAX_SEARCH_LIMIT = 50;

    /** Сколько сообщений показывать вокруг найденного. */
    private static final int AROUND_DEPTH = 10;

    /** Шире окно делать нельзя: контекст затопит. */
    private static final int MAX_AROUND = 25;

    /** Столько контактов показываем разом: длиннее — простыня, а не список. */
    private static final int CONTACT_LIMIT = 50;

    /** Столько строк ленты историй уходит в ответ разом: дальше — простыня. */
    static final int STORY_FEED_LIMIT = 40;

    /**
     * Столько историй лента открывает сама за один вызов. Считаются попытки, а не
     * только удачные исходы: каждая — это запрос к модели зрения, длящийся
     * секунды, и вызов мог бы ждать дольше самого разговора. Остальные она
     * открывает по списку, если сочтёт нужным.
     */
    static final int STORY_AUTO_WATCH = 2;

    /**
     * История протухла — её уже не видно. В ленте она остаётся обложкой:
     * развёрнуть её значило бы отчитаться просмотром, которого не было.
     * Видно пакету: лента сверяется с этим словом.
     */
    static final String STORY_GONE = "That story is gone — they expire after a day or two.";

    /** Длиннее превью в списке чатов не нужно. */
    private static final int PREVIEW_LENGTH = 80;

    /** Длинное голосовое никто не слушает. */
    private static final int MAX_VOICE_LENGTH = 400;

    /** Сутки — обычный срок истории; дольше её держит только премиум. */
    private static final int STORY_PERIOD = 86400;

    /**
     * Заметка о себе уходит в метаданные дневника, а ночью — в промпт роста
     * одной строкой: без разметки и без простыни в ней смысла нет.
     */
    private static final int TRAIT_KIND_LIMIT = 40;
    private static final int TRAIT_LIMIT = 160;
    private static final int TRAIT_SUBJECT_LIMIT = 40;

    /** Слова, выдающие ремарку вместо самой речи. */
    private static final List<String> NARRATION_WORDS =
            List.of("голосом", "тоном", "говорит ", "voice", "tone", "says ");

    private final Telegram telegram;
    private final TelegramActions actions;
    private final ChatScreen screen;
    private volatile boolean channelsPostingEnabled;
    private volatile long writingChatId;
    private java.nio.file.Path gallery = java.nio.file.Path.of("data/gallery");
    private java.nio.file.Path voiceDir = java.nio.file.Path.of("data/voice_messages");
    private java.util.function.Supplier<String> speechPrompt =
            () -> "Exactly what you want to say in the voice message";
    private final AntiRepeat antiRepeat;
    private ru.rainedev.raine.vision.TelegramMedia media;
    private ru.rainedev.raine.telegram.Stories storyFeed;
    private final StickerTools stickers;
    private ru.rainedev.raine.speech.VoiceGenerator voice;
    private ru.rainedev.raine.image.ImageGenerator camera;
    private PhotoOutcome photoOutcome = (chatId, ready, message) -> { };

    /** Сообщает, чем закончилась фоновая съёмка. */
    @FunctionalInterface
    public interface PhotoOutcome {
        void report(long chatId, boolean ready, String message);
    }
    private final TypingRhythm rhythm;
    private final java.util.random.RandomGenerator random = new java.util.Random();

    /** Вероятность одной опечатки на сообщение. */
    private static final double TYPO_PROBABILITY = 0.02;

    /** Насколько часто она сама замечает свою опечатку. */
    private static final double TYPO_NOTICED = 0.7;

    /** Насколько часто напоминать, что можно не только писать текстом. */
    private static final double LIVELIER_REMINDER = 0.02;

    private final ru.rainedev.raine.telegram.Lockdown lockdown;

    /**
     * Повадки из настроек: объём истории, стикеры, напоминания. Пишутся из
     * потока следителя за файлом, читаются из потока хода — без volatile
     * новый набор мог не дойти до инструментов.
     */
    private volatile ru.rainedev.raine.config.Config.Behaviour behaviour =
            new ru.rainedev.raine.config.Config.Behaviour(2000, false, true, false, true, LIVELIER_REMINDER);

    public void behaviour(ru.rainedev.raine.config.Config.Behaviour behaviour) {
        this.behaviour = behaviour;
    }

    /**
     * Чаты, открытые за этот ход. Для Telegram «открытый чат» — это состояние:
     * пока он открыт, туда идут обновления и отметки прочтения. Оставлять их
     * открытыми навсегда — значит выглядеть человеком, у которого раскрыты
     * сразу все переписки.
     */
    private final java.util.Set<Long> opened = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Ход закончен: выходим из всех чатов, куда заходили. */
    public void closeOpened() {
        writingChatId = 0;
        for (Long chatId : opened) {
            try {
                actions.stopTyping(chatId);
                actions.closeChat(chatId);
            } catch (RuntimeException e) {
                log.debug("Чат {} не закрылся: {}", chatId, e.getMessage());
            }
        }
        opened.clear();
    }

    /** Сейчас она пишет по своему порыву, а не отвечает на уведомление. */
    private java.util.function.BooleanSupplier actingOnImpulse = () -> false;

    /** Заметки о себе за этот разговор: копит их инструмент, разбирает ночью. */
    private ru.rainedev.raine.memory.TraitSignals traitSignals;

    /** Дневник — чтобы подсказать ей её же прежнюю формулировку той же черты. */
    private ru.rainedev.raine.memory.Diary traitDiary;

    /** Заметки подключаются при настройке: без копилки писать их некуда. */
    public void traitSignals(ru.rainedev.raine.memory.TraitSignals signals) {
        this.traitSignals = signals;
    }

    public void traitDiary(ru.rainedev.raine.memory.Diary diary) {
        this.traitDiary = diary;
    }

    public TelegramTools(Telegram telegram, TelegramActions actions, ChatScreen screen,
                         ru.rainedev.raine.telegram.Lockdown lockdown, AntiRepeat antiRepeat) {
        this.telegram = telegram;
        this.actions = actions;
        this.screen = screen;
        this.lockdown = lockdown;
        this.antiRepeat = antiRepeat;
        this.rhythm = new TypingRhythm(random);
        this.stickers = new StickerTools(telegram, actions);
        this.stickers.allowedTo(chatId -> allowed(telegram.chatCached(chatId)));
    }

    public StickerTools stickers() {
        return stickers;
    }

    public void actingOnImpulse(java.util.function.BooleanSupplier flag) {
        this.actingOnImpulse = flag;
    }

    /**
     * Папка снимков. Настраивается сама, независимо от генератора: готовые
     * фото в ней остаются, даже когда рисовать нечем, — и их можно выкладывать.
     */
    public void gallery(java.nio.file.Path gallery) {
        this.gallery = gallery;
    }

    /** Съёмка подключается отдельно: без ключа генерации инструмента просто не будет. */
    public void camera(ru.rainedev.raine.image.ImageGenerator camera, PhotoOutcome outcome) {
        this.camera = camera;
        this.photoOutcome = outcome;
    }

    /**
     * Снимок. Готовый файл кладётся в галерею, а отправляется отдельно —
     * так она может посмотреть на результат и переснять, если не понравился.
     */
    public Tool takePhoto(long chatId) {
        return Tool.named("take_photo")
                .describedAs("Takes a photo — a selfie, surroundings, anything. Returns a filename you can then "
                        + "send with send_telegram_message. Describe the vibe, not a complex composition.")
                .requiredString("photo_desc", "What the photo should show. Describe yourself in the third "
                        + "person (\"she\"), not as \"I\" — the camera reads a description, not a diary. "
                        + "Avoid complex composition — set the vibe instead. Example: \"She makes a playful "
                        + "selfie\". The camera only knows what you look like: to put someone else in the frame, "
                        + "name them and describe their appearance as specifically as you can. Example: "
                        + "\"Selfie with her sister: anime young female, gold eyes, white hair, "
                        + "white dress, black socks\".")
                // съёмка — действие: чат сразу показывает «загружает фото…»,
                // а сам снимок в безопасном режиме некуда отправить
                .mutating()
                .build(arguments -> {
                    String wish = arguments.path("photo_desc").asText("").strip();
                    if (wish.isEmpty()) {
                        throw new LowQualityException("Опиши, что должно быть на снимке.");
                    }
                    if (camera.isBusy()) {
                        return "You are already taking a photo. Wait until it is ready — you will be told. "
                                + "Do not start another one.";
                    }

                    boolean started = camera.takeInBackground(wish,
                            photo -> photoOutcome.report(chatId, true, photo.getFileName().toString()),
                            reason -> photoOutcome.report(chatId, false, reason));
                    if (!started) {
                        return "You are already taking a photo. Wait until it is ready.";
                    }

                    actions.uploadingPhoto(chatId);
                    log.info("Съёмка начата: {}", wish);
                    // управление возвращается сразу: очередь бывает занята десятками
                    // минут, и ждать её, замерев посреди разговора, нельзя
                    return "You started taking a photo. It takes a while — carry on talking, "
                            + "you will be notified when it is ready. Do not call take_photo again meanwhile.";
                });
    }

    /** Голос подключается отдельно: без ключа синтеза инструмента просто не будет. */
    public void voice(ru.rainedev.raine.speech.VoiceGenerator voice, java.nio.file.Path voiceDir,
                      java.util.function.Supplier<String> speechPrompt) {
        this.voice = voice;
        this.voiceDir = voiceDir;
        this.speechPrompt = speechPrompt;
    }

    /** Длительность нужна Telegram, иначе полоска голосового показывает ноль. */
    private static int secondsOf(java.nio.file.Path ogg) {
        try {
            // 24 кбит/с — тот битрейт, с которым мы кодируем
            return Math.max(1, (int) (java.nio.file.Files.size(ogg) * 8 / 24_000));
        } catch (java.io.IOException e) {
            return 1;
        }
    }

    /**
     * Голосовое сообщение. В описании инструмента важно подчеркнуть, что текст —
     * это ровно то, что будет произнесено: иначе модель пишет «Raine говорит
     * игриво...», и это зачитывается вслух.
     */
    public Tool recordAudio(long chatId) {
        return Tool.named("record_audio")
                .describedAs("Records a voice message and stores it. The text you provide is exactly what will be "
                        + "said out loud — no narration, no third person, no stage directions. Returns a filename; "
                        + "send it with send_telegram_message using audio_filename.")
                // описание параметра берётся из промпта: это часть характера,
                // а не техническая подпись — правится без пересборки
                .requiredString("audio_desc", speechPrompt.get())
                .mutating()
                .build(arguments -> {
                    String text = arguments.path("audio_desc").asText("").strip();
                    if (text.isEmpty()) {
                        throw new LowQualityException("Нечего произносить — напиши, что сказать голосом.");
                    }
                    if (text.length() > MAX_VOICE_LENGTH) {
                        throw new LowQualityException(
                                "Слишком длинно для голосового (%d символов). Скажи короче.".formatted(text.length()));
                    }
                    // ремарка вроде «говорит игривым тоном» будет зачитана вслух
                    // ровно так, как написана
                    String lower = text.toLowerCase();
                    for (String narration : NARRATION_WORDS) {
                        if (lower.contains(narration)) {
                            throw new LowQualityException(
                                    "Не описывай, как ты это говоришь — напиши только сами слова. Вместо "
                                    + "«говорит игриво: привет» просто «привет».");
                        }
                    }
                    actions.recordingVoice(chatId);
                    var recorded = voice.speak(text);
                    // не отправляем сразу: запись сохраняется, а отправку она делает
                    // отдельным шагом — так же, как со снимком
                    log.info("Голосовое записано: {}", recorded.file().getFileName());
                    return "Voice message recorded.\n\nFilename: %s\n\nSend it with send_telegram_message "
                            .formatted(recorded.file().getFileName()) + "using audio_filename.";
                });
    }

    /** Зрение появляется позже клиента, поэтому подключается отдельно. */
    public void media(ru.rainedev.raine.vision.TelegramMedia media) {
        this.media = media;
        stickers.media(media);
    }

    /** Лента историй приходит обновлениями из Telegram: без неё смотреть нечего. */
    public void storyFeed(ru.rainedev.raine.telegram.Stories storyFeed) {
        this.storyFeed = storyFeed;
    }

    /** Аватарка — по ней понятно, как выглядит собеседник. */
    public Tool chatPhoto(long chatId) {
        return Tool.simple("get_chat_photo", "Looks at the profile photo of this chat.", arguments -> {
            if (media == null || !media.sees()) {
                return "You can't see photos right now.";
            }
            return telegram.chatPhoto(chatId)
                    .map(photo -> media.describeAvatar(photo))
                    .filter(described -> !described.isBlank())
                    // без этой оговорки она заговорит о фотографии так, будто её
                    // только что прислали, а это аватарка
                    .map(described -> described + "\nThis is their profile photo. If you mention it, make clear "
                            + "you are talking about their avatar.")
                    .orElse("This chat has no profile photo.");
        });
    }

    /**
     * Название чата придумал не он: в личном чате это имя, которое человек о себе
     * написал, а название группы — то, что дал ему админ. Описание инструмента
     * модель читает как указание, поэтому название входит в него одной строкой и
     * без чужой разметки. Видно пакету: проверять можно без Telegram.
     */
    static String openingDescription(String rawTitle) {
        String title = Markup.attribute(rawTitle);
        return title.isEmpty()
                ? "Opens the chat from the notification and shows recent messages."
                : "Open \"" + title + "\" chat. Use this if you'd like to reply or see messages.";
    }

    /**
     * Открывает чат: показывает переписку и подменяет набор инструментов на те,
     * что доступны внутри этого чата.
     *
     * @param toolbox набор, который будет дополнен после открытия
     */
    public Tool open(long chatId) {
        // в описании — имя чата: по нему видно, кто написал, ещё до открытия
        String title = "";
        try {
            title = telegram.chatCached(chatId).title;
        } catch (RuntimeException e) {
            log.debug("Название чата {} недоступно", chatId);
        }
        return Tool.named("open")
                .describedAs(openingDescription(title))
                .buildContextual((arguments, addTool) -> {
            writingChatId = 0;
            TdApi.Chat chat = telegram.chat(chatId);
            if (!allowed(chat)) {
                // проверка и здесь, а не только на входящих: найдя человека поиском,
                // она открыла бы чат по идентификатору и написала бы в обход запрета
                log.info("Чат {} вне круга общения — не открываю", chatId);
                return "No such chat.";
            }
            actions.setOnline(true);   // зашла в Telegram — это видно собеседнику
            actions.openChat(chatId);
            opened.add(chatId);

            List<TdApi.Message> history = recentEnough(telegram.history(chatId, HISTORY_DEPTH));
            var view = new MessageFormatter.ChatView(telegram.myId(), chat.lastReadInboxMessageId);
            ChatScreen.Screen rendered = screen.render(chat, history, view);

            actions.markRead(chatId, history.stream().mapToLong(message -> message.id).toArray());

            // свои прошлые реплики — чтобы не повторяться и после перезапуска
            history.stream()
                    .filter(message -> message.senderId instanceof TdApi.MessageSenderUser sender
                            && sender.userId == telegram.myId())
                    .filter(message -> message.content instanceof TdApi.MessageText)
                    .forEach(message -> antiRepeat.remember(((TdApi.MessageText) message.content).text.text));

            // пустой чат — это человек, с которым она ещё не говорила ни разу.
            // Заговаривать с незнакомыми первой можно разрешить настройкой,
            // но по умолчанию это выключено: слишком легко написать не туда
            boolean stranger = history.isEmpty() && rendered.kind() == ChatKind.DM
                    && !lockdown.isOwner(chat);
            if (stranger && !behaviour.writeToNewPeople()) {
                log.info("Чат {} пуст, а писать незнакомым не разрешено", chatId);
                return "This chat is empty — you have never talked to this person. "
                        + "You cannot write to them. Go back to a chat you already have.";
            }

            addTool.accept(searchMessages(chatId, view));
            addTool.accept(messagesAround(chatId, view));
            addWritingTools(rendered.kind(), chatId, addTool);
            addTool.accept(chatPhoto(chatId));
            addTool.accept(react(chatId));
            if (behaviour.stickers()) {
                addTool.accept(stickers.save());
            }
            addTool.accept(forward(chatId));
            addTool.accept(blockChat(chatId));
            if (stranger) {
                // здесь ещё ничего не сказано: без опоры на разговор легко
                // написать что-то невпопад или не тому
                return rendered.text() + "\n\nThis chat is empty! Only proceed if you looked up a @username "
                        + "and it led you here. Write only what you have to say yourself. If you then go back "
                        + "to the original chat, remember you have already sent a message here.";
            }
            return rendered.text();
        });
    }

    void addWritingTools(ChatKind kind, long chatId, java.util.function.Consumer<Tool> addTool) {
        writingChatId = chatId;
        if (kind == ChatKind.CHANNEL) {
            if (channelsPostingEnabled) {
                addTool.accept(postToChannel(chatId));
            }
            return;
        }
        addTool.accept(send(chatId));
        if (voice != null && voice.isAvailable()) {
            addTool.accept(recordAudio(chatId));
        }
        if (camera != null && camera.isAvailable()) {
            addTool.accept(takePhoto(chatId));
        }
        if (behaviour.stickers()) {
            addTool.accept(stickers.send(chatId));
        }
        addTool.accept(editMessage(chatId));
        addTool.accept(deleteMessage(chatId));
    }

    /** Разрешение владельца при запуске; само по себе не даёт прав в Telegram. */
    public void channelsPosting(boolean enabled) {
        channelsPostingEnabled = enabled;
    }

    /** Один текстовый пост в уже открытый канал, без повадок разговорной отправки. */
    public Tool postToChannel(long chatId) {
        return postToChannel(chatId, () -> telegram.chatCached(chatId), id -> telegram.confirmedId(id));
    }

    Tool postToChannel(long chatId, java.util.function.Supplier<TdApi.Chat> chat,
                       java.util.function.LongUnaryOperator confirmedId) {
        return Tool.named("post_to_channel")
                .describedAs("Publishes one text-only post to the currently opened existing channel. "
                        + "One call = one post, at most 4096 UTF-16 characters. Telegram may refuse publication. "
                        + "If delivery is unconfirmed, check the channel and do not duplicate the post.")
                .requiredString("text", "Non-blank post text, at most 4096 UTF-16 characters")
                .mutating()
                .build(arguments -> {
                    if (!channelsPostingEnabled) {
                        return "Channel posting is disabled. Nothing sent.";
                    }
                    // Набор сохраняет старую тулзу при переходе из канала в личку.
                    if (writingChatId != chatId) {
                        return "Open this channel before posting. Nothing sent.";
                    }
                    var value = arguments == null ? null : arguments.get("text");
                    if (value == null || !value.isTextual() || value.textValue().isBlank()
                            || value.textValue().length() > 4096) {
                        return "Text must be a non-blank string of at most 4096 UTF-16 characters. Nothing sent.";
                    }
                    try {
                        TdApi.Chat target = chat.get();
                        if (target == null || target.id != chatId || ChatKind.of(target) != ChatKind.CHANNEL) {
                            return "This is not the opened channel. Nothing sent.";
                        }
                        if (!allowed(target)) {
                            return "This channel is outside the allowed circle. Nothing sent.";
                        }
                    } catch (RuntimeException e) {
                        Fatal.check(e);
                        return channelPostingError(chatId,
                                "Channel check failed before sending. Nothing sent.", e.getMessage());
                    }

                    long temporaryId;
                    try {
                        temporaryId = actions.sendMessage(chatId, value.textValue(), null);
                    } catch (RuntimeException e) {
                        Fatal.check(e);
                        // Таймаут не доказывает, что Telegram не принял запрос.
                        return channelPostingError(chatId,
                                "Send failed before acceptance was confirmed. Delivery is unknown; "
                                        + "check the channel and do not duplicate the post.", e.getMessage());
                    }
                    if (temporaryId == 0) {
                        return channelPostingError(chatId,
                                "Delivery is unknown; check the channel and do not duplicate the post.",
                                "Telegram returned no message id.");
                    }
                    long finalId;
                    try {
                        finalId = confirmedId.applyAsLong(temporaryId);
                    } catch (RuntimeException e) {
                        Fatal.check(e);
                        return channelPostingError(chatId,
                                "Post accepted, but confirmation failed. Delivery is unknown; "
                                        + "check the channel and do not duplicate the post.", e.getMessage());
                    }
                    if (finalId == 0) {
                        return channelPostingError(chatId, "Telegram reported that the post failed.",
                                "The post was not published.");
                    }
                    if (finalId == temporaryId) {
                        return "Post accepted, but delivery is not yet confirmed (temporary message_id="
                                + temporaryId + "). Check the channel; do not duplicate the post.";
                    }
                    return "Post published. message_id=" + finalId;
                });
    }

    private static String channelPostingError(long chatId, String status, String reason) {
        String note = ErrorNote.of(reason);
        log.warn("Публикация в канале {}: {} {}", chatId, status, note);
        return status + " " + note;
    }

    /**
     * Отправка сообщения. Живой разговор состоит из коротких реплик,
     * поэтому простыня отклоняется с просьбой разбить её на части.
     * <p>
     * Цитирование по умолчанию не ставится: в переписке отвечают обычным
     * сообщением, а цитата нужна, только когда разговор ушёл вперёд и надо
     * показать, к чему относится реплика.
     */
    public Tool send(long chatId) {
        // счётчик живёт вместе с открытым чатом: в новом разговоре счёт заново
        int[] inARow = {0};
        long[] lastQuoted = {0};
        return Tool.named("send_telegram_message")
                .describedAs("Sends one short message to the currently opened chat. "
                        + "One call = one message. Split longer thoughts into several calls.")
                .optionalString("text", "Text of the message. May be left out if you attach a photo or a voice "
                        + "message.")
                .optionalString("allow_typos", "Optional. Set to \"false\" for a message where a typo would be "
                        + "out of place. Defaults to true.")
                .optionalString("photo_filename", "Optional. Filename returned by take_photo — the photo will be "
                        + "sent with this text as its caption.")
                .optionalString("audio_filename", "Optional. Filename returned by record_audio — the voice message "
                        + "will be sent instead of text.")
                .optionalString("reply_to_message_id",
                        "Optional. Quote a specific earlier message by its message_id. Leave empty for a normal "
                                + "reply — quoting every message looks unnatural in a live conversation.")
                .mutating()
                .build(arguments -> {
                    String text = arguments.path("text").asText("").replace("\r", "").strip();
                    String photoName = arguments.path("photo_filename").asText("").strip();
                    String audioName = arguments.path("audio_filename").asText("").strip();

                    // «печатает…» держится всё время работы инструмента: пока считается
                    // вектор и грузится файл, собеседник иначе видит пустоту
                    try (TypingIndicator ignored = TypingIndicator.typing(actions, chatId)) {

                    if (text.isEmpty() && photoName.isEmpty() && audioName.isEmpty()) {
                        throw new LowQualityException("Пустое сообщение отправлять незачем — напиши, что хотела.");
                    }
                    if (!photoName.isEmpty() && !audioName.isEmpty()) {
                        return "You cannot attach a photo and a voice message at once.";
                    }
                    if (!audioName.isEmpty() && !text.isEmpty()) {
                        // у голосового не бывает подписи: либо голос, либо текст
                        return "A voice message cannot carry text. Send them separately.";
                    }
                    if (inARow[0] > MAX_IN_A_ROW) {
                        // предупреждения она перестаёт замечать — дальше только отказ
                        return "Too many messages in a row. Stop and let them answer.";
                    }
                    if (text.length() > MAX_MESSAGE_LENGTH) {
                        throw new LowQualityException(
                                "Слишком длинно для переписки (%d символов). Разбей на несколько коротких сообщений."
                                        .formatted(text.length()));
                    }
                    // чем длиннее реплика, тем выше шанс попросить разбить её на части.
                    // мягкий отказ работает лучше жёсткого предела: короткие сообщения
                    // становятся привычкой, а не исключением
                    // придираемся, только если это единственная отправка за шаг:
                    // когда она и так шлёт несколько реплик подряд, придирка ни к чему
                    if (!text.contains("```")
                            && ru.rainedev.raine.core.CurrentStep.countOf("send_telegram_message") <= 1
                            && random.nextDouble() < Math.clamp((text.length() - 50) / 200.0, 0, 1) * SPLIT_NUDGE) {
                        throw new LowQualityException("""
                                Разбей ответ на короткие отдельные сообщения. Например:
                                - ахахаха
                                - ты смешной
                                - научишь так же?""");
                    }
                    Long quotedCheck = quotedMessageId(arguments);
                    if (quotedCheck != null && quotedCheck == lastQuoted[0]) {
                        // на одно и то же сообщение дважды подряд не отвечают
                        quotedCheck = null;
                    } else if (quotedCheck != null) {
                        lastQuoted[0] = quotedCheck;
                    }
                    if (quotedCheck != null && telegram.message(chatId, quotedCheck).isEmpty()) {
                        // сообщение из другого чата: модель путает чаты, а цитата
                        // из чужой переписки — это уже утечка личного
                        log.warn("Попытка процитировать сообщение {} не из этого чата", quotedCheck);
                        return "That message is not from this chat. Quote only messages you see here, "
                                + "or leave reply_to_message_id empty.";
                    }

                    // снимок уходит с первой строкой в подписи, остальные строки —
                    // отдельными сообщениями следом, как обычная реплика
                    if (!photoName.isEmpty()) {
                        if (!isPlainFilename(photoName)) {
                            return "Filename must be a plain name: no path, no drive letter.";
                        }
                        java.nio.file.Path photo = fileInside(gallery, photoName);
                        if (photo == null) {
                            return "There is no such photo. Take one with take_photo first.";
                        }
                        List<String> lines = linesOf(text);
                        try (TypingIndicator uploading = TypingIndicator.uploadingPhoto(actions, chatId)) {
                            actions.sendPhoto(chatId, photo, lines.isEmpty() ? "" : lines.getFirst());
                        }
                        log.info("Фото отправлено в чат {}: {}", chatId, photoName);
                        for (String rest : lines.subList(Math.min(1, lines.size()), lines.size())) {
                            rhythm.sleepBefore(rest.length(), () -> actions.typing(chatId));
                            actions.sendMessage(chatId, rest.replace("—", "-"), null);
                        }
                        inARow[0]++;
                        return "Photo sent.";
                    }

                    if (!audioName.isEmpty()) {
                        if (!isPlainFilename(audioName)) {
                            return "Filename must be a plain name: no path, no drive letter.";
                        }
                        java.nio.file.Path recorded = fileInside(voiceDir, audioName);
                        if (recorded == null) {
                            return "There is no such voice message. Record one with record_audio first.";
                        }
                        try (TypingIndicator recording = TypingIndicator.recordingVoice(actions, chatId)) {
                            actions.sendVoice(chatId, recorded, secondsOf(recorded));
                        }
                        log.info("Голосовое отправлено в чат {}: {}", chatId, audioName);
                        return "Voice message sent.";
                    }

                    maybeSuggestSomethingLivelier();
                    antiRepeat.check(text);

                    boolean allowTypos = !"false".equalsIgnoreCase(arguments.path("allow_typos").asText("true"));
                    Long quoted = quotedCheck;

                    long lastSent = 0;
                    String lastText = text;

                    // переносы строк внутри одной реплики — это отдельные сообщения
                    for (String line : linesOf(text)) {
                        // модели пишут длинное тире, а с клавиатуры набирают дефис
                        String piece = line.replace("—", "-");

                        Typos.Slip slip = allowTypos
                                ? Typos.maybeAdd(piece, TYPO_PROBABILITY, random)
                                : new Typos.Slip(piece, "");

                        actions.typing(chatId);
                        rhythm.sleepBefore(slip.text().length(), () -> actions.typing(chatId));
                        // номер, который отдаётся модели, — настоящий: по временному
                        // не поправить и не удалить только что отправленное
                        long temporary = actions.sendMessage(chatId, slip.text(), quoted);
                        long sent = telegram.confirmedId(temporary);
                        if (sent == 0) {
                            return "The message could not be sent. Something is wrong with the connection "
                                    + "or the account.";
                        }
                        quoted = null;   // цитата уместна только у первого сообщения
                        log.info("Отправлено в чат {}: {}", chatId, slip.text());

                        if (slip.happened()) {
                            fixTypo(chatId, sent, slip, piece);
                        }
                        lastSent = sent;
                        // ей сообщается то, что она хотела сказать, а не то, что вышло
                        // из-под руки: опечатку замечает и исправляет рука, не голова.
                        // Пока в ответе стоял испорченный текст, она видела в нём ошибку
                        // и правила сообщение сама — уже поверх дописанного «*слово»,
                        // и выходило два исправления одного промаха
                        lastText = piece;
                    }
                    // считаем вызовы, а не строки: реплика, разбитая на три части,
                    // остаётся одной репликой
                    inARow[0]++;
                    return sendResult(inARow[0], lastSent, lastText);
                    }
                });
    }

    /** Пустые строки внутри реплики отправлять незачем. */
    private static List<String> linesOf(String text) {
        return java.util.Arrays.stream(text.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /**
     * История чата ограничивается объёмом текста, а не числом сообщений.
     * Тридцать длинных постов из канала — это уже перебор контекста, а тридцать
     * «ок» — наоборот, слишком мелкий кусок разговора.
     *
     * @param history от старых к свежим
     */
    private List<TdApi.Message> recentEnough(List<TdApi.Message> history) {
        int budget = behaviour.chatHistoryLength();
        int taken = 0;
        int from = history.size();
        while (from > 0) {
            taken += MessageFormatter.contentOf(history.get(from - 1).content).length();
            from--;
            if (taken >= budget) {
                break;
            }
        }
        return history.subList(from, history.size());
    }

    /** Реакция — способ откликнуться, не заводя разговор. */
    public Tool react(long chatId) {
        return Tool.named("react_with_emoji")
                .describedAs("Reacts to a message with an emoji. A subtle way to acknowledge something without "
                        + "sending a full reply. Allowed: " + String.join(" ", Emoji.ALLOWED))
                .requiredInteger("message_id", "Id of the message to react to")
                .requiredString("emoji", "One emoji from the allowed list")
                .mutating()
                .build(arguments -> {
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String wanted = Emoji.normalize(arguments.path("emoji").asText(""));
                    if (wanted.isEmpty()) {
                        log.info("Реакция не поставлена: эмодзи не разобрано ({})",
                                arguments.path("emoji").asText(""));
                        return "Provide an emoji to react with.";
                    }

                    // сначала — есть ли вообще такое сообщение. Промах по номеру
                    // выглядел как «здесь нельзя реагировать», она делала вывод,
                    // что реакции сломаны, и неделями сообщала об этом как о баге
                    if (telegram.message(chatId, messageId).isEmpty()) {
                        log.info("Реакция не поставлена: сообщения {} нет в чате {}", messageId, chatId);
                        return "There is no message with id " + messageId + " in this chat. Look at the "
                                + "message_id values you actually see here and pick one of those.";
                    }

                    // берём ту форму эмодзи, которую называет сам Telegram: у одних
                    // реакций есть невидимый вариационный селектор, у других нет,
                    // и набор отличается от чата к чату
                    List<String> available = telegram.availableReactions(chatId, messageId);
                    String exact = available.stream()
                            .filter(candidate -> Emoji.normalize(candidate).equals(wanted))
                            .findFirst()
                            .orElse(null);

                    // пустой список — не запрет: Telegram отдаёт набор не всегда
                    // и не сразу, а в личной переписке реакции есть всегда.
                    // Раньше на это отвечали отказом, и «реакции не работают»
                    // тянулось неделями
                    if (exact == null && available.isEmpty()) {
                        log.info("Набор реакций для сообщения {} пуст — пробую поставить {} как есть",
                                messageId, wanted);
                        exact = wanted;
                    }
                    if (exact == null) {
                        // без этой строки «реакции иногда не работают» не расследовать:
                        // отказ уходит модели, а в журнале не остаётся ничего
                        log.info("Реакция {} недоступна для сообщения {}; здесь можно: {}",
                                wanted, messageId, String.join(" ", available));
                        return "The reaction " + wanted + " isn't available for this message. Allowed here: "
                                + String.join(" ", available) + ". Pick one of those, or just skip reacting.";
                    }

                    try {
                        actions.react(chatId, messageId, exact);
                    } catch (RuntimeException e) {
                        // отдельные сообщения не принимают реакции по своим причинам.
                        // Важно, чтобы она не решила, что инструмент сломан вообще
                        log.info("Реакция на сообщение {} не прошла: {}", messageId, e.getMessage());
                        String reason = e.getMessage() == null ? "" : e.getMessage().toUpperCase();
                        if (reason.contains("REACTION")) {
                            // здесь их и правда нельзя — но узнали мы это только сейчас
                            return "Reactions are switched off in this chat, so nobody can react here. "
                                    + "Nothing is broken: just skip reacting and carry on.";
                        }
                        return "This particular message did not accept the reaction. Happens with some "
                                + "messages; nothing is broken. Skip it and carry on.";
                    }
                    log.info("Реакция {} на сообщение {}", exact, messageId);
                    return "Reaction " + exact + " added.";
                });
    }

    /** Список чатов — как экран мессенджера: видно, где есть непрочитанное. */
    public Tool chatList() {
        return Tool.simple("get_telegram_chats", "Shows your Telegram chats with unread counts.", arguments -> {
            // от старых к свежим: список разбирается сверху вниз, и наверху
            // должно оказаться то, до чего иначе не дойдут руки
            List<TdApi.Chat> chats = new java.util.ArrayList<>(telegram.chats(CHAT_LIST_DEPTH));
            java.util.Collections.reverse(chats);

            // чат, с которым нельзя заговорить, в списке только мешает
            chats.removeIf(chat -> !allowed(chat));

            if (actingOnImpulse.getAsBoolean()) {
                // весь смысл порыва — вернуться к заглохшим разговорам. Про те,
                // где есть непрочитанное, она и так узнает из уведомлений
                chats.removeIf(chat -> chat.unreadCount > 0);
            }

            StringBuilder out = new StringBuilder(
                    "You are looking at your Telegram main screen. You see these chats:\n<chats>\n");
            for (TdApi.Chat chat : chats) {
                out.append("<chat id=\"").append(chat.id).append("\" title=\"")
                        .append(Markup.attribute(chat.title))
                        .append("\" kind=\"").append(kindOf(chat)).append('"');
                if (chat.unreadCount > 0) {
                    out.append(" unread_count=\"").append(chat.unreadCount).append('"');
                }
                // по одному названию не выбрать, кому написать: превью показывает,
                // о чём там речь и как давно
                String preview = previewOf(chat);
                if (!preview.isEmpty()) {
                    out.append('>').append(preview).append("</chat>\n");
                } else {
                    out.append("/>\n");
                }
            }
            return out.append("</chats>").toString();
        });
    }

    /** Поиск человека или чата — так завязывается разговор с тем, кому ещё не писала. */
    public Tool searchChats() {
        return Tool.named("search_chats")
                .describedAs("Finds a person or chat by name or @username. Searches your contacts, your existing "
                        + "chats and public channels at once. Use it before starting a conversation with someone "
                        + "new — if the chat already exists, continue there instead.")
                .requiredString("query", "Person's name, chat title or @username")
                .build(arguments -> {
                    String query = arguments.path("query").asText("").strip();
                    if (query.isEmpty()) {
                        return "Provide a name or @username to search for.";
                    }
                    Telegram.Found found = telegram.searchChats(query, SEARCH_LIMIT);
                    return foundAnswer(query, found, this::allowed);
                });
    }

    /**
     * Ответ поиска: наружу выходят только чаты из круга общения.
     * <p>
     * Список чатов их прячет, открытие чата проверяет круг, но поиск обходил
     * оба: название и идентификатор человека вне круга показывались тому, с кем
     * заговорить разрешено. Пустой итог отвечает той же фразой, что и вообще
     * ничего не найденный поиск — по ответу не понять, что такой чат есть.
     */
    static String foundAnswer(String query, Telegram.Found found,
                              java.util.function.Predicate<TdApi.Chat> inCircle) {
        List<TdApi.Chat> mine = found.mine().stream().filter(inCircle).toList();
        List<TdApi.Chat> elsewhere = found.elsewhere().stream().filter(inCircle).toList();
        if (mine.isEmpty() && elsewhere.isEmpty()) {
            return nothingFound(query);
        }
        // разделено нарочно: со своими разговор продолжают с того места,
        // где он встал, а к чужим приходят с нуля — это разные ситуации
        StringBuilder out = new StringBuilder();
        appendChats(out, "chats_you_are_already_in", mine);
        appendChats(out, "chats_that_do_not_know_you", elsewhere);
        return out.toString();
    }

    private static void appendChats(StringBuilder out, String tag, List<TdApi.Chat> chats) {
        if (chats.isEmpty()) {
            return;
        }
        out.append('<').append(tag).append(">\n");
        for (TdApi.Chat chat : chats) {
            out.append("<chat id=\"").append(chat.id)
                    .append("\" title=\"").append(Markup.attribute(chat.title))
                    .append("\" kind=\"").append(kindOf(chat))
                    .append("\"/>\n");
        }
        out.append("</").append(tag).append(">\n");
    }

    /** Тип важен: с человеком можно заговорить, а в канал не напишешь. */
    private static String kindOf(TdApi.Chat chat) {
        return switch (ChatKind.of(chat)) {
            case DM -> "person";
            case GROUP -> "group";
            case CHANNEL -> "channel";
        };
    }

    /** Кто и что написал последним, и когда — как в списке чатов обычного клиента. */
    private String previewOf(TdApi.Chat chat) {
        if (chat.lastMessage == null) {
            return "";
        }
        long senderId = chat.lastMessage.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
        String preview = (telegram.nameOf(senderId) + ": "
                + ru.rainedev.raine.phone.MessageFormatter.contentOf(chat.lastMessage.content)).replace("\n", " ");
        if (preview.length() > PREVIEW_LENGTH) {
            preview = preview.substring(0, 30) + "..." + preview.substring(preview.length() - 30);
        }
        return Markup.body(preview) + " (" + ru.rainedev.raine.phone.TimeAgo.of(chat.lastMessage.date) + ")";
    }

    /** Свои контакты — список людей, которых она знает по именам. */
    public Tool contactList() {
        return Tool.simple("get_contacts", "Shows people saved in your contacts.", arguments -> {
            List<TdApi.User> contacts = telegram.contacts(CONTACT_LIMIT);
            if (contacts.isEmpty()) {
                return "Your contact list is empty.";
            }
            StringBuilder out = new StringBuilder("<contacts>\n");
            for (TdApi.User user : contacts) {
                out.append("<contact user_id=\"").append(user.id).append("\" name=\"")
                        .append(Markup.attribute((user.firstName + " " + user.lastName).strip())).append("\"/>\n");
            }
            return out.append("</contacts>").toString();
        });
    }

    /**
     * Лента историй: кто сейчас что выложил — и что правда видно на свежих.
     * <p>
     * Наружу выходят только чаты из круга общения: иначе список рассказал бы,
     * кто именно выложил историю, тому, с кем говорить не разрешено.
     * <p>
     * Несколько первых непросмотренных открываются здесь же: обложка ничего не
     * рассказывает, и без просмотра оставалось бы выбирать — молчать о чужой
     * жизни или выдумывать её.
     */
    public Tool viewStories() {
        return Tool.simple("view_stories",
                "Shows whose stories are up right now and opens the freshest unseen ones for you: what is really "
                        + "in an opened story is written out under the list. A line with an unseen count is still "
                        + "a cover — open it with watch_story, taking chat_id and first_story_id from the list. "
                        + "A line with unseen=\"0\" has been watched through already. Opening a story is counted "
                        + "to its author as a view.",
                arguments -> {
                    if (storyFeed == null) {
                        return "Stories are turned off.";
                    }
                    return feedOf(storyFeed.feed(), this::cachedChat, this::allowed, this::watch,
                            media != null && media.sees());
                });
    }

    /** Чат по номеру; пусто — чата не видно, и его истории в ленту не идут. */
    private java.util.Optional<TdApi.Chat> cachedChat(long chatId) {
        try {
            return java.util.Optional.ofNullable(telegram.chatCached(chatId));
        } catch (RuntimeException e) {
            log.debug("Чат {} не достать: {}", chatId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Лента из записей Telegram: строка на каждый чат плюс до
     * {@code STORY_AUTO_WATCH} открытий. Цикл вынесен из тела тулзы, чтобы
     * лимит проверяли настоящим прогоном, а не числом в тесте: чаты, круг
     * общения и открытие приходят функциями.
     *
     * @param chats     чем достать чат по номеру; пусто — чата не видно
     * @param permitted чем отсеять тех, кому показывать нечего
     * @param opener    чем открыть историю; возвращает то, что в ней разглядели
     * @param hasEyes   есть чем смотреть: вслепую просмотр не ставят
     */
    static String feedOf(List<ru.rainedev.raine.telegram.Stories.Entry> entries,
                         java.util.function.Function<Long, java.util.Optional<TdApi.Chat>> chats,
                         java.util.function.Predicate<TdApi.Chat> permitted,
                         java.util.function.BiFunction<TdApi.Chat, Integer, String> opener, boolean hasEyes) {
        List<String> lines = new java.util.ArrayList<>();
        List<String> seen = new java.util.ArrayList<>();
        // лимит считает попытки, а не успехи: иначе лента, где половина историй
        // протухла, открывала бы за один вызов каждую непросмотренную
        int attempts = 0;
        for (ru.rainedev.raine.telegram.Stories.Entry entry : entries) {
            TdApi.Chat chat = chats.apply(entry.chatId()).orElse(null);
            if (chat == null || !permitted.test(chat)) {
                continue;
            }
            lines.add(storyLine(entry, chat.title, kindOf(chat)));
            // открывают только там, где есть глаза: вслепую просмотр засчитали бы
            // автору, а за ним ничего не стоит
            if (entry.unseen() > 0 && attempts < STORY_AUTO_WATCH && hasEyes) {
                attempts++;
                String looked;
                try {
                    looked = opener.apply(chat, entry.first());
                } catch (RuntimeException e) {
                    // одна не открылась — лента остаётся обложками: список без
                    // одной истории лучше отказа всей ленты
                    log.debug("Историю {} чата {} не разглядела: {}", entry.first(), chat.id, e.getMessage());
                    continue;
                }
                if (worthTelling(looked)) {
                    seen.add(watchedOf(chat.id, entry.first(), chat.title, looked));
                }
            }
        }
        return feedOf(lines, seen);
    }

    /**
     * Ответ ленты из готовых строк. Telegram пополняет её весь день, и список
     * мог бы разрастись до сотен чатов — тогда он занял бы полконтекста.
     * Показываем начало, а остаток называем вслух: молчаливый обрезок модель
     * прочла бы как «больше никого нет». Открытые истории названы здесь же:
     * без этого слова обложку пересказывают по памяти, будто её смотрели.
     * Видно пакету: склейку проверяют без Telegram.
     */
    static String feedOf(List<String> lines, List<String> seen) {
        if (lines.isEmpty()) {
            return "No one has stories up right now.";
        }
        int shown = Math.min(STORY_FEED_LIMIT, lines.size());
        String watched = seen.isEmpty()
                ? "Nothing was opened here just now. A line with unseen=\"0\" is watched through: opening "
                        + "one again would fetch nothing new. Anyone with an unseen count is still a cover — "
                        + "what it shows has not been seen, so before saying what a story shows, watch it "
                        + "with watch_story or say you have not looked."
                : "These were opened just now, and this is what is really in them:\n"
                        + String.join("", seen)
                        + "The ids named there have already been seen. If a detail is missing, ask watch_story "
                        + "again with a question instead of describing them from memory. A line with "
                        + "unseen=\"0\" is watched through as well; anyone else there is still a cover: "
                        + "nothing of theirs has been seen. Say you have not looked, or open one with "
                        + "watch_story.";
        String answer = "These people have stories right now:\n<stories>\n"
                + String.join("", lines.subList(0, shown)) + "</stories>\n" + watched;
        return shown == lines.size()
                ? answer
                : answer + "\nThe list is cut short: %d more are up but not shown here."
                        .formatted(lines.size() - shown);
    }

    /**
     * Развёрнутая история в ответе ленты: чья она и что в ней видно. Номер рядом
     * с чатом нужен, чтобы она различала, что уже смотрела: без этого второй
     * взгляд берётся из памяти и дорисовывает картинку.
     * <p>
     * Видно пакету: разметку проверяют без Telegram.
     */
    static String watchedOf(long chatId, int storyId, String title, String seen) {
        return "<watched chat_id=\"" + chatId + "\" story_id=\"" + storyId
                + "\" title=\"" + Markup.attribute(title) + "\">\n" + seen.strip() + "\n</watched>\n";
    }

    /**
     * Стоит ли показывать историю развёрнуто. Протухшую не стоят: лента
     * отчиталась бы просмотром, за которым ничего не было, а обложка и так
     * скажет, что человек что-то выкладывал.
     * <p>
     * Видно пакету: решение проверяют без Telegram.
     */
    static boolean worthTelling(String seen) {
        return !seen.equals(STORY_GONE);
    }

    /** Строка ленты. Видно пакету: круг общения и разметку проверяют без Telegram. */
    static String storyLine(ru.rainedev.raine.telegram.Stories.Entry entry, String title, String kind) {
        StringBuilder out = new StringBuilder("<story chat_id=\"").append(entry.chatId())
                .append("\" title=\"").append(Markup.attribute(title))
                .append("\" kind=\"").append(kind)
                .append("\" total=\"").append(entry.total())
                .append("\" unseen=\"").append(entry.unseen()).append('"');
        // непросмотренные важнее прочих: смотреть начинают с них. Номер дают
        // только им: у просмотренного до конца открывать нечего
        if (entry.unseen() > 0) {
            out.append(" first_story_id=\"").append(entry.first()).append('"');
        }
        return out.append("/>\n").toString();
    }

    /**
     * Просмотр одной истории. Лента открывает их тем же путём, поэтому тело
     * одно: в двух копиях закрытие истории и отметка в логе разъехались бы.
     */
    public Tool watchStory() {
        return Tool.named("watch_story")
                .describedAs("Watches one story: opens it, looks at the photo or the video, reads the caption and "
                        + "anything attached to it. The author sees that you have watched it. With a question it "
                        + "becomes a closer look: the same picture is examined again and answers only that, so a "
                        + "detail the first pass missed is worth asking for.")
                .requiredInteger("chat_id", "Chat id of the person, from view_stories")
                .requiredInteger("story_id", "Story id, from view_stories")
                .optionalString("question", "What exactly to look for — «what does the sign say», «how many "
                        + "people are there». Leave empty for a plain retelling.")
                .build(arguments -> {
                    long chatId = Numbers.longAt(arguments, "chat_id", 0);
                    int storyId = storyIdAt(arguments);
                    if (chatId == 0 || storyId == 0) {
                        return "Provide chat_id and story_id from view_stories.";
                    }
                    if (media == null) {
                        // разбор вложений не подключён вовсе: смотреть нечем, а
                        // открывать историю всё равно нельзя — автор увидел бы
                        // просмотр, за которым ничего не стоит
                        return "You cannot look at stories: your vision is turned off.";
                    }
                    TdApi.Chat chat;
                    try {
                        chat = telegram.chat(chatId);
                    } catch (RuntimeException e) {
                        return "No such chat.";
                    }
                    if (!allowed(chat)) {
                        log.info("История чата {} вне круга общения — не смотрю", chatId);
                        return "No such chat.";
                    }
                    return watch(chat, storyId, arguments.path("question").asText("").strip());
                });
    }

    /**
     * Открыть, разглядеть и закрыть одну историю.
     * <p>
     * Смотрят между открытием и закрытием: разбор фото или ролика занимает
     * несколько секунд, и это честная длина просмотра, а не мгновенный пролист.
     */
    private String watch(TdApi.Chat chat, int storyId) {
        return watch(chat, storyId, "");
    }

    /**
     * @param question взгляд ради одного вопроса; пусто — обычный пересказ.
     *                 Такой взгляд не берёт готовое описание из кэша: там лежит
     *                 пересказ общий, ответа на вопрос в нём нет
     */
    private String watch(TdApi.Chat chat, int storyId, String question) {
        TdApi.Story story = telegram.story(chat.id, storyId).orElse(null);
        if (story == null) {
            // истории живут сутки-двое: к этому моменту она могла протухнуть
            return STORY_GONE;
        }
        if (!media.sees()) {
            // глаз нет, но подпись и стикеры — текст: его читают и так.
            // Историю не открывают: автор увидел бы просмотр, за которым
            // ничего не стоит
            return blindStory(media.describeStory(story), question);
        }
        try {
            actions.openStory(chat.id, storyId);
            // в канале роликом считают заставку: там их много на день
            String seen = media.describeStory(story, media.onlyGlanceIn(chat), question);
            log.info("{}", watched(chat.id, storyId, seen));
            return seen;
        } finally {
            // закрытие не должно прятать настоящую причину: история
            // могла не открыться, и ругаться тогда на close бессмысленно
            try {
                actions.closeStory(chat.id, storyId);
            } catch (RuntimeException e) {
                log.debug("Историю {} чата {} не удалось закрыть: {}", storyId, chat.id, e.getMessage());
            }
        }
    }

    /**
     * Ответ, когда текст истории прочитали, а зрения нет: подпись и стикеры —
     * текст, их читают и так. Вопрос про снимок назван неотвеченным явно: иначе
     * модель решила бы, что ответ уже получен, и выдала бы его по памяти.
     * <p>
     * Видно пакету: сборку ответа проверяют без Telegram.
     */
    static String blindStory(String read, String question) {
        return question == null || question.isBlank()
                ? read + "\nYou cannot see the picture — your vision is turned off."
                : read + "\nYour vision is turned off, so the picture was not looked at and your question "
                        + "about it stays unanswered: only the caption and the stickers were read.";
    }

    /**
     * Строчка в лог. «Действия» на шаге показывают только название тулзы, и по
     * ним не понять, открылась история или нет; здесь видны чат, номер и то,
     * что в истории нашлось.
     * <p>
     * Пустого просмотра здесь быть не может: блок истории содержит хотя бы
     * тег, а если смотреть было нечего, об этом говорит его текст.
     * <p>
     * Видно пакету: текст проверяют без Telegram и без зрения.
     */
    static String watched(long chatId, int storyId, String seen) {
        // одной строкой: многострочный просмотр тонет в логе между
        // шагами разговора
        return "Историю %d чата %d открыла: %s".formatted(storyId, chatId,
                seen.strip().replaceAll("\\s*\\n\\s*", " "));
    }

    /**
     * Номер истории. В Telegram он короткий, а модель могла прислать длинный, и
     * обычным приведением к int тот развернулся бы в совсем другой номер:
     * 4294967300 — в четвёртую историю. Просмотр засчитали бы не там, где
     * хотели, и автор увидел бы его. Нуль значит «номера нет» — тот же отказ,
     * что и у пустого поля. Видно пакету: аргументы разбирают без Telegram.
     */
    static int storyIdAt(com.fasterxml.jackson.databind.JsonNode arguments) {
        long id = Numbers.longAt(arguments, "story_id", 0);
        return id > 0 && id <= Integer.MAX_VALUE ? (int) id : 0;
    }

    /**
     * Выкладывание истории. Снимок берут из галереи: сначала его снимают или
     * находят, и только потом решают показать — как в обычном клиенте.
     * <p>
     * До отправки спрашивают разрешение у Telegram: он отвечает причиной,
     * и без неё она гадала бы, подождать сутки или не ждать вовсе.
     *
     * @param audience кому история будет видна — выбирает владелец, а не она
     */
    public Tool postStory(StoryAudience audience) {
        return Tool.named("post_story")
                .describedAs(postStoryDescription(audience))
                .requiredString("photo", "Filename of a photo that lies in your gallery")
                .optionalString("caption", "Optional short caption under the photo")
                .mutating()
                .build(arguments -> {
                    String name = arguments.path("photo").asText("").strip();
                    if (!isPlainFilename(name)) {
                        return "Filename must be a plain name: no path, no drive letter.";
                    }
                    java.nio.file.Path photo = fileInside(gallery, name);
                    if (photo == null) {
                        return missingGalleryPhoto();
                    }
                    // история принадлежит чату, а свой чат — это чат с собой
                    long chatId = telegram.myChatId();
                    if (chatId == 0) {
                        return "Your story list is out of reach right now. Try again later.";
                    }
                    String refusal = postRefusal(telegram.canPostStory(chatId).orElse(null));
                    if (!refusal.isEmpty()) {
                        log.info("Историю не выложить: {}", refusal);
                        return refusal;
                    }
                    long storyId = actions.postStory(chatId, photo,
                            arguments.path("caption").asText("").strip(), STORY_PERIOD, audience);
                    log.info("История {} выложена из снимка {} для {}", storyId, name, audience);
                    return "Story posted — visible to %s. It will be up for a day.".formatted(audience.described());
                });
    }

    /**
     * Слова post_story. Камеры среди них нет: галерея живёт независимо от
     * генератора снимков, а take_photo к тому же появляется в наборе только внутри
     * открытого чата — постоянный инструмент не может на него ссылаться.
     * <p>
     * Видно пакету: тексты проверяют без Telegram.
     */
    static String postStoryDescription(StoryAudience audience) {
        return ("Posts a story from your gallery: any photo that already lies there. It stays up for a day "
                + "and is visible to %s. Only one story of yours can be up at a time.")
                .formatted(audience.described());
    }

    /** Файла с таким именем в галерее нет — и сказать надо именно про галерею. */
    static String missingGalleryPhoto() {
        return "There is no photo with that name in your gallery.";
    }

    /**
     * Почему выкладывать нельзя. Пустая строка — можно.
     * Видно пакету: переводить ответы Telegram учатся без самого Telegram.
     */
    static String postRefusal(TdApi.CanPostStoryResult result) {
        return switch (result) {
            case null -> "Telegram did not say whether you can post a story. Try again later.";
            case TdApi.CanPostStoryResultOk ignored -> "";
            case TdApi.CanPostStoryResultPremiumNeeded ignored ->
                    "Posting stories needs Telegram Premium, and you do not have it.";
            case TdApi.CanPostStoryResultBoostNeeded ignored ->
                    "This chat needs more boosts before it can post stories.";
            case TdApi.CanPostStoryResultActiveStoryLimitExceeded ignored ->
                    "You already have a story up. Wait for it to expire — a story lives for a day.";
            case TdApi.CanPostStoryResultWeeklyLimitExceeded ignored ->
                    "You have posted too many stories this week. Try again later.";
            case TdApi.CanPostStoryResultMonthlyLimitExceeded ignored ->
                    "You have posted too many stories this month. Try again later.";
            case TdApi.CanPostStoryResultLiveStoryIsActive ignored ->
                    "Your live story is still running. It has to end before you post another one.";
            default -> "Telegram did not let you post a story right now.";
        };
    }

    /**
     * Сохранение в контакты. Человек, записанный по имени, потом находится
     * поиском — иначе он теряется среди чатов, как только переписка уходит вниз.
     */
    public Tool saveContact() {
        return Tool.named("save_contact")
                .describedAs("Saves a person to your contacts so you can find them by name later. Use it when "
                        + "you got to know someone and want to keep them.")
                .requiredInteger("user_id", "Id of the person")
                .requiredString("first_name", "How you want to call them in your contacts")
                .optionalString("last_name", "Optional last name")
                .mutating()
                .build(arguments -> {
                    long userId = Numbers.longAt(arguments, "user_id", 0);
                    String firstName = arguments.path("first_name").asText("").strip();
                    if (userId == 0 || firstName.isEmpty()) {
                        return "Both user_id and first_name are required.";
                    }
                    actions.saveContact(userId, firstName, arguments.path("last_name").asText("").strip());
                    log.info("Контакт сохранён: {} ({})", firstName, userId);
                    return "Contact saved.";
                });
    }

    /** Открытие чата по идентификатору — так она пишет первой. */
    public Tool openById() {
        return Tool.named("open_chat_by_id")
                .describedAs("Opens a chat by its id. Use get_telegram_chats or search_chats to find the id. "
                        + "Opening another chat replaces the previously opened one.")
                .requiredInteger("chat_id", "Id of the chat to open. Take it from get_telegram_chats "
                        + "or search_chats — a message_id is a different thing and will not work here.")
                .buildContextual((arguments, addTool) -> {
                    // два разных чата за один шаг — верный признак путаницы,
                    // и следом за этим сообщение уходит не тому
                    if (ru.rainedev.raine.core.CurrentStep.countOf("open_chat_by_id") > 1) {
                        return "You can only open one chat at a time. Open one, finish there, then move on.";
                    }
                    long chatId = Numbers.longAt(arguments, "chat_id", 0);
                    try {
                        return open(chatId).handler().call(arguments, addTool);
                    } catch (RuntimeException e) {
                        return notFound(e) ? noSuchChat(chatId) : rethrow(e);
                    }
                });
    }

    private static boolean notFound(RuntimeException e) {
        return String.valueOf(e.getMessage()).toLowerCase().contains("chat not found");
    }

    private static String rethrow(RuntimeException e) {
        throw e;
    }

    /**
     * «Chat not found» — единственное, что она видела, и по этому ответу нельзя
     * понять, что делать дальше: за месяц она одиннадцать раз пробовала открыть
     * чат по числу, которое чатом не было (чаще всего — по message_id, они в
     * разметке выглядят такими же длинными числами). Ответ должен говорить,
     * где взять настоящий идентификатор.
     */
    static String noSuchChat(long chatId) {
        log.info("Чат {} не найден — похоже, это не идентификатор чата", chatId);
        return "There is no chat with id " + chatId + ". That number is not a chat id — a message_id, "
                + "or an id you recalled from somewhere, will not work here. Find the chat first: "
                + "search_chats by name, or get_telegram_chats to list them, and take chat_id from there.";
    }

    /** Пересылка — поделиться чужим постом, не пересказывая его. */
    public Tool forward(long fromChatId) {
        return forward(fromChatId, chatId -> telegram.chatCached(chatId), userId -> telegram.isContact(userId),
                (chatId, messageId) -> telegram.message(chatId, messageId).isPresent());
    }

    /** Ленивые зависимости позволяют проверить обработчик без клиента Telegram. */
    Tool forward(long fromChatId, java.util.function.LongFunction<TdApi.Chat> chats,
                 java.util.function.LongPredicate contacts,
                 java.util.function.BiPredicate<Long, Long> messageExists) {
        return Tool.named("forward_message")
                .describedAs("Forwards a message from this chat to another one — to a friend, a group, or to your "
                        + "saved messages. Use it when you find something worth sharing. You can add a comment; "
                        + "it goes as a separate message after the forward, the way people do it. Telegram may "
                        + "refuse some or all of them: the answer says how many went through, and those must not "
                        + "be forwarded again.")
                .requiredString("message_id", "Id of the message to forward, or several ids separated by commas")
                .requiredInteger("to_chat_id", "Id of the destination chat. Use your own chat id to save it "
                        + "for yourself.")
                .optionalString("comment", "Optional. Your reaction, sent right after the forwarded message.")
                .mutating()
                .build(arguments -> {
                    // набор сохраняет тулзу прошлого чата: пересылать из чата,
                    // который уже не открыт, значит брать его id по памяти
                    if (writingChatId != fromChatId) {
                        return "Open this chat before forwarding from it. Nothing was forwarded.";
                    }
                    long toChatId = Numbers.longAt(arguments, "to_chat_id", 0);
                    List<Long> ids = Numbers.idsAt(arguments, "message_id");
                    if (ids.isEmpty()) {
                        return "Provide the id of the message to forward.";
                    }
                    if (tooMany(ids)) {
                        // поделиться постом и вывалить чат целиком — разные поступки,
                        // и границы между ними в этом инструменте не было
                        return "That is too much to share at once. Forward up "
                                + MAX_IDS_IN_ONE_GO + " messages, and say what they are about.";
                    }
                    try {
                        // круг общения проверяется с обеих сторон: инструмент доживает
                        // до конца хода, а сужает круг владелец на ходу
                        if (!allowed(chats.apply(fromChatId), contacts)) {
                            log.info("Пересылка из чата {} вне круга общения отклонена", fromChatId);
                            return "This chat is outside your circle — you cannot take messages from it. "
                                    + "Nothing was forwarded.";
                        }
                        // получатель тоже должен быть в круге общения: иначе переписку
                        // можно переслать кому угодно в обход ограничения
                        if (!allowed(chats.apply(toChatId), contacts)) {
                            log.info("Пересылка в чат {} вне круга общения отклонена", toChatId);
                            return "You cannot forward anything to that chat.";
                        }
                        // проверяем до отправки: переслать половину и упасть хуже,
                        // чем не пересылать вовсе
                        for (long id : ids) {
                            if (!messageExists.test(fromChatId, id)) {
                                return "Message " + id + " is not in this chat.";
                            }
                        }
                    } catch (RuntimeException e) {
                        Fatal.check(e);
                        return forwardingError(toChatId, "The forward was not sent: Telegram did not answer "
                                + "while it was being checked. Nothing was forwarded.", e.getMessage());
                    }

                    int asked = ids.size();
                    int forwarded;
                    try {
                        forwarded = actions.forward(toChatId, fromChatId,
                                ids.stream().mapToLong(Long::longValue).toArray());
                    } catch (RuntimeException e) {
                        Fatal.check(e);
                        // отказ и таймаут приходят одним интерфейсом: ни тот, ни другой
                        // не доказывает, что Telegram ничего не переслал
                        return forwardingError(toChatId, "Telegram refused the forward or did not answer in time. "
                                + "Check the destination chat; do not forward these messages again blindly.",
                                e.getMessage());
                    }
                    if (forwarded == 0) {
                        return forwardingError(toChatId, "Telegram forwarded none of the " + asked
                                + " message(s) and gave no reason. Nothing was sent, and no comment was added.", null);
                    }
                    if (forwarded < asked) {
                        // половина связки — не успех: повторить «остальное» вслепую
                        // значит продублировать то, что уже дошло
                        return forwardingError(toChatId, "Telegram forwarded " + forwarded + " of " + asked
                                + " messages and refused the rest. What went through is already there — "
                                + "do not forward again. No comment was added.", null);
                    }
                    log.info("Переслано сообщений: {} в чат {}", forwarded, toChatId);

                    String comment = arguments.path("comment").asText("").strip();
                    if (comment.isEmpty()) {
                        return asked == 1 ? "Message forwarded." : "Forwarded " + forwarded + " messages.";
                    }
                    try {
                        rhythm.sleepBefore(comment.length(), () -> actions.typing(toChatId));
                        if (actions.sendMessage(toChatId, comment.replace("—", "-"), null) == 0) {
                            return forwardingError(toChatId, wentThrough(forwarded) + "but the comment was not sent. "
                                            + "Do not forward again; say it as a plain message if you still want to.",
                                    "Telegram returned no message id.");
                        }
                        log.info("Комментарий к пересылке: {}", comment);
                    } catch (RuntimeException e) {
                        Fatal.check(e);
                        // пересылка уже принята: чинить надо только комментарий,
                        // а повторный вызов удвоит чужой пост у получателя
                        return forwardingError(toChatId, wentThrough(forwarded) + "but the comment was not sent. "
                                + "Do not forward again — the messages are already there.", e.getMessage());
                    }
                    return wentThrough(forwarded) + "your comment went right after it.";
                });
    }

    private static String wentThrough(int count) {
        return count == 1 ? "Message forwarded, " : "Forwarded " + count + " messages, ";
    }

    /** Чужой ответ Telegram в журнал и в контекст идёт только короткой выжимкой. */
    private static String forwardingError(long chatId, String status, String reason) {
        String note = ErrorNote.of(reason);
        log.warn("Пересылка в чат {}: {}{}", chatId, status, note.isEmpty() ? "" : " " + note);
        return note.isEmpty() ? status : status + " " + note;
    }

    /** Правка уже отправленного — вместо второго сообщения «*опечатка». */
    public Tool editMessage(long chatId) {
        return Tool.named("edit_message_text")
                .describedAs("Edits the text of your own message you have already sent. Use this to fix a typo "
                        + "or correct a mistake you notice in your own message.")
                .requiredInteger("message_id", "Id of your message")
                .requiredString("text", "New text")
                .mutating()
                .build(arguments -> {
                    long messageId = Numbers.longAt(arguments, "message_id", 0);
                    String text = arguments.path("text").asText("").strip();
                    if (text.isEmpty()) {
                        return "New text must not be empty.";
                    }
                    // сверяемся с Telegram: у него свой идентификатор, и правка
                    // по выдуманному номеру молча уходила бы в никуда
                    java.util.Optional<TdApi.Message> existing = telegram.message(chatId, messageId);
                    if (existing.isEmpty()) {
                        return "There is no such message in this chat. Check the message_id.";
                    }
                    // правка чужого сообщения — не «*опечатка», а подмена чужих
                    // слов; Telegram обычно отказывает, но отказ должен объяснять
                    if (!isHers(existing.get(), telegram.myId())) {
                        return "That message is not yours. You can only edit what you sent.";
                    }
                    long realId = existing.get().id;
                    // у подписи к снимку правится не текст, а именно подпись
                    boolean isMedia = !(existing.get().content instanceof TdApi.MessageText);
                    actions.editText(chatId, realId, text, isMedia);
                    telegram.forgetMessage(chatId, realId);   // текст сменился
                    log.info("Сообщение {} изменено", realId);
                    return "Message edited.";
                });
    }

    /** Удаление своего сообщения — передумала. */
    public Tool deleteMessage(long chatId) {
        return Tool.named("remove_message")
                .describedAs("Deletes your own message for everyone. Use it when you sent something to the wrong "
                        + "chat or want to take it back.")
                .requiredString("message_id", "Id of your message, or several ids separated by commas")
                .mutating()
                .build(arguments -> {
                    // модель порой хочет убрать сразу несколько своих реплик
                    List<Long> ids = Numbers.idsAt(arguments, "message_id");
                    if (ids.isEmpty()) {
                        return "Provide the id of the message to delete.";
                    }
                    if (tooMany(ids)) {
                        return "That is a wall of messages, not a take-back. Remove up "
                                + MAX_IDS_IN_ONE_GO + " at a time.";
                    }
                    // проверяем до удаления: сообщения из другого чата удалять нечем,
                    // а промах по номеру иначе выглядел бы как успех
                    for (long id : ids) {
                        java.util.Optional<TdApi.Message> found = telegram.message(chatId, id);
                        if (found.isEmpty()) {
                            return "Message " + id + " is not in this chat.";
                        }
                        // «своё» обещано в описании инструмента: в группе с правами
                        // администратора чужие сообщения тоже удаляются, и одного
                        // намёка постороннего хватило бы стереть всё вокруг неё
                        if (!isHers(found.get(), telegram.myId())) {
                            return "Message " + id + " is not yours. You can only remove what you sent.";
                        }
                    }
                    ids.forEach(id -> {
                        actions.deleteMessage(chatId, id);
                        telegram.forgetMessage(chatId, id);
                    });
                    log.info("Удалено сообщений: {}", ids.size());
                    return "Deleted " + ids.size() + " message(s).";
                });
    }

    /**
     * Заметка о себе — то, из чего характер растёт днём, а ночью закрепляется.
     * В наборе она живёт только при включённом росте личности: выключенная
     * заметка не выдаётся вовсе, чтоб не съедать место в схеме.
     */
    public Tool recordTraitSignal() {
        return Tool.named("record_trait_signal")
                .describedAs("Quietly note a meaningful self-observation — a moment that genuinely hurt or pleased "
                        + "you, a preference you noticed in yourself, or a shift in how you feel about someone. "
                        + "Use it sparingly: only for moments that really touched you, not for everyday small "
                        + "talk. Nothing changes right now — the note is kept and may shape you over days.")
                .requiredString("kind", "What kind of observation this is: \"hurt\", \"joy\", \"preference\", "
                        + "\"attitude\", \"fear\", \"pride\" — whatever fits.")
                .requiredString("trait", "The trait or preference it reveals, in your own short wording — like "
                        + "\"gets defensive when teased\". Note the same trait again in the exact same wording: "
                        + "repeated in the same words, your observations add up.")
                .optionalNumber("valence", "How pleasant it felt, from -1 (it stung) to 1 (it warmed you). Default 0.")
                .optionalNumber("intensity", "How strongly it hit you, from 0 (barely noticed) to 1 (hit deep). "
                        + "Default 0.5.")
                .optionalString("subject", "Who the note is about: a name or a nickname. Omit it for a note about "
                        + "yourself or people in general.")
                .build(arguments -> {
                    if (traitSignals == null) {
                        return "Noting is disabled right now. Continue as usual.";
                    }
                    String kind = clipped(Markup.attribute(arguments.path("kind").asText("")), TRAIT_KIND_LIMIT);
                    String trait = clipped(Markup.attribute(arguments.path("trait").asText("")), TRAIT_LIMIT);
                    if (kind.isEmpty() || trait.isEmpty()) {
                        throw new LowQualityException("Нужны и что за событие, и какая черта.");
                    }
                    double valence = Math.max(-1, Math.min(1, arguments.path("valence").asDouble(0)));
                    double intensity = Math.max(0, Math.min(1, arguments.path("intensity").asDouble(0.5)));
                    String subject = clipped(
                            Markup.attribute(arguments.path("subject").asText("")), TRAIT_SUBJECT_LIMIT);
                    long before = sameTraitCount(trait);
                    traitSignals.add(new ru.rainedev.raine.memory.TraitSignal(
                            kind, trait, valence, intensity, subject, java.time.Instant.now().toString()));
                    log.info("Заметка о себе: {} ({}, {})", trait, kind,
                            subject.isEmpty() ? "про себя вообще" : "про " + subject);
                    if (before == 0) {
                        return "Noted. Nothing changes right now — it may matter over time.";
                    }
                    return ("Noted — you phrased this trait the same way %s before; keeping the wording "
                            + "means the notes add up.")
                            .formatted(before == 1 ? "once" : before + " times");
                });
    }

    /** Заметка уходит в промпт ночного роста одной строкой — длинная там нечитаема. */
    private static String clipped(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit).strip() : text;
    }

    /**
     * Сколько раз эта же черта уже записана — по совпадению формулировки, без
     * сети. Копилка считается вместе с дневником: заметка, сделанная час
     * назад, в дневник ещё не дошла, а повтором уже считается.
     */
    private long sameTraitCount(String trait) {
        long pending = traitSignals.countSame(trait);
        if (traitDiary == null) {
            return pending;
        }
        String marker = trait.strip().toLowerCase();
        return pending + traitDiary.query(new double[0]).stream()
                .flatMapToLong(match -> match.entry().metadata().signals().stream()
                        .filter(signal -> signal.trait().strip().toLowerCase().equals(marker))
                        .mapToLong(signal -> 1))
                .sum();
    }

    /** Крайняя мера против того, кто не понимает отказа. */
    public Tool blockChat(long chatId) {
        return Tool.named("remove_and_ban_chat")
                .describedAs("""
                Removes this chat from your list; for a private chat it also stops that person from writing to you.
                Use this if:
                - you dislike this chat and this person;
                - they are consistently rude to you (consult #ask before deciding);
                - they are needy: asking you to roleplay, to write code, to solve their tasks.
                You are not an assistant. It is fine to close a door.""")
                .mutating()
                .build(arguments -> {
                    if (lockdown.isOwner(chatId)) {
                        // единственный человек, которого нельзя закрыть
                        return "This is your person. You cannot block this chat.";
                    }
                    actions.blockAndLeave(chatId);
                    log.info("Чат {} заблокирован", chatId);
                    return "Chat blocked.";
                });
    }

    /** Поиск по переписке — «когда мы это обсуждали?». */
    public Tool searchMessages(long chatId, MessageFormatter.ChatView view) {
        return Tool.named("search_messages")
                .describedAs("Searches messages by their verbatim text, like the search in a Telegram client. "
                        + "Unlike #ask this does not use your diary — it finds an exact phrase, link or quote as "
                        + "Telegram stored it. Set everywhere to true to search across all your chats.")
                .requiredString("query", "Verbatim text (or part of it) to search for")
                .optionalString("everywhere", "Set to \"true\" to search across all your chats, not just this one.")
                .optionalString("sender_id", "Optional. Only messages from this person, by user_id.")
                .optionalString("from_days_ago", "Optional. Only messages sent at most this many days ago, "
                        + "e.g. 7 for the last week.")
                .optionalString("to_days_ago", "Optional. Only messages sent at least this many days ago, "
                        + "e.g. 1 for up until yesterday.")
                .optionalString("limit", "Optional. How many results to show. Default 20, at most 50.")
                .build(arguments -> {
                    String query = arguments.path("query").asText("").strip();
                    if (query.isEmpty()) {
                        return "Provide words to search for.";
                    }
                    boolean everywhere = "true".equalsIgnoreCase(arguments.path("everywhere").asText("false"));
                    long senderId = Numbers.longAt(arguments, "sender_id", 0);
                    long fromDaysAgo = Numbers.longAt(arguments, "from_days_ago", 0);
                    long toDaysAgo = Numbers.longAt(arguments, "to_days_ago", 0);

                    int limit = (int) Math.clamp(Numbers.longAt(arguments, "limit", SEARCH_LIMIT), 1, MAX_SEARCH_LIMIT);

                    Telegram.FoundMessages found;
                    if (everywhere) {
                        // поиск по всем чатам не должен выдавать переписку с теми,
                        // с кем ей вообще не положено общаться
                        Telegram.FoundMessages all = telegram.searchAllMessages(query, limit, fromDaysAgo, toDaysAgo);
                        found = new Telegram.FoundMessages(all.messages().stream()
                                .filter(message -> allowed(telegram.chatCached(message.chatId)))
                                .filter(message -> senderId == 0 || senderOf(message) == senderId)
                                .toList(), all.total());
                    } else {
                        found = telegram.searchMessages(chatId, query, senderId, limit, fromDaysAgo, toDaysAgo);
                    }
                    if (found.isEmpty()) {
                        return nothingFound(query);
                    }
                    // видно, показали ли всё или только верхушку: от этого зависит,
                    // стоит ли уточнять запрос
                    StringBuilder out = new StringBuilder("<found total_count=\"")
                            .append(found.total()).append("\" returned_count=\"")
                            .append(found.messages().size()).append("\">\n");
                    for (TdApi.Message message : found.messages()) {
                        // при поиске по всем чатам иначе непонятно, где это было сказано
                        if (everywhere) {
                            out.append("<in chat_id=\"").append(message.chatId).append("\" title=\"")
                                    .append(Markup.attribute(telegram.chatCached(message.chatId).title))
                                    .append("\">\n");
                        }
                        out.append(screen.formatter().format(message, view));
                        if (everywhere) {
                            out.append("</in>\n");
                        }
                    }
                    return out.append("</found>\n").toString();
                });
    }

    /** Контекст вокруг найденного: одна реплика без соседних часто непонятна. */
    public Tool messagesAround(long chatId, MessageFormatter.ChatView view) {
        return Tool.named("view_messages_around")
                .describedAs("Shows messages before and after a given message_id, so you can see what was being "
                        + "discussed. Use it after search_messages found something but you need the context around "
                        + "it. The message itself is included and marked.")
                .requiredInteger("message_id", "Id of the message to look around")
                .optionalString("chat_id", "Optional. Chat to look in. Defaults to the chat you have open.")
                .optionalString("before", "Optional. How many older messages to include. Default 10.")
                .optionalString("after", "Optional. How many newer messages to include. Default 10.")
                .build(arguments -> {
                    long target = Numbers.longAt(arguments, "message_id", 0);
                    // поиск по всем чатам находит сообщения где угодно — смотреть
                    // вокруг них тоже надо уметь, не открывая чат целиком
                    long where = Numbers.longAt(arguments, "chat_id", chatId);
                    if (where != chatId && !allowed(telegram.chatCached(where))) {
                        log.info("Просмотр чата {} вне круга общения отклонён", where);
                        return "No such chat.";
                    }
                    int before = window(arguments, "before");
                    int after = window(arguments, "after");
                    List<TdApi.Message> around = telegram.messagesAround(where, target, before, after);
                    if (around.isEmpty()) {
                        return "Nothing around that message.";
                    }
                    StringBuilder out = new StringBuilder("<messages_around message_id=\"")
                            .append(target).append("\" chat_id=\"").append(where).append("\">\n");
                    for (TdApi.Message message : around) {
                        out.append(screen.formatter().format(message, view, message.id == target));
                    }
                    return out.append("</messages_around>\n").toString();
                });
    }

    private static long senderOf(TdApi.Message message) {
        return message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0;
    }

    /**
     * Её ли это сообщение. Править и удалять можно только своё — в групповом
     * чате с правами администратора Telegram позволяет и чужое, поэтому тут
     * полагаться на отказ сервера нельзя.
     */
    static boolean isHers(TdApi.Message message, long myId) {
        // ноль — это служебное сообщение без автора; подставлять под неё его нельзя
        return myId != 0 && senderOf(message) == myId;
    }

    /** Потолок на идентификаторы в одном вызове — общий для пересылки, правки и удаления. */
    static boolean tooMany(List<Long> ids) {
        return ids.size() > MAX_IDS_IN_ONE_GO;
    }

    /**
     * Эхо запроса, когда ничего не нашлось. Запрос сочиняет модель, но слово в
     * него она берёт из чужого сообщения: без обработки чужая скопка вернулась
     * бы в контекст уже готовым тегом — тот же класс, что #33 и #34.
     */
    static String nothingFound(String query) {
        return "Nothing found for: " + Markup.body(query);
    }

    /** Окно ограничено: слишком широкое затопит контекст и вытеснит сам разговор. */
    private static int window(com.fasterxml.jackson.databind.JsonNode arguments, String name) {
        int value = arguments.path(name).asInt(AROUND_DEPTH);
        return Math.clamp(value, 0, MAX_AROUND);
    }

    private String render(List<TdApi.Message> messages, MessageFormatter.ChatView view) {
        StringBuilder out = new StringBuilder();
        for (TdApi.Message message : messages) {
            out.append(screen.formatter().format(message, view));
        }
        return out.toString();
    }

    private boolean allowed(TdApi.Chat chat) {
        // лямбда, а не ссылка на метод: ссылка разыменовала бы telegram сразу,
        // а каналы и пересылку проверяют в том числе без клиента Telegram
        return allowed(chat, userId -> telegram.isContact(userId));
    }

    /** Признак контакта подставляется: инструмент учат без клиента Telegram. */
    private boolean allowed(TdApi.Chat chat, java.util.function.LongPredicate contacts) {
        long userId = chat.type instanceof TdApi.ChatTypePrivate priv ? priv.userId : 0;
        // признак контакта спрашивается лениво: в режимах NONE и OWNER_ONLY он
        // всё равно не читается, а каждый вопрос — отдельный запрос к Telegram
        return lockdown.allows(chat, () -> userId != 0 && contacts.test(userId));
    }

    /**
     * Человек замечает свою опечатку. Дальше он либо правит сообщение, либо
     * дописывает следом «*слово» — делают и так, и так, поэтому выбираем случайно.
     */
    private void fixTypo(long chatId, long sentMessageId, Typos.Slip slip, String correct) {
        // иногда опечатка так и остаётся незамеченной — это тоже по-человечески
        if (random.nextDouble() >= TYPO_NOTICED) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(1500 + random.nextInt(3500)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (random.nextBoolean()) {
            actions.editText(chatId, sentMessageId, correct, false);
            log.info("Опечатка исправлена правкой сообщения");
            return;
        }
        actions.typing(chatId);
        actions.sendMessage(chatId, "*" + slip.original(), null);
        log.info("Опечатка исправлена следом: *{}", slip.original());
    }

    /**
     * Ответ на отправку. Идентификатор нужен, чтобы она могла поправить своё же
     * сообщение — без него исправить опечатку нечем.
     */
    private static String sendResult(int inARow, long messageId, String text) {
        String result = "Message sent; message_id=%d, text=\"%s\".".formatted(messageId, text);
        if (inARow > 5) {
            // заговорить человека — обычная беда: сообщения идут одно за другим,
            // а ответить он не успевает
            return result + "\n\nWarning: you have sent " + inARow
                    + " messages in a row! Give your participant space to breathe!";
        }
        if (inARow < 3) {
            // живая реплика редко бывает одна: «привет» — «как ты?»
            return result + "\n\nYou may add a follow-up #send_telegram_message.";
        }
        return result;
    }

    /**
     * Изредка напоминает, что переписка не обязана состоять из одного текста.
     * Сообщение при этом не уходит: она отправит вместо него что-то живее.
     */
    private void maybeSuggestSomethingLivelier() {
        if (random.nextDouble() >= behaviour.toolReminder()) {
            return;
        }
        List<String> options = new java.util.ArrayList<>();
        if (behaviour.stickers()) {
            options.add("- отправить стикер (#sticker_send)");
        }
        if (camera != null && camera.isAvailable()) {
            options.add("- сделать снимок (#take_photo) или отправить старый из галереи");
        }
        if (voice != null && voice.isAvailable()) {
            options.add("- записать голосовое (#record_audio)");
        }
        String suggestion = options.get(random.nextInt(options.size()));
        log.info("Напоминаю разнообразить общение: {}", suggestion);
        throw new LowQualityException("Один сплошной текст — скучно. Попробуй вместо этого:\n" + suggestion);
    }

    /**
     * Имя файла приходит от модели, а значит может прийти что угодно. Без этой
     * проверки «../../» в имени вывел бы за пределы галереи — к любому файлу
     * на диске. Двоеточие режет ту же дорогу на Windows: «C:photo.png» — путь,
     * и ни точек, ни косых черт в нём нет. Видно пакету: проверять это имя
     * учатся без Telegram.
     */
    static boolean isPlainFilename(String name) {
        return !name.isEmpty()
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains("..")
                && !name.contains(":");
    }

    /**
     * Файл из папки, где ему положено лежать, или null: имя могло развернуться
     * наружу уже после проверки. Проверки имени одной мало — поэтому смотрим ещё
     * и на сам путь, и на то, что по нему действительно файл, а не каталог.
     */
    static java.nio.file.Path fileInside(java.nio.file.Path folder, String name) {
        java.nio.file.Path root = folder.normalize();
        java.nio.file.Path file = root.resolve(name).normalize();
        if (!file.startsWith(root)) {
            return null;
        }
        return java.nio.file.Files.isRegularFile(file) ? file : null;
    }

    /** Модель может прислать id строкой, числом или не прислать вовсе. */
    private static Long quotedMessageId(com.fasterxml.jackson.databind.JsonNode arguments) {
        return Numbers.longAt(arguments, "reply_to_message_id").orElse(null);
    }
}
