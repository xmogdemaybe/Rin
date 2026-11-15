package ru.rainedev.raine.tools;

import it.tdlight.jni.TdApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.phone.ChatScreen;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.telegram.Telegram;
import ru.rainedev.raine.telegram.TelegramActions;
import ru.rainedev.raine.vision.TelegramMedia;

/**
 * Стикеры. Вынесены отдельно, потому что их три и они самостоятельны:
 * посмотреть свои, забрать понравившийся чужой, отправить.
 */
public final class StickerTools {

    private static final Logger log = LoggerFactory.getLogger(StickerTools.class);

    /** Столько стикеров помещается в избранном и недавних. */
    private static final int LIMIT = 40;

    /** Сколько из них влезает в снимок чата: дальше список становится шумом. */
    static final int FAVOURITES = 18;

    private final Telegram telegram;
    private final TelegramActions actions;
    private final ChatScreen screen;
    private TelegramMedia media;

    /** Готовый блок избранного для снимка чата; пуст, пока не прогрето. */
    private volatile String favourites = "";

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
        List<TdApi.Sticker> saved = telegram.savedStickers(LIMIT);
        // избранное у аккаунта бывает пустым, а паки стоят: добрать из первого
        // установленного дешевле, чем угадывать по одному эмодзи
        List<TdApi.Sticker> known = saved.size() >= FAVOURITES ? saved
                : telegram.stickerSets().stream().findFirst()
                        .flatMap(set -> telegram.stickerSet(set.name))
                        .map(set -> mergeFresh(saved, List.of(set.stickers)))
                        .orElse(saved);
        List<TdApi.Sticker> shown = known.stream().limit(FAVOURITES).toList();
        favourites = favouritesBlock(shown, FAVOURITES,
                sticker -> media == null ? "" : media.stickerWords(sticker));
        return shown.size();
    }

    /**
     * Компактный список своего стикера: номер, эмодзи и одна строка того, что
     * о нём известно. Описание берётся из уже разгляденного — платить зрением
     * за каждый открытый чат незачем.
     */
    static String favouritesBlock(List<TdApi.Sticker> stickers, int cap,
                                  java.util.function.Function<TdApi.Sticker, String> describe) {
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
        return out.append("</favourite_stickers>\n").toString();
    }

    /** Сколько символов выделено на описание стикера в полке: дальше шум. */
    private static final int CAPTION_LIMIT = 160;

    /**
     * О чём стикер — одной строкой. Поля задаёт собственный промпт зрения
     * ({@code sticker_to_text.md}): сначала что на картинке и зачем это шлют, а
     * если разбор не вышелся — первая строка как есть. Чужие скобки остаются
     * текстом: полка встаёт внутрь снимка чата.
     */
    static String caption(String described) {
        if (described == null || described.isBlank()) {
            return "";
        }
        String scene = field(described, "VisibleContent");
        String purpose = field(described, "CommunicativeMeaning");
        if (purpose.isEmpty()) {
            purpose = field(described, "Emotion");
        }
        String line = scene.isEmpty() ? purpose : (purpose.isEmpty() ? scene : scene + " — " + purpose);
        if (line.isBlank()) {
            line = firstLine(described);
        }
        line = Markup.body(line).strip();
        return line.length() <= CAPTION_LIMIT ? line : line.substring(0, CAPTION_LIMIT).strip() + "…";
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
                + "Use this before #sticker_send.", arguments -> {
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
        StringBuilder out = new StringBuilder("<stickers>\n");
        for (TdApi.Sticker sticker : stickers) {
            out.append("<sticker sticker_id=\"").append(sticker.id)
                    .append("\" emoji=\"").append(Markup.attribute(sticker.emoji))
                    .append("\">");
            if (media != null) {
                out.append('\n').append(media.describeSticker(sticker).strip()).append('\n');
            }
            out.append("</sticker>\n");
        }
        return out.append("</stickers>").toString();
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
                .requiredInteger("sticker_id", "Id of the sticker to save")
                .mutating()
                .build(arguments -> {
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
                    if (stickerId == 0) {
                        return "Provide the sticker_id you want to save.";
                    }
                    java.util.Optional<Telegram.StickerRef> sticker = telegram.sticker(stickerId);
                    if (sticker.isEmpty()) {
                        return "You have not seen that sticker. Use sticker_list, open a pack with "
                                + "sticker_pack, or save one you were sent.";
                    }
                    actions.saveSticker(sticker.get());
                    log.info("Стикер {} сохранён", stickerId);
                    // сохранить молча — странно: человек в такой момент говорит «о, забрала себе»
                    return "Sticker saved. Tell them you liked it and kept it.";
                });
    }

    /** Стикер вместо слов — иногда точнее любого текста. */
    public Tool send(long chatId) {
        return Tool.named("sticker_send")
                .describedAs("Sends one of your saved stickers. Call sticker_list first to see what you have.")
                .requiredInteger("sticker_id", "Id of the sticker to send")
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
                    long stickerId = ru.rainedev.raine.core.Numbers.longAt(arguments, "sticker_id", 0);
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
