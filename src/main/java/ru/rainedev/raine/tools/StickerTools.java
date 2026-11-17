package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.vision.Glimpses;
import ru.rainedev.raine.vision.TelegramMedia;

/**
 * Стикеры. Вынесены отдельно, потому что их три и они самостоятельны:
 * посмотреть свои, забрать понравившийся чужой, отправить.
 */
public final class StickerTools {

    private static final Logger log = LoggerFactory.getLogger(StickerTools.class);

    /** Столько стикеров помещается в избранном и недавних. */
    private static final int LIMIT = 40;

    /** Сколько стикеров за один список досматривать зрением; кэш места не стоит. */
    static final int GLIMPSES = 6;

    /** Сколько из них влезает в снимок чата: дальше список становится шумом. */
    static final int FAVOURITES = 18;

    /** Столько мест полки отдаётся ротации из установленных паков. */
    static final int ROTATION = 8;

    /** Столько мест остаётся избранному и недавнему. */
    static final int SHELF_HEAD = FAVOURITES - ROTATION;

    /** Сколько имён паков влезает в строку: дальше она перестаёт читаться. */
    static final int PACK_NAMES = 12;

    private final Telegram telegram;
    private final TelegramActions actions;
    private final ChatScreen screen;
    private TelegramMedia media;

    /** Готовый блок избранного для снимка чата; пуст, пока не прогрето. */
    private volatile String favourites = "";

    /** Разгляденные стикеры установленных паков: из них берётся ротация полки. */
    private volatile List<TdApi.Sticker> pool = List.of();

    /** Короткие имена установленных паков — тем и зовут #sticker_pack. */
    private volatile String packNames = "";

    /** Сколько стикеров встало на полку: об этом докладывает прогрев. */
    private volatile int shelfSize;

    /** Сдвиг окна ротации: следующий заход берёт других стикеров паков. */
    private final java.util.concurrent.atomic.AtomicLong rotationMark =
            new java.util.concurrent.atomic.AtomicLong();

    /** Отправлять стикер можно ровно туда же, куда и обычное сообщение. */
    private java.util.function.LongPredicate allowed = chatId -> true;

    public StickerTools(Telegram telegram, TelegramActions actions, ChatScreen screen) {
        this.telegram = telegram;
        this.actions = actions;
        this.screen = screen;
    }

    public void allowedTo(java.util.function.LongPredicate allowed) {
        this.allowed = allowed;
    }

    public void media(TelegramMedia media) {
        this.media = media;
    }

    /** Что встанет в снимок открытого чата; пусто, пока реестр не прогрет. */
    public String favourites() {
        return favourites;
    }

    /**
     * Прогрев перед разговорами: реестр стикеров и их описания.
     * <p>
     * Без него отправка стикера стоит трёх обращений к модели — посмотреть
     * список, открыть пак, отправить, — а каждое тянет за собой весь контекст
     * разговора. Плюс реестр живёт в памяти процесса: после рестарта первый же
     * «отправь тот, что в прошлом месяце» упирался в «You have not seen that
     * sticker». Делается в фоне и один раз на запуск.
     *
     * @return сколько стикеров встанет в снимок чата
     */
    public int warmUp() {
        build(true, true);
        return shelfSize;
    }

    /**
     * Пересборка на ходу: после сохранённого стикера, открытого пака, ночного
     * досмотра и сброса контекста.
     * <p>
     * Только по памяти и по тому, что уже лежит на диске: полка меняется
     * посреди разговора, и платить за неё скачиванием и зрением незачем.
     */
    public void refresh() {
        build(false, false);
    }

    /** Пересборка, когда состав паков мог измениться: их пак открыли или досмотрели. */
    public void reshelve() {
        build(false, true);
    }

