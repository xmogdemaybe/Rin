package ru.rainedev.raine;

import it.tdlight.client.SimpleAuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Goodnights;
import ru.rainedev.raine.core.Notification;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.core.Rest;
import ru.rainedev.raine.core.Spontaneity;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.OpenAiCompatibleClient;
import ru.rainedev.raine.memory.CharacterGrowth;
import ru.rainedev.raine.memory.Diary;
import ru.rainedev.raine.memory.DiaryMemory;
import ru.rainedev.raine.memory.DiaryWriter;
import ru.rainedev.raine.memory.Recall;
import ru.rainedev.raine.memory.TrustLedger;
import ru.rainedev.raine.memory.WorkingMemory;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.MediaDescriber;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.phone.Notifications;
import ru.rainedev.raine.prompt.Prompts;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.telegram.TelegramProxy;
import ru.rainedev.raine.tools.AntiRepeat;
import ru.rainedev.raine.tools.TelegramTools;
import ru.rainedev.raine.tools.WebSearch;
import ru.rainedev.raine.vision.TelegramMedia;
import ru.rainedev.raine.vision.Vision;

/** Связывает Telegram, промпты и цикл обработки в одно живое целое. */
public final class RaineApp implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RaineApp.class);

    private final Config config;
    private final SimpleTelegramClient client;

    /** Правки в config.properties подхватываются на ходу — как и промпты. */
    private ru.rainedev.raine.config.LiveConfig settings;

    /** Перенести разговор в память. Зовётся при переполнении и при остановке. */
    private java.util.function.Consumer<String> rememberConversation = why -> { };

    /** Разговоры, которые не удалось пересказать: сеть отвалилась не вовремя. */
    private ru.rainedev.raine.memory.PendingConversations pending;

    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * TDLib начинает закрываться сам, не спрашивая нас: при остановке процесса
     * его собственный обработчик может сработать раньше нашего. Обращаться
     * к закрывающемуся клиенту нельзя — вызов повисает на минуты.
     */
    private volatile boolean telegramClosing;

    /**
     * Всё это создаётся в {@link #start()}, а читается потоком обновлений
     * Telegram, который запускается раньше. Без volatile такой поток вправе
     * не увидеть присваивание и провалиться в пустоту посреди разговора.
     */
    private volatile Telegram telegram;
    private volatile TelegramTools tools;
    private volatile NotificationLoop loop;
    private Thread brain;
    private Rest rest;
    private Goodnights goodnights;
    private CharacterGrowth growth;
    private TelegramMedia telegramMedia;
    private Vision vision;
    private volatile Lockdown lockdown;
    private TelegramActions actions;
    private ru.rainedev.raine.core.Surroundings around;
    private final java.util.Random random = new java.util.Random();

    /** Столько чатов просматривается при запуске в поисках непрочитанного. */
    private static final int CATCH_UP_CHATS = 50;

    /** Сколько ждём, пока начатый ход договорит. Дольше — уже не остановка, а зависание. */
    private static final java.time.Duration SHUTDOWN_GRACE = java.time.Duration.ofMinutes(2);

    /** Насколько часто сообщение из заглушённого чата остаётся без внимания. */
    private static final double MUTED_IGNORE_CHANCE = 0.8;
    private Spontaneity spontaneity;

    public RaineApp(SimpleTelegramClientBuilder builder,
                    SimpleAuthenticationSupplier<?> authentication,
                    Config config) {
        this.config = config;
        // после заморозки аккаунта продолжать нельзя: каждая следующая попытка
        // только копит отказы
        ru.rainedev.raine.core.Fatal.handler(error -> {
            log.error("Останавливаюсь: учётная запись недоступна");
            stopEverything();
        });
        builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthorizationState);
        builder.addUpdateHandler(TdApi.UpdateConnectionState.class, this::onConnectionState);
        builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);
        // Telegram подтверждает отправку отдельным событием и только там называет
        // настоящий номер сообщения
        builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded.class,
                update -> telegram.onSendSucceeded(update.oldMessageId, update.message.id));
        builder.addUpdateHandler(TdApi.UpdateMessageSendFailed.class,
                update -> telegram.onSendFailed(update.oldMessageId,
                        update.error == null ? "причина неизвестна" : update.error.message));
        this.client = builder.build(authentication);
        // как только клиент есть, первым делом ставим прокси: в сети, где
        // Telegram заблокирован, без него не дойти даже до кода подтверждения
        TelegramProxy.setup(client, config.telegramProxy());
    }

    public SimpleTelegramClient client() {
        return client;
    }

    /** Поднимает всё остальное. Вызывается после того, как клиент авторизовался. */
    public void start() {
        telegram = new Telegram(client, config.character().name());
        lockdown = new Lockdown(Lockdown.Mode.of(config.lockdown()), config.ownerId(),
                config.lockdownAllowChannels());
        log.info("Круг общения: {}", describe(lockdown.mode()));
        // без этого поиск по своим чатам возвращает пустоту: TDLib ищет
        // только по тому, что уже загружено с сервера
        telegram.loadChats(200);

        ru.rainedev.raine.llm.Transcript.enabled(config.llmTranscript());
        Prompts prompts = new Prompts(config.promptsDir(), config.character());
        LlmClient llm = new OpenAiCompatibleClient(config.llm(), config.embeddingModel());

        // зрение подключается к разметке: описание встаёт прямо под вложением
        MediaDescriber media = MediaDescriber.NONE;
        if (config.vision().enabled()) {
            vision = new Vision(llm, prompts, config.vision().cacheDir(),
                    config.vision().model(), config.vision().cheapModel(), config.character().name());
            var hearing = new ru.rainedev.raine.speech.SpeechToText(config.hearing());
            if (hearing.isAvailable()) {
                vision.hearing(hearing);
            }
            telegramMedia = new TelegramMedia(telegram, vision, () -> loop == null ? List.of() : loop.context());
            telegramMedia.videoInChannels(config.vision().videoInChannels());
            media = telegramMedia;
        } else {
            log.info("Зрение выключено: вложения видны только по типу");
        }

        MessageFormatter formatter = new MessageFormatter(
                telegram::nameOf,
                (chatId, messageId) -> telegram.message(chatId, messageId),
                media);

        actions = config.readOnly()
                ? new TelegramActions.ReadOnly()
                : new TelegramActions.Live(client, telegram);
        if (config.readOnly()) {
            log.warn("Режим только чтения: она думает, но ничего не отправляет и не открывает чаты");
        }

        AntiRepeat antiRepeat = new AntiRepeat(llm, prompts.lazy("anti_repeat.md"), config.repeat());
        tools = new TelegramTools(telegram, actions, new ChatScreen(formatter, prompts).recentDepth(config.vision().recentDepth()),
                lockdown, antiRepeat);
        if (telegramMedia != null) {
            tools.media(telegramMedia);
        }

        var horde = new ru.rainedev.raine.image.HordeClient(
                config.camera().baseUrl(), config.camera().apiKey(), config.camera().models(),
                java.time.Duration.ofMinutes(config.camera().queueMinutes()));
        if (config.camera().enabled() && horde.isAvailable()) {
            tools.camera(new ru.rainedev.raine.image.ImageGenerator(horde, llm, prompts,
                    config.camera().gallery(), config.character().name(),
                    config.vision().model(), config.camera().maxTrials()),
                    config.camera().gallery(), this::onPhotoOutcome);
        } else {
            log.info("Генерация снимков не настроена: сфотографироваться нечем");
        }

        var voiceGenerator = new ru.rainedev.raine.speech.VoiceGenerator(config.voice(), config.voice().directory());
        if (voiceGenerator.isAvailable()) {
            tools.voice(voiceGenerator, config.voice().directory(), prompts.lazy("record_audio_speech.md"));
        } else {
            log.info("Синтез речи не настроен: голосовые записывать нечем");
        }

        Diary diary = new Diary(config.diaryDir());
        diary.embedder(llm::embedding);

        // ask доступен всегда: заглянуть в память можно в любой момент хода
        Toolbox always = new Toolbox();
        WebSearch web = new WebSearch(config.webSearchUrl(), config.webSearchKey());
        Recall recall = new Recall(diary, llm, prompts.lazy("character_base.md"), web,
                config.diaryMinRelatedness());
        always.add(recall.asTool(this::currentSituation));
        if (config.behaviour().stickers()) {
            always.add(tools.stickers().list());
            // пак открывают не в чате, а «у себя»: ссылка могла прийти когда угодно
            always.add(tools.stickers().pack());
        }
        always.add(tools.chatList());
        always.add(tools.contactList());
        always.add(tools.saveContact());
        always.add(tools.searchChats());
        always.add(tools.openById());

        // рост личности: заметки о себе принимает эта же связка. Тумблер снял —
        // инструмент из набора исчезает, вместо просьбы «не пользуйся»
        ru.rainedev.raine.memory.TraitSignals traitSignals = new ru.rainedev.raine.memory.TraitSignals();
        tools.traitSignals(traitSignals);
        tools.traitDiary(diary);
        if (config.characterGrowthEnabled()) {
            always.add(tools.recordTraitSignal());
        }
        // один экземпляр на всю жизнь: о переполнении файла владелец должен
        // услышать один раз, а не каждую ночь заново
        growth = new CharacterGrowth(diary, llm, prompts.lazy("character_growth_pass.md"),
                config.promptsDir().resolve("character_growth.md"));
        growth.onOverflow(this::tellOwnerAboutGrowth);

        // доверие к людям: единственная бухгалтерия в data/trust.md, по строке на
        // человека. Сама она решает только «заслужено или нет» — файл правит код
        var trust = new TrustLedger(
                config.diaryDir().resolveSibling("trust.md"),
                config.ownerId(), config.character().papikName());
        if (config.trustLedgerEnabled()) {
            always.add(trust.tool());
        }
        // файл доверия тоже уходит в системный промпт: об обрезке слышит владелец
        trust.onOverflow(this::tellOwnerAboutTrustLedger);

        WorkingMemory workingMemory = new WorkingMemory(config.workingMemoryFile(), llm, config.character().name());
        // файл уходит в каждый системный промпт: об обрезке владелец должен слышать
        workingMemory.onOverflow(this::tellOwnerAboutWorkingMemory);
        around = new ru.rainedev.raine.core.Surroundings(
                config.around().enabled(), config.around().latitude(), config.around().longitude());

        loop = new NotificationLoop(
                llm,
                () -> prompts.system(around.asPromptSuffix() + workingMemory.asPromptSuffix()
                        + (settings.current().trustLedgerEnabled() ? trust.asPromptSuffix() : "")),
                always, config.contextTokenLimit());
        // доверие смягчает поведение: на ходу разговора она знает, кому отвечает,
        // и строка об этом человеке стоит в промпте, а не в контексте
        loop.aboutPerson(id -> settings.current().trustLedgerEnabled() ? trust.aboutPerson(id) : "");
        DiaryMemory diaryMemory = new DiaryMemory(diary, llm,
                config.diaryInjectionMaxLength(), config.diaryMinRelatedness());
        loop.memory(diaryMemory);
        DiaryWriter writer = new DiaryWriter(
                diary, llm, prompts.lazy("diary_save.md"), config.diaryPlagiarismThreshold());
        pending = new ru.rainedev.raine.memory.PendingConversations(config.diaryDir().resolveSibling("pending"));
        // разговор не выбрасываем, а переносим в долгую память
        rememberConversation = why -> {
            if (loop.context().isEmpty()) {
                return;
            }
            log.info("{} — переношу разговор в дневник", why);
            // заметки о себе дожили до переноса: записываются вместе с дневником
            var taken = traitSignals.drain();
            try {
                // сначала средняя память: ей нужен ещё не урезанный разговор
                workingMemory.update(prompts.system(""), loop.context());
                var saved = writer.saveStrict(prompts.system(""), loop.context(), taken);
                if (saved.isEmpty() && !taken.isEmpty()) {
                    traitSignals.requeue(taken);
                }
            } catch (RuntimeException e) {
                if (!taken.isEmpty()) {
                    traitSignals.requeue(taken);
                }
                // чаще всего это оборванная сеть на остановке: пересказ делает
                // модель, а её нет. Разговор откладывается сырым и разбирается
                // при следующем запуске — потерять целый день из-за связи нельзя
                log.error("Не удалось сохранить память — откладываю разговор", e);
                pending.keep(loop.context());
            }
            loop.clearContext();
            diaryMemory.forget();
            diary.reload();
        };
        loop.onContextOverflow(() -> rememberConversation.accept("Контекст переполнен"));

        // ход закончен — выходим из чатов, в которые заходили
        loop.onNotificationDone(done -> tools.closeOpened());
        loop.onNotificationFailed((notification, failure) ->
                pending.keepFailure(notification.text(), loop.context(), String.valueOf(failure.getMessage())));
        // потерянное уведомление не должно оставлять чат прочитанным: без отметки
        // догон при следующем запуске его не найдёт
        loop.backToUnread(this::backToUnread);

        rest = new Rest(random);
        loop.rest(rest);

        // всё, что можно поменять, не перезапуская бота
        settings = new ru.rainedev.raine.config.LiveConfig(Config.defaultFile(), config)
                .onChange(fresh -> {
                    var circle = Lockdown.Mode.of(fresh.lockdown());
                    if (circle != lockdown.mode() || fresh.lockdownAllowChannels() != lockdown.allowsChannels()) {
                        log.info("Круг общения теперь: {}{}", describe(circle),
                                fresh.lockdownAllowChannels() ? ", каналы читаю" : ", каналы не читаю");
                    }
                    tools.behaviour(fresh.behaviour());
                    rest.dayNaps(fresh.behaviour().dayNaps());
                    lockdown.mode(circle);
                    lockdown.allowChannels(fresh.lockdownAllowChannels());
                    antiRepeat.limits(fresh.repeat());
                    diaryMemory.limits(fresh.diaryInjectionMaxLength(), fresh.diaryMinRelatedness());
                    recall.limits(fresh.diaryMinRelatedness());
                    ru.rainedev.raine.llm.Transcript.enabled(fresh.llmTranscript());
                    // тумблеры снимаются на ходу: инструмент появляется в наборе
                    // или исчезает из него, вместо просьбы «не пользуйся»
                    switchTool(always, fresh.characterGrowthEnabled(), "record_trait_signal",
                            tools::recordTraitSignal, "Рост личности");
                    switchTool(always, fresh.trustLedgerEnabled(), "note_trust", trust::tool,
                            "Счёт доверия");
                });
        settings.watch();

        if (config.night().enabled()) {
            var night = new ru.rainedev.raine.core.NightSleep(
                    config.night().bedtimeFrom(), config.night().bedtimeTo(),
                    config.night().wakeFrom(), config.night().wakeTo(),
                    java.time.Clock.systemDefaultZone(), random);
            rest.night(night);
            log.info("Сегодня ложится около {}, встаёт около {}",
                    night.bedtime().toLocalTime().withSecond(0), night.wakeTime().toLocalTime().withSecond(0));

            // сон по-человечески: живой разговор откладывает ночь, а отбой и подъём
            // отмечаются парой слов тем, кто особенно близок
            goodnights = new ru.rainedev.raine.core.Goodnights(
                    trust, config.ownerId(), config.character().papikName(), random);
            rest.aliveTalk(loop::talkAlive);
            rest.leavingForNight(this::goodnightsAtBedtime);
            rest.seenMorning(this::goodnightsAtMorning);
        }

        rest.duringNight((duration, wokeUp) -> {
            Config fresh = settings.current();
            if (fresh.sleepConsolidation()) {
                var limits = fresh.consolidation();
                var bounded = new ru.rainedev.raine.memory.SleepConsolidation(
                        diary, llm, prompts.lazy("sleep_consolidator.md"),
                        config.diaryDir().resolve("archive"), fresh.diaryConsolidationBudget(), random,
                        limits.maxRequests(), limits.maxTokens());
                bounded.run(duration.compareTo(java.time.Duration.ofMinutes(limits.minutes())) < 0
                        ? duration : java.time.Duration.ofMinutes(limits.minutes()),
                        () -> wokeUp.getAsBoolean() || !settings.current().sleepConsolidation());
            }
            // рост личности — отдельная ветка рядом с пересмотром, но не внутри
            // него: у неё свой проход и свой тумблер, ни от чего другого не зависит
            if (fresh.characterGrowthEnabled()) {
                growth.pass(() -> wokeUp.getAsBoolean() || !settings.current().characterGrowthEnabled());
            }
        });
        log.info("Пересмотр памяти во сне: {}", config.sleepConsolidation() ? "включён" : "выключен");
        log.info("Рост личности: {}", config.characterGrowthEnabled() ? "включён" : "выключен");
        log.info("Счёт доверия: {}", config.trustLedgerEnabled() ? "ведётся" : "не ведётся");

        spontaneity = new Spontaneity(loop, diary, always, random);
        spontaneity.asleepWhen(rest::isResting);
        tools.actingOnImpulse(spontaneity::isActing);
        spontaneity.start();

        // «в сети» держится только пока она занята перепиской: остальное время
        // и весь сон её видно офлайн, как любого человека
        loop.onIdle(() -> actions.setOnline(false));
        rest.onStateChange(resting -> actions.setOnline(false));

        brain = Thread.ofVirtual().name("rin-brain").start(loop::run);
        Thread.ofVirtual().name("rin-catchup").start(this::catchUpOnUnread);
        Thread.ofVirtual().name("rin-pending").start(() -> rememberPending(writer, prompts.system("")));
        // отказ сам не переигрывается: эти файлы нужны для контекста разговора.
        // Чат, оставленный без ответа, при этом снова числится непрочитанным, и
        // его найдёт догон при запуске
        int owed = pending.unanswered().size();
        if (owed > 0) {
            log.warn("С прошлого сбоя без ответа осталось {} уведомл. — data/pending/failed-notifications", owed);
        }
        log.info("Запущена от имени {} (id {})", config.character().name(), telegram.myId());
    }

    /**
     * Отложенное сетью досказывается при первой возможности — не мешая запуску
     * и не задерживая ответ. Не получилось и сейчас — пусть лежит дальше:
     * лучше пересказать завтра, чем выбросить сегодня.
     */
    private void rememberPending(DiaryWriter writer, String systemPrompt) {
        for (java.nio.file.Path file : pending.waiting()) {
            var conversation = pending.read(file);
            if (conversation.isEmpty()) {
                log.warn("Отложенный разговор {} пуст или повреждён — оставляю для проверки", file.getFileName());
                continue;
            }
            try {
                int saved = writer.saveStrict(systemPrompt, conversation).size();
                pending.done(file);
                log.info("Отложенный разговор {} пересказан, записей: {}", file.getFileName(), saved);
            } catch (RuntimeException e) {
                log.warn("Отложенный разговор {} пока не пересказать: {}", file.getFileName(), e.getMessage());
                return;   // связи по-прежнему нет, остальные ждут своей очереди
            }
        }
    }

    /**
     * Снимок готовится в фоне, поэтому о нём сообщают уведомлением — так же,
     * как о пришедшем сообщении. Иначе результат некуда было бы деть: ход,
     * в котором она начала съёмку, давно закончился.
     */
    private void onPhotoOutcome(long chatId, boolean ready, String message) {
        String text = ready
                ? ("Your photo is ready.\n\n%s\nFilename: %s\n\nOpen the chat and send it with "
                   + "send_telegram_message if you like it, or take another one. Mention the filename "
                   + "in your diary — you may want to reuse the photo later.")
                        .formatted(lookAtOwnPhoto(message), message)
                : "Your photo did not work out: " + message + ". You can try again or let it go.";

        Toolbox available = new Toolbox();
        available.add(tools.open(chatId));
        loop.submit(new Notification(text, available, true).from(chatId));
        log.info("Уведомление о снимке поставлено в очередь");
    }

    /**
     * Ложится спать: кому близко — тем пара тихих слов перед сном. Порыв идёт
     * по обычной механике: чат открывается сам, а что сказать — решает она.
     */
    private void goodnightsAtBedtime() {
        greetCloseOnes(goodnights.drawForNight(), RaineApp::goodnightPrompt);
    }

    /** Проснулась сама, ко времени: те же близкие слышат первые слова с утра. */
    private void goodnightsAtMorning() {
        greetCloseOnes(goodnights.drawForMorning(), RaineApp::morningPrompt);
    }

    /** Вечер и утро отличаются только книгой пауз и подсказкой — ход один на двоих. */
    private void greetCloseOnes(List<Goodnights.Receiver> receivers,
                                java.util.function.Function<Goodnights.Receiver, String> prompt) {
        for (Goodnights.Receiver receiver : receivers) {
            Toolbox available = new Toolbox();
            available.add(tools.open(receiver.id()));
            loop.submit(new Notification(prompt.apply(receiver), available, true)
                    .about(receiver.id()).from(receiver.id()));
        }
    }

    /**
     * Файл нажитого упёрся в лимит: об этом слышит владелец, а не только журнал.
     * Один раз на случай — дальше файл разбирают руками.
     */
    private void tellOwnerAboutGrowth(long lines) {
        long ownerId = config.ownerId();
        if (ownerId <= 0) {
            return;
        }
        Toolbox available = new Toolbox();
        available.add(tools.open(ownerId));
        loop.submit(new Notification(("Your prompts/character_growth.md reached its %d-line limit, so "
                + "tonight's growth pass wrote nothing: the draft had %d lines. Open the chat with your "
                + "person (id %d) and say plainly, in one short message, that the file is full and needs "
                + "trimming by hand. Then call #wait.").formatted(
                        CharacterGrowth.MAX_LINES, lines, ownerId), available, true).from(ownerId));
    }

    /**
     * Средняя память не влезла в отведённый размер и обрезана: часть её пунктов
     * больше не доходит до системного промпта. Слышит владелец — один раз на случай.
     */
    private void tellOwnerAboutWorkingMemory(long chars) {
        long ownerId = config.ownerId();
        if (ownerId <= 0) {
            return;
        }
        Toolbox available = new Toolbox();
        available.add(tools.open(ownerId));
        loop.submit(new Notification(("Your working memory file %s reached its %d-character limit and had to be "
                + "cut short: the text was %d characters, so some of your own notes no longer reach your system "
                + "prompt. Open the chat with your person (id %d) and say plainly, in one short message, that the "
                + "file is too long and needs trimming by hand. Then call #wait.").formatted(
                        config.workingMemoryFile(), WorkingMemory.MAX_LENGTH, chars, ownerId),
                        available, true).from(ownerId));
    }

    /**
     * Файл доверия вырос дольше отведённых строк и обрезан: часть записей больше
     * не доходит до системного промпта. Слышит владелец — один раз на случай.
     */
    private void tellOwnerAboutTrustLedger(long lines) {
        long ownerId = config.ownerId();
        if (ownerId <= 0) {
            return;
        }
        Toolbox available = new Toolbox();
        available.add(tools.open(ownerId));
        loop.submit(new Notification(("Your trust file holds %d lines while only the last %d reach your system "
                + "prompt, so the people above them are invisible to you right now. Open the chat with your "
                + "person (id %d) and say plainly, in one short message, that the file is too long and needs "
                + "trimming by hand. Then call #wait.").formatted(
                        lines, TrustLedger.MAX_LINES, ownerId), available, true).from(ownerId));
    }

    private static String goodnightPrompt(Goodnights.Receiver receiver) {
        return ("You are about to go to bed soon. If you feel like it, say a couple of quiet goodnight "
                + "words to %s: open the chat (id %d) and write them yourself. If you would rather stay "
                + "silent tonight, call #wait.").formatted(knownAs(receiver), receiver.id());
    }

    private static String morningPrompt(Goodnights.Receiver receiver) {
        return ("You just woke up on your own, the night is over. If you feel like it, send your first "
                + "good-morning words to %s: open the chat (id %d) and write them yourself. If you would "
                + "rather stay quiet, call #wait.").formatted(knownAs(receiver), receiver.id());
    }

    private static String knownAs(Goodnights.Receiver receiver) {
        return receiver.name() == null || receiver.name().isBlank()
                ? "someone close to you"
                : receiver.name();
    }

    /** Последняя реплика в контексте — чтобы подагент искал по делу, а не вообще. */
    /**
     * Собственный снимок она сначала разглядывает, а потом решает, отправлять ли.
     * Без описания выбор делается вслепую: «файл готов» — и всё.
     */
    private String lookAtOwnPhoto(String fileName) {
        if (vision == null) {
            return "";
        }
        try {
            return vision.describe(config.camera().gallery().resolve(fileName),
                    Vision.Kind.PHOTO, loop == null ? List.of() : loop.context());
        } catch (RuntimeException e) {
            log.debug("Свой снимок не разглядеть: {}", e.getMessage());
            return "";
        }
    }

    private String currentSituation() {
        var context = loop.context();
        return context.isEmpty() ? "" : String.valueOf(context.getLast().content());
    }

    private void onAuthorizationState(TdApi.UpdateAuthorizationState update) {
        switch (update.authorizationState) {
            case TdApi.AuthorizationStateReady ignored -> log.info("Авторизация пройдена");
            case TdApi.AuthorizationStateClosing ignored -> {
                telegramClosing = true;
                log.info("Закрываемся...");
            }
            case TdApi.AuthorizationStateClosed ignored -> {
                telegramClosing = true;
                log.info("Закрыто");
            }
            case TdApi.AuthorizationStateLoggingOut ignored -> log.info("Выходим из аккаунта...");
            default -> log.debug("Состояние авторизации: {}",
                    update.authorizationState.getClass().getSimpleName());
        }
    }

    /** Состояние связи стоит видеть в логе: половина бед — с сетью, а не с ботом. */
    private void onConnectionState(TdApi.UpdateConnectionState update) {
        switch (update.state) {
            case TdApi.ConnectionStateReady ignored -> log.info("Связь с Telegram есть");
            case TdApi.ConnectionStateConnecting ignored ->
                    log.info("Подключаюсь к Telegram... если надолго — проверь VPN или прокси");
            case TdApi.ConnectionStateConnectingToProxy ignored -> log.info("Подключаюсь через прокси...");
            case TdApi.ConnectionStateWaitingForNetwork ignored -> log.info("Жду сеть...");
            case TdApi.ConnectionStateUpdating ignored -> log.info("Догоняю пропущенное...");
            default -> log.debug("Состояние связи: {}", update.state.getClass().getSimpleName());
        }
    }

    /**
     * События Telegram разносит один поток, и он же доставляет ответы на наши
     * запросы. Спросить у него что-нибудь и подождать ответа прямо здесь —
     * значит остановить его навсегда: ответа он принести не сможет, потому что
     * ждёт сам себя. После такого клиент глохнет целиком, молча.
     * <p>
     * Поэтому здесь только то, что считается на месте, а всё остальное —
     * включая проверку круга общения — уходит на отдельный поток.
     */
    private void onNewMessage(TdApi.UpdateNewMessage update) {
        if (loop == null) {
            return;
        }
        TdApi.Message message = update.message;
        if (message.isOutgoing) {
            return;
        }

        long senderId = message.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0L;
        if (senderId == config.ownerId() && rest != null) {
            rest.wakeUp();   // сообщение владельца поднимает, даже если она отошла
        }
        Thread.ofVirtual().name("rin-incoming").start(() -> {
            try {
                receive(message, senderId);
            } catch (RuntimeException e) {
                log.error("Не удалось разобрать входящее", e);
            }
        });
    }

    private void receive(TdApi.Message message, long senderId) {
        log.debug("Входящее из чата {} от {}", message.chatId, senderId);
        // судим по чату, а не только по отправителю: в разрешённой группе пишут
        // и незнакомые, и это нормально — переписка там общая
        if (!lockdown.allows(telegram.chatCached(message.chatId), telegram.isContact(senderId))) {
            log.debug("Пропускаю сообщение из чата {} — он вне круга общения", message.chatId);
            return;
        }

        // заглушённый чат — просьба уделять ему меньше внимания, а не игнорировать
        // совсем: иначе о нём вообще ничего не узнать. Чтобы отсечь начисто,
        // чат убирают в архив
        if (telegram.isMuted(message.chatId) && random.nextDouble() < MUTED_IGNORE_CHANCE) {
            log.debug("Заглушённый чат {} — на этот раз пропускаю", message.chatId);
            return;
        }

        if (handledAsCommand(message, senderId)) {
            return;
        }
        notifyAbout(message, senderId);
    }

    private void noticeLater() {
        int seconds = settings == null ? config.noticeDelaySeconds() : settings.current().noticeDelaySeconds();
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(java.time.Duration.ofMillis(random.nextInt(seconds * 1000)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Разбирает непрочитанное, накопившееся пока её не было.
     * <p>
     * Telegram не присылает уведомления о том, что пришло до запуска. Без этого
     * всё, написанное за ночь или за время простоя, остаётся незамеченным
     * навсегда — она просто не узнает, что ей писали.
     * <p>
     * Сюда попадают и чаты, которые она оставила непрочитанными сама, не сумев
     * ответить: открытый чат Telegram считает прочитанным, и без отметки то
     * сообщение пропало бы молча.
     */
    private void catchUpOnUnread() {
        try {
            // от старых к новым: так разговор восстанавливается в том порядке,
            // в каком он шёл
            List<TdApi.Chat> chats = new java.util.ArrayList<>(telegram.chats(CATCH_UP_CHATS));
            java.util.Collections.reverse(chats);

            int found = 0;
            for (TdApi.Chat chat : chats) {
                if (!awaitsAnswer(chat) || chat.lastMessage == null || chat.lastMessage.isOutgoing) {
                    continue;
                }
                long senderId = chat.lastMessage.senderId instanceof TdApi.MessageSenderUser user ? user.userId : 0L;
                if (!lockdown.allows(chat, telegram.isContact(senderId))) {
                    continue;
                }
                notifyAbout(chat.lastMessage, senderId);
                found++;
            }
            if (found > 0) {
                log.info("Пока меня не было, написали в {} чат(ов)", found);
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось разобрать непрочитанное: {}", e.getMessage());
        }
    }

    /** Команды владельца — короткие служебные вопросы, ради них будить модель незачем. */
    private boolean handledAsCommand(TdApi.Message message, long senderId) {
        if (senderId != config.ownerId() || !(message.content instanceof TdApi.MessageText text)) {
            return false;
        }
        String command = text.text.text.strip().toLowerCase();
        if (!command.equals("/version")) {
            return false;
        }
        String answer = "Версия %s, Java %s, дневник: %d записей"
                .formatted(version(), Runtime.version().feature(), new Diary(config.diaryDir()).size());
        actions.sendMessage(message.chatId, answer, message.id);
        log.info("Ответ на команду: {}", answer);
        return true;
    }

    private static String version() {
        String version = RaineApp.class.getPackage().getImplementationVersion();
        return version == null ? "из исходников" : version;
    }

    private void notifyAbout(TdApi.Message message, long senderId) {
        // человек не бросается к телефону в ту же секунду: он замечает уведомление
        // не сразу. Без этой паузы ответ приходит подозрительно мгновенно
        noticeLater();

        TdApi.Chat chat = telegram.chat(message.chatId);
        ChatKind kind = ChatKind.of(chat);

        String text = switch (kind) {
            case DM -> Notifications.directMessage(chat);
            case GROUP -> Notifications.groupMessage(chat, telegram.nameOf(senderId), senderId);
            case CHANNEL -> Notifications.channelPost(chat);
        };

        // уведомление устарело, если такое же уже стоит в очереди по этому чату
        loop.discardMatching(Notifications.openingTag(chat.id));

        // набор живёт вместе с уведомлением: инструменты одного чата не должны
        // оставаться доступными, когда разговор идёт уже в другом
        Toolbox available = new Toolbox();
        available.add(tools.open(chat.id));

        // чат остаётся при уведомлении: не ответит — он снова повиснет
        // непрочитанным, и догон после перезапуска его найдёт
        Notification notice = new Notification(text, available).about(senderId).from(chat.id);

        // сообщение владельца и закреплённые чаты идут вне очереди: остальное
        // подождёт, а это — то, ради чего она вообще здесь
        boolean important = senderId == config.ownerId()
                || (behaviour().wakeOnPinnedChat() && telegram.isPinned(chat.id));
        if (important) {
            loop.submitFirst(notice);
            log.info("Уведомление из чата \"{}\" — вне очереди", chat.title);
        } else {
            loop.submit(notice);
            log.info("Уведомление из чата \"{}\" поставлено в очередь", chat.title);
        }
    }

    /**
     * Чат, ответа на сообщение в котором не досталось, снова висит
     * непрочитанным. Telegram снимет отметку сам, стоит чат прочитать, так что
     * она не может остаться навсегда.
     */
    private void backToUnread(Notification notification) {
        if (notification.chatId() == 0) {
            return;   // событие не из переписки: отмечать негде
        }
        TdApi.Chat chat = telegram.chat(notification.chatId());
        if (!stillUnanswered(chat)) {
            log.info("Чат \"{}\" уже ей отвечен — отметку не трогаю", chat.title);
            return;
        }
        actions.markUnread(notification.chatId());
        log.info("Чат \"{}\" снова непрочитанный: ответа на сообщение не было", chat.title);
    }

    /**
     * Отметку ставит только чат, где последним стоит чужое сообщение. Своё на
     * конце значит, что разговор пошёл дальше и она в нём сказала слово, а
     * поднимать переписку заново — значит ответить на вчерашнее сегодня.
     */
    static boolean stillUnanswered(TdApi.Chat chat) {
        return chat.lastMessage != null && !chat.lastMessage.isOutgoing;
    }

    /**
     * Ждёт ли чат ответа. Открытый чат Telegram считает прочитанным даже тогда,
     * когда она к нему не вернулась, — поэтому сюда попадает и отметка
     * «не прочитано», которую она оставляет за собой при таком отказе.
     */
    static boolean awaitsAnswer(TdApi.Chat chat) {
        return chat.unreadCount > 0 || chat.isMarkedAsUnread;
    }

    /**
     * Тумблер снимается на ходу: инструмент появляется в наборе или исчезает
     * из него. Просьба «не пользуйся» в промпте стоит токенов и не держит.
     */
    private void switchTool(Toolbox available, boolean on, String name,
                            java.util.function.Supplier<ru.rainedev.raine.core.Tool> tool, String label) {
        boolean there = available.names().contains(name);
        if (on == there) {
            return;
        }
        log.info("{} теперь: {}", label, on ? "включён" : "выключен");
        if (on) {
            available.add(tool.get());
        } else {
            available.remove(name);
        }
    }

    private static String describe(Lockdown.Mode mode) {
        return switch (mode) {
            case NONE -> "все";
            case CONTACTS_ONLY -> "только контакты";
            case OWNER_ONLY -> "только владелец";
        };
    }

    /** Повадки берутся из живых настроек: их правят чаще всего. */
    private Config.Behaviour behaviour() {
        return settings == null ? config.behaviour() : settings.current().behaviour();
    }

    /** Остановка по непоправимой причине: разбудить main, чтобы процесс завершился. */
    private void stopEverything() {
        try {
            close();
        } catch (Exception e) {
            log.warn("При остановке что-то пошло не так", e);
        }
    }

    /**
     * Остановка по-человечески: начатый ход доводится до конца, а всё сказанное
     * за сеанс уходит в память. Оборвать посреди хода — значит потерять разговор
     * целиком: он живёт в оперативной памяти до самой записи в дневник.
     */
    @Override
    public void close() throws Exception {
        if (!closing.compareAndSet(false, true)) {
            return;   // остановка может прийти и сигналом, и из main разом
        }
        log.info("Останавливаюсь. Дайте время сохранить разговор");

        if (settings != null) {
            settings.close();
        }
        if (spontaneity != null) {
            spontaneity.close();   // новых порывов не заводим
        }
        if (loop != null) {
            loop.stop();
            if (!loop.awaitStopped(SHUTDOWN_GRACE)) {
                log.warn("Ход не успел закончиться за {} — сохраняю как есть", SHUTDOWN_GRACE);
            }
            rememberConversation.accept("Останавливаюсь");
        }
        // прощаемся с Telegram, только если он ещё слушает: он закрывается сам
        // и своим чередом, а вызов в закрывающийся клиент повисает надолго
        if (!telegramClosing) {
            if (tools != null) {
                tools.closeOpened();   // выходим из чатов, а не бросаем их открытыми
            }
            if (actions != null) {
                actions.setOnline(false);
            }
        } else {
            log.debug("Telegram уже закрывается — прощаться через него не буду");
        }
        if (brain != null) {
            brain.interrupt();
        }
        // если Telegram закрывается сам, ждать от него подтверждения бессмысленно:
        // ответа не будет, а ожидание длится полторы минуты на пустом месте
        if (telegramClosing) {
            log.debug("Telegram закрывается сам — подтверждения не жду");
        } else {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("Telegram закрылся не попрощавшись: {}", e.getClass().getSimpleName());
            }
        }
        log.info("Всё сохранено, до встречи");
    }
}