    /**
     * Ночной досмотр установленных паков: разглядеть то, до чего в разговоре
     * не дошло зрение. Днём такой просмотр стоит минуту молчания на пак,
     * ночью — ничего.
     *
     * @param limit   сколько стикеров за ночь разглядеть
     * @param wokeUp  проснулась ли: сверяется перед каждым шагом
     * @return сколько свежих просмотров зрение потратило
     */
    public int warmPacks(int limit, java.util.function.BooleanSupplier wokeUp) {
        if (media == null || limit < 1) {
            return 0;   // без глаз досматривать нечем, а ноль за ночь — не досмотр
        }
        List<List<TdApi.Sticker>> packs = new java.util.ArrayList<>();
        for (TdApi.StickerSetInfo info : telegram.stickerSets()) {
            telegram.stickerSet(info.name).ifPresent(set -> packs.add(List.of(set.stickers)));
        }
        int looked = warmPacks(packs, new Night(), limit, wokeUp);
        if (looked > 0) {
            log.info("Стикеров за ночь досмотрено: {}", looked);
            // досмотренное встаёт на полку сразу: утром оно должно быть видно без запроса
            reshelve();
        }
        return looked;
    }

    /** Чем ночной проход смотрит один стикер: шов нужен проверкам лимита и отсечки. */
    interface Sight {
        /** Уже разгляден: кэш ничего не стоит. */
        boolean ready(TdApi.Sticker sticker);

        /** Смотреть нечем до всякой модели: файла нет, он недокачан или недавно не разгляделся. */
        boolean blocked(TdApi.Sticker sticker);

        /** Свежий взгляд: скачивает и просит зрение. */
        void look(TdApi.Sticker sticker, Glimpses budget);
    }

    /** Досмотр через зрение и файлы: тот же {@link Sight}, но без швов. */
    private final class Night implements Sight {
        @Override
        public boolean ready(TdApi.Sticker sticker) {
            return media.described(sticker);
        }

        @Override
        public boolean blocked(TdApi.Sticker sticker) {
            return media.localStickerFile(sticker).filter(media::unviewable).isPresent();
        }

        @Override
        public void look(TdApi.Sticker sticker, Glimpses budget) {
            media.describeSticker(sticker, budget);
        }
    }

    /**
     * Ночной проход по установленным пакам.
     * <p>
     * Описанное и недоступное локально пропускают молча, без закачки; лимит
     * считают только по свежим взглядам. Дальнейшие взгляды закрывает само
     * зрение: после первого отказа {@code Glimpses} больше ничего не разрешает, и
     * проход сам останавливается — биться об исчерпанную квоту до утра
     * означает сотни оплаченных попыток впустую.
     *
     * @return сколько свежих взглядов потрачено
     */
    static int warmPacks(List<List<TdApi.Sticker>> packs, Sight sight, int limit,
                         java.util.function.BooleanSupplier wokeUp) {
        Glimpses budget = new Glimpses(limit);
        for (List<TdApi.Sticker> pack : packs) {
            for (TdApi.Sticker sticker : pack) {
                if (wokeUp.getAsBoolean() || !budget.allows(false)) {
                    return budget.spent();
                }
                if (sight.ready(sticker) || sight.blocked(sticker)) {
                    continue;
                }
                sight.look(sticker, budget);
            }
        }
        return budget.spent();
    }

    /**
     * @param look      досматривать ли зрением недостающее (только на старте)
     * @param withPacks перебирать ли установленные паки заново
     */
    private void build(boolean look, boolean withPacks) {
        if (withPacks) {
            List<TdApi.StickerSetInfo> sets = telegram.stickerSets();
            packNames = namesOf(sets);
            pool = poolOf(sets);
        }
        List<TdApi.Sticker> head = telegram.savedStickers(LIMIT);
        List<TdApi.Sticker> mine = head.size() > SHELF_HEAD ? head.subList(0, SHELF_HEAD) : head;
        List<TdApi.Sticker> shown = mergeFresh(mine, rotated(pool)).stream().limit(FAVOURITES).toList();
        shelfSize = shown.size();
        // платный взгляд положен только тому, что встанет на полку, и только на
        // старте: перебирать зрением весь пак заранее незачем
        Glimpses budget = new Glimpses(look ? GLIMPSES : 0);
        favourites = favouritesBlock(shown, FAVOURITES, sticker -> {
            if (media == null) {
                return "";
            }
            String ready = media.shelfWords(sticker);
            if (!ready.isBlank() || !look) {
                return ready;   // на ходу — что есть на диске, то и полка
            }
            return budget.allows(false) ? media.stickerWords(sticker, budget) : "";
        }, packNames);
    }

    /**
     * Ротация полки живёт разгляденными стикерами паков: чего нет в кэше, то на
     * полку не попадёт, а досмотрит его ночной проход. Заодно каждый стикер
     * пака попадает в реестр файлов — без него {@code sticker_send} отказал бы
     * по строчке, взятой с этой же полки.
     */
    private List<TdApi.Sticker> poolOf(List<TdApi.StickerSetInfo> sets) {
        if (media == null) {
            return List.of();
        }
        List<TdApi.Sticker> ready = new java.util.ArrayList<>();
        for (TdApi.StickerSetInfo info : sets) {
            Optional<TdApi.StickerSet> found = telegram.stickerSet(info.name);
            if (found.isEmpty()) {
                continue;
            }
            for (TdApi.Sticker sticker : found.get().stickers) {
                if (media.described(sticker)) {
                    ready.add(sticker);
                }
            }
        }
        return List.copyOf(ready);
    }

    private List<TdApi.Sticker> rotated(List<TdApi.Sticker> ready) {
        return rotated(ready, rotationMark.getAndIncrement());
    }

    /**
     * Полка берёт следующий кусок списка, а не тот, что показала в прошлый раз:
     * иначе восемь мест вечно держала бы одна и та же восьмёрка.
     */
    static List<TdApi.Sticker> rotated(List<TdApi.Sticker> ready, long mark) {
        if (ready.isEmpty()) {
            return List.of();
        }
        List<TdApi.Sticker> ordered = ready.stream()
                .sorted(java.util.Comparator.comparingLong(sticker -> sticker.id))
                .toList();
        int start = (int) (Math.floorMod(mark, ordered.size()));
        List<TdApi.Sticker> picked = new java.util.ArrayList<>();
        for (int taken = 0; taken < ROTATION && taken < ordered.size(); taken++) {
            picked.add(ordered.get((start + taken) % ordered.size()));
        }
        return picked;
    }

    /** Имена установленных паков для строки на полке. */
    static String namesOf(List<TdApi.StickerSetInfo> sets) {
        return String.join(", ", sets.stream().map(set -> set.name)
                .filter(name -> name != null && !name.isBlank())
                .limit(PACK_NAMES).toList());
    }

    /**
     * Компактный список своего стикера: номер, эмодзи и одна строка того, что
     * о нём известно. Описание берётся из уже разгляденного — платить зрением
     * за каждый открытый чат незачем.
     */
    static String favouritesBlock(List<TdApi.Sticker> stickers, int cap,
                                  java.util.function.Function<TdApi.Sticker, String> describe) {
        return favouritesBlock(stickers, cap, describe, "");
    }

    /**
     * @param packs короткие имена установленных паков; без них она не знает,
     *              что у неё есть и что открывать {@code #sticker_pack}
     */
    static String favouritesBlock(List<TdApi.Sticker> stickers, int cap,
                                  java.util.function.Function<TdApi.Sticker, String> describe,
                                  String packs) {
        List<TdApi.Sticker> shown = stickers.stream().limit(cap).toList();
        if (shown.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n\n<favourite_stickers>\n")
                .append("Your own stickers. Send one with #sticker_send(sticker_id) — no need to look them up.\n");
        for (TdApi.Sticker sticker : shown) {
            out.append("  - sticker_id=\"").append(sticker.id)
                    .append("\" emoji=\"").append(Markup.attribute(sticker.emoji)).append('"');
            String looks = caption(describe.apply(sticker));
            if (!looks.isBlank()) {
                out.append(": ").append(looks);
            }
            out.append('\n');
        }
        if (stickers.size() > shown.size()) {
            out.append("  %d more are yours too — #sticker_list shows them all.\n"
                    .formatted(stickers.size() - shown.size()));
        }
        if (packs != null && !packs.isBlank()) {
            out.append("Installed packs: ").append(Markup.body(packs))
                    .append(". Open one with #sticker_pack(name) to keep its stickers.\n");
        }
        return out.append("</favourite_stickers>\n").toString();
    }

    /** Сколько символов выделено на описание стикера в полке: дальше шум. */
    private static final int CAPTION_LIMIT = 220;

    /**
     * О чём стикер — одной строкой. Поля задаёт собственный промпт зрения
     * ({@code sticker_to_text.md}): первым идёт смысл, потом сюжет — смысл
     * короче, и при обрезке терять надо подробности, а не назначение жеста.
     * Чужие скобки остаются текстом: полка встаёт внутрь снимка чата.
     */
    static String caption(String described) {
        if (described == null || described.isBlank()) {
            return "";
        }
        String purpose = field(described, "CommunicativeMeaning");
        if (purpose.isEmpty()) {
            purpose = field(described, "Emotion");
        }
        String scene = field(described, "VisibleContent");
        String line = purpose.isEmpty() ? scene : (scene.isEmpty() ? purpose : purpose + " — " + scene);
        if (line.isBlank()) {
            line = firstLine(described);
        }
        line = Markup.body(line).strip();
        return wholeWords(line);
    }

    /** Обрезка по границе слова: полуслово в полке читается как обрыв мысли. */
    private static String wholeWords(String line) {
        if (line.length() <= CAPTION_LIMIT) {
            return line;
        }
        String cut = line.substring(0, CAPTION_LIMIT);
        int space = cut.lastIndexOf(' ');
        return (space > 0 ? cut.substring(0, space) : cut).strip() + "…";
    }

    /** Значение поля вида «- VisibleContent: …». */
    private static String field(String described, String name) {
        String wanted = name.toLowerCase() + ":";
        for (String raw : described.lines().toList()) {
            String line = raw.strip().replaceFirst("^[-*] ", "").toLowerCase();
            if (line.startsWith(wanted)) {
                return raw.strip().replaceFirst("^[-*] ", "").substring(wanted.length()).strip();
            }
        }
        return "";
    }

    /** Первая содержательная строка — на случай, когда полей разбор не дал. */
    private static String firstLine(String described) {
        return described.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .map(line -> line.replaceFirst("^[-*] ", ""))
                .orElse("");
    }

    /** Избранные первыми: пак добирает то, чего не хватает, не повторяя их. */
    static List<TdApi.Sticker> mergeFresh(List<TdApi.Sticker> favourites, List<TdApi.Sticker> pack) {
        List<TdApi.Sticker> merged = new java.util.ArrayList<>(favourites);
        for (TdApi.Sticker sticker : pack) {
            if (merged.stream().noneMatch(its -> its.id == sticker.id)) {
                merged.add(sticker);
            }
        }
        return merged;
    }

    /**
     * Список своих стикеров с описанием каждого.
     * <p>
     * Одного эмодзи мало: по нему не выбрать, какой стикер уместен сейчас.
     * Описания берутся зрением и кэшируются, поэтому список стоит дорого
     * только в первый раз.
     */
    public Tool list() {
        return Tool.simple("sticker_list", "Returns your saved stickers with a description of each. "
                + "Your <favourite_stickers> shelf in the screen already lists the usual ones: call this "
                + "only when the shelf has nothing that fits.", arguments -> {
                    List<TdApi.Sticker> stickers = telegram.savedStickers(LIMIT);
                    if (stickers.isEmpty()) {
                        // паки могут быть и при пустом избранном: тогда выбор есть,
                        // просто он лежит не здесь
                        String packs = packsOfMine();
                        return packs.isEmpty()
                                ? "You have no saved stickers yet."
                                : "You have no favourite stickers yet, but you have packs." + packs;
                    }
                    return describe(stickers) + packsOfMine();
                });
    }

    /**
     * Целый пак по ссылке. Присланный пак — обычный подарок в переписке,
     * и брать из него можно было бы только то, что человек прислал поштучно:
     * поиск по имени открывает весь набор разом.
     * <p>
     * Открытый пак ещё и ставится себе, поэтому это действие: ссылку мог
     * прислать кто угодно, а в безопасном режиме у аккаунта ничего не должно
     * появляться по чужой просьбе.
     */
    public Tool pack() {
        return Tool.named("sticker_pack")
                .describedAs("Opens a whole sticker pack and keeps it: pass a t.me/addstickers/... link "
                        + "or just the pack name. Returns every sticker in it, ready for #sticker_send.")
                .requiredString("name", "Pack link or its short name")
                .mutating()
                .build(arguments -> {
                    String name = arguments.path("name").asText("");
                    var found = telegram.stickerSet(name);
                    if (found.isEmpty()) {
                        return "There is no such sticker pack. Check the link or the name.";
                    }
                    TdApi.StickerSet set = found.get();
                    if (!set.isInstalled) {
                        // ставим себе: иначе пак живёт до перезапуска и в Telegram его не видно
                        telegram.installStickerSet(set.id);
                    }
                    log.info("Пак {} ({}) открыт, стикеров: {}", set.name, set.title, set.stickers.length);
                    // открытый пак сразу попадает и на полку, и в строку имён:
                    // иначе «я его добавила» снова останется незакрытым обещанием
                    reshelve();
                    List<TdApi.Sticker> shown = set.stickers.length > LIMIT
                            ? List.of(set.stickers).subList(0, LIMIT)
                            : List.of(set.stickers);
                    String tail = set.stickers.length > LIMIT
                            ? "\nFirst %d of %d stickers.".formatted(LIMIT, set.stickers.length)
                            : "";
                    return packOpened(set.title, describe(shown), tail);
                });
    }

    /**
     * Заголовок пака сочиняет тот, кто его собрал, а ссылку могла прислать
     * кто угодно. Ответ инструмента модель читает как разметку — поэтому
     * заголовок входит в него текстом, одной строкой.
     */
    static String packOpened(String title, String stickers, String tail) {
        return "Pack \"%s\" is yours now.\n%s%s".formatted(Markup.attribute(title), stickers, tail);
    }

    /** Список с описаниями — общий вид и для своих стикеров, и для пака. */
    private String describe(List<TdApi.Sticker> stickers) {
        return describe(stickers, media == null ? null
                : (sticker, budget) -> media.describeSticker(sticker, budget));
    }

    /**
     * <p>
     * Свежих просмотров на один список отводится {@link #GLIMPSES}: пак в тридцать
     * с лишним стикеров стоит минуты, а когда квота кончилась, каждый файл ещё и
     * бьётся об отказ трижды. То, что уже разглядено и лежит в кэше, лимит не тратит.
     *
     * @param look чем смотреть на стикер; пусто — описания нет, и в списке
     *             остаётся только эмодзи
     */
    static String describe(List<TdApi.Sticker> stickers, Look look) {
        Glimpses budget = new Glimpses(GLIMPSES);
        StringBuilder out = new StringBuilder("<stickers>\n");
        for (TdApi.Sticker sticker : stickers) {
            out.append("<sticker sticker_id=\"").append(sticker.id)
                    .append("\" emoji=\"").append(Markup.attribute(sticker.emoji))
                    .append("\">\n");
            String looks = look == null ? "" : look.of(sticker, budget).strip();
            out.append(looks.isBlank() ? UNSEEN : looks).append("\n</sticker>\n");
        }
        return out.append("</stickers>").toString();
    }

    /** Как выглядит стикер, до которого в этом списке не дошло зрение. */
    static final String UNSEEN = "no description yet — emoji only";

    /** Чем список смотрит один стикер: шов нужен проверкам бюджета. */
    interface Look {
        String of(TdApi.Sticker sticker, Glimpses budget);
    }


    /**
     * Имена своих паков идут следом за стикерами — без них она не знает,
     * что у неё вообще есть, и открывать ей нечего. Описания здесь не нужны:
     * содержимое покажет #sticker_pack, а список должен оставаться дешёвым.
     */
    private String packsOfMine() {
        List<TdApi.StickerSetInfo> sets = telegram.stickerSets();
        if (sets.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n<sticker_packs>\n");
        for (TdApi.StickerSetInfo set : sets) {
            out.append("<pack name=\"").append(Markup.attribute(set.name))
                    .append("\" title=\"").append(Markup.attribute(set.title))
                    .append("\" stickers=\"").append(set.size).append("\" />\n");
        }
        return out.append("</sticker_packs>\nOpen any of them with #sticker_pack.").toString();
    }

    /** Понравившийся чужой стикер можно забрать себе. */
    public Tool save() {
        return Tool.named("sticker_save")
                .describedAs("Saves a sticker so you can use it later. Use this if you liked a sticker someone sent.")
                .requiredString("sticker_id", "Sticker id exactly as printed. Keep it as text: "
                        + "sent as a number it loses its last digits.")
                .mutating()
                .build(arguments -> {
                    java.util.Optional<Long> asked =
                            ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id");
                    if (asked.isEmpty()) {
                        return "Send the sticker_id exactly as it is printed, as text — as a number it "
                                + "arrives rounded and would save another sticker.";
                    }
                    long stickerId = asked.get();
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "You have not seen that sticker. Use sticker_list, open a pack with "
                                + "sticker_pack, or save one you were sent.";
                    }
                    actions.saveSticker(sticker.get());
                    log.info("Стикер {} сохранён", stickerId);
                    // полка обязана догнать сохранённое: иначе «забрала себе»
                    // оборачивается «такого стикера у меня нет» в том же разговоре
                    refresh();
                    // сохранить молча — странно: человек в такой момент говорит «о, забрала себе»
                    return "Sticker saved. Tell them you liked it and kept it.";
                });
    }

    /** Стикер вместо слов — иногда точнее любого текста. */
    public Tool send(long chatId) {
        return Tool.named("sticker_send")
                .describedAs("Sends one sticker. Take sticker_id from your <favourite_stickers> shelf in "
                        + "the screen; call sticker_list only when that shelf has nothing that fits.")
                .requiredString("sticker_id", "Sticker id exactly as printed, e.g. 541175087705903941. "
                        + "Keep it as text: sent as a number it loses its last digits.")
                .optionalString("reply_to_message_id",
                        "Optional. Quote a specific message — a sticker in reply to something said earlier.")
                .mutating()
                .build(arguments -> {
                    // круг общения проверяется и здесь: открыть чат — одно,
                    // а отправить в него — другое, и запрет должен работать в обоих
                    if (!allowed.test(chatId)) {
                        log.info("Стикер в чат {} вне круга общения не отправлен", chatId);
                        return "You cannot send anything to that chat.";
                    }
                    java.util.Optional<Long> asked =
                            ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id");
                    if (asked.isEmpty()) {
                        // id, пришедший дробным числом, — это уже соседний стикер
                        // того же пака; угадывать, какой она имела в виду, нечем
                        return "Send the sticker_id exactly as it is printed in <favourite_stickers>, as text.";
                    }
                    long stickerId = asked.get();
                    // отправить можно всё, что ей известно: избранное, недавнее,
                    // открытый пак, присланный только что стикер. Проверка по одному
                    // избранному отсекала целые паки — а именно ими и пользуются
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "You don't have this sticker. Call sticker_list to see yours, "
                                + "or sticker_pack to open a pack.";
                    }
                    Long replyTo = ru.rainedev.raine.core.Numbers.longAt(arguments, "reply_to_message_id")
                            .filter(id -> telegram.message(chatId, id).isPresent())
                            .orElse(null);
                    actions.typing(chatId);
                    long temporary = actions.sendSticker(chatId, sticker.get(), replyTo);
                    // номер, который придёт в истории, — настоящий: по временному
                    // снимок чата не узнал бы своё же и напечатал стикер заново
                    long sent = telegram.confirmedId(temporary);
                    log.info("Стикер {} отправлен в чат {}", stickerId, chatId);
                    if (screen != null && sent != 0) {
                        screen.markSent(chatId, sent);
                    }
                    return "Sticker sent.";
                });
    }
}
