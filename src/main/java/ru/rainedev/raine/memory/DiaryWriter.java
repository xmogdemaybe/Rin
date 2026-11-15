package ru.rainedev.raine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.NotificationLoop;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.phone.ChatScreen;

/**
 * Переносит разговор в долгую память: просит модель пересказать его записями
 * и сохраняет то, чего в дневнике ещё нет.
 */
public final class DiaryWriter {

    private static final Logger log = LoggerFactory.getLogger(DiaryWriter.class);

    /** Разделитель между записями в ответе модели. */
    private static final String SEPARATOR = "---";

    /** Короче этого — обрывок, а не воспоминание. */
    private static final int MIN_ENTRY_LENGTH = 20;

    /** Дословная цитата: в «ёлочках» или в прямых кавычках, но не через всю страницу. */
    private static final Pattern QUOTE = Pattern.compile("«([^»\\n]+)»|\"([^\"\\n]+)\"");

    private static final int MAX_ATTEMPTS = 3;

    /** Блоками памяти размечен и ответ воспоминаний, и повод порыва. */
    private static final String RECALL_OPEN = "<your_diary_page";
    private static final String RECALL_CLOSE = "</your_diary_page>";

    /** Пояснение к разметке, которое снимок чата ставит под своими сообщениями. */
    private static final String NOTE_OPEN = "<instructions>";
    private static final String NOTE_CLOSE = "</instructions>";

    /** Полка своих стикеров: вклеивается в снимок чата, к разговору отношения не имеет. */
    private static final String SHELF_OPEN = "<favourite_stickers>";
    private static final String SHELF_CLOSE = "</favourite_stickers>";

    /** Её слова уходят в аргументах вызова, а не в тексте ответа. */
    private static final String SEND_TOOL = "send_telegram_message";
    private static final String REACT_TOOL = "react_with_emoji";

    /**
     * Поступки, которые меняют мир вокруг разговора, а не только текст в нём.
     * Пересказчику они нужны как факт: по одним её словам он записывал
     * «подписалась и посмотрела», хотя вызова не было.
     */
    private static final java.util.Map<String, String> ACTIONS = java.util.Map.ofEntries(
            java.util.Map.entry("open", "opened chat"),
            java.util.Map.entry("open_chat_by_id", "opened chat"),
            java.util.Map.entry("join_channel", "subscribed to channel"),
            java.util.Map.entry("leave_channel", "left channel"),
            java.util.Map.entry("sticker_send", "sent a sticker"),
            java.util.Map.entry("sticker_save", "saved a sticker"),
            java.util.Map.entry("forward_message", "forwarded messages"),
            java.util.Map.entry("post_to_channel", "published a channel post"),
            java.util.Map.entry("remove_message", "deleted messages"),
            java.util.Map.entry("edit_message_text", "edited a message"),
            java.util.Map.entry("remove_and_ban_chat", "removed and banned a chat"),
            java.util.Map.entry("save_contact", "saved a contact"),
            java.util.Map.entry("note_trust", "noted trust"));

    /** Чем поступок опознаётся: первый найденный номер идёт в строку главным. */
    private static final List<String> ACTION_IDS =
            List.of("chat_id", "to_chat_id", "sticker_id", "message_id", "message_ids", "user_id");

    /** Служебный формат ответа (<outputFormatting>): про намерения, а не про разговор. */
    private static final Pattern MENTAL_NOTE = Pattern.compile(
            "^[ \\t]*-[ \\t]*(?:thought|emotion|to remember|intention)[ \\t]*:.*$", Pattern.MULTILINE);

    private static final com.fasterxml.jackson.databind.ObjectMapper ARGUMENTS =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final Diary diary;
    private final LlmClient llm;
    private final java.util.function.Supplier<String> savePrompt;
    private final double plagiarismThreshold;

    public DiaryWriter(Diary diary, LlmClient llm, String savePrompt, double plagiarismThreshold) {
        this(diary, llm, () -> savePrompt, plagiarismThreshold);
    }

    public DiaryWriter(Diary diary, LlmClient llm, java.util.function.Supplier<String> savePrompt,
                       double plagiarismThreshold) {
        this.diary = diary;
        this.llm = llm;
        this.savePrompt = savePrompt;
        this.plagiarismThreshold = plagiarismThreshold;
    }

    /** @return сохранённые записи; пусто, если сохранять оказалось нечего */
    public List<DiaryEntry> save(String systemPrompt, List<Message> context) {
        return save(systemPrompt, context, List.of(), Origin.GENERAL, false);
    }

    /** A failed summary must leave the original conversation available for another attempt. */
    public List<DiaryEntry> saveStrict(String systemPrompt, List<Message> context) {
        return saveStrict(systemPrompt, context, List.of(), Origin.GENERAL);
    }

    /**
     * @param signals заметки о себе за этот разговор; уходят в первую же
     *                сохранённую запись, рядом с её эмбеддингом
     * @param origin  откуда разговор: метка и чат ложатся на каждую запись, и уже
     *                по ним память решает, кому их можно показывать
     */
    public List<DiaryEntry> saveStrict(String systemPrompt, List<Message> context, List<TraitSignal> signals,
                                       Origin origin) {
        return save(systemPrompt, context, signals, origin, true);
    }

    private List<DiaryEntry> save(String systemPrompt, List<Message> context,
                                  List<TraitSignal> signals, Origin origin, boolean strict) {
        List<Message> conversation = conversationOnly(context);
        if (conversation.isEmpty()) {
            if (strict) {
                throw new NotRetellable("от разговора остались одни служебные вставки");
            }
            return List.of();
        }

        String summary;
        try {
            summary = askForSummary(systemPrompt, conversation);
        } catch (NotRetellable e) {
            // обычный ход не тонет из-за вердикта фильтра: просто нечего сохранять,
            // а вот отложенный разговор по этому же признаку уберётся из очереди
            if (strict) {
                throw e;
            }
            return List.of();
        }
        if (summary.isBlank()) {
            log.warn("Пересказ разговора не получен — записи не сохранены");
            if (strict) throw new IllegalStateException("Пересказ не получен, исходный разговор нужно сохранить");
            return List.of();
        }
        if (strict && Arrays.stream(summary.replace("- --", SEPARATOR).replace("-- -", SEPARATOR)
                .split(SEPARATOR)).noneMatch(part -> part.strip().length() >= MIN_ENTRY_LENGTH)) {
            throw new IllegalStateException("Пересказ слишком короткий, исходный разговор нужно сохранить");
        }
        return persist(summary, signals, origin);
    }

    /**
     * Голый разговор: без того, что дописала в контекст сама система. Страница
     * памяти, пояснения к снимку чата и напоминания цикла занимают половину
     * дампа (в разговоре от 9 ноября 90 579 символов, из них 44 775 — обвязка),
     * и в пересказе её служебные фразы стоят наравне с человеческими.
     * <p>
     * Обратно собирается то, чего в тексте сообщений нет: реплики и реакции
     * Raine из аргументов её вызовов и снимок открытого чата, где до уведомления
     * видно всё, что сказал человек. Без этого в дневник уходил конспект её
     * намерений вместо разговора.
     * <p>
     * Средней памяти срез не касается: ей нужна целая переписка, а не пересказ.
     */
    /** Есть ли в разговоре слова, кроме того, что дописал в него цикл. */
    static boolean hasWords(List<Message> context) {
        return !conversationOnly(context).isEmpty();
    }

    private static List<Message> conversationOnly(List<Message> context) {
        List<Message> kept = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (Message message : context) {
            if (message.role() == Message.Role.TOOL && !isChatScreen(message.content())) {
                // «Success», «message_id=…», выдачи #ask — протокол шага, а не
                // слова разговора. Снимок чата — исключение: реплики человека
                // живут только в нём, пока не стали уведомлением
                continue;
            }
            String text = cutBlocks(message.content() == null ? "" : message.content(),
                    RECALL_OPEN, RECALL_CLOSE);
            text = cutBlocks(text, NOTE_OPEN, NOTE_CLOSE);
            text = cutBlocks(text, SHELF_OPEN, SHELF_CLOSE);
            for (String insertion : NotificationLoop.serviceInsertions()) {
                text = text.replace(insertion, "");
            }
            if (message.role() == Message.Role.ASSISTANT) {
                // её собственные заметки о намерениях — не то, что было сказано
                text = withoutMentalNotes(text);
                text = join(text, herWords(message) + herActions(message, actions));
            }
            text = text.replaceAll("\n{3,}", "\n\n").strip();
            if (text.isEmpty()) {
                // реплика, от которой остались одни теги или вызов без текста
                continue;
            }
            // tool_calls не переносятся: без ответов на них запрос невалиден.
            // Снимок чата уходит словами человека, а не ответом инструмента:
            // роль tool без своего вызова шлюз отвергает целиком, ответом 400
            Message.Role role = message.role() == Message.Role.TOOL ? Message.Role.USER : message.role();
            kept.add(new Message(role, text, null, null, null));
        }
        if (!kept.isEmpty()) {
            kept.add(actionsSummary(actions));
        }
        return kept;
    }

    /**
     * Сводка поступков отдельным блоком. Без неё пересказчик судит о действиях
     * по её словам: «сейчас глянем» и «подпишусь» в переписке 15 ноября он
     * записал в дневник как «я подписалась и посмотрела», хотя ни одной тулзы
     * перехода или подписки вызвано не было.
     */
    private static Message actionsSummary(List<String> actions) {
        StringBuilder out = new StringBuilder("<actions_taken>\n");
        if (actions.isEmpty()) {
            out.append("None. Rin did not open another chat, subscribe or unsubscribe, send a sticker ")
                    .append("or forward anything in this conversation. What she said she would do is ")
                    .append("a promise, not something that happened.");
        } else {
            for (String action : actions) {
                out.append(action).append('\n');
            }
            out.append("Only these actions actually happened; anything else she said is a promise, not a fact.");
        }
        return Message.user(out.append("\n</actions_taken>").toString());
    }

    /**
     * Не-текстовые поступки той же репликой, что и её слова: открытие чата,
     * подписка, стикер, пересылка. Пересказчик видит их как факт рядом с тем,
     * что она сказала, а не выводит из обещаний.
     */
    private static String herActions(Message message, List<String> ledger) {
        StringBuilder done = new StringBuilder();
        for (ToolCall call : message.toolCallsOrEmpty()) {
            if (!ACTIONS.containsKey(call.name())) {
                continue;
            }
            String line = actionLine(call.name(), argumentsOf(call));
            done.append(line).append('\n');
            ledger.add(line);
        }
        return done.toString();
    }

    private static String actionLine(String tool, JsonNode arguments) {
        StringBuilder line = new StringBuilder("Rin ").append(ACTIONS.get(tool));
        String first = null;
        StringBuilder rest = new StringBuilder();
        for (String key : ACTION_IDS) {
            JsonNode value = arguments.get(key);
            if (value == null || value.asText("").isBlank()) {
                continue;
            }
            if (first == null) {
                first = value.asText();
            } else {
                rest.append(rest.isEmpty() ? "" : ", ").append(key).append('=').append(value.asText());
            }
        }
        if (first != null) {
            line.append(' ').append(first);
        }
        if (!rest.isEmpty()) {
            line.append(" (").append(rest).append(')');
        }
        if ("post_to_channel".equals(tool)) {
            String text = arguments.path("text").asText("");
            if (!text.isBlank()) {
                line.append(": \"").append(text).append('"');
            }
        }
        return line.append('.').toString();
    }

    private static boolean isChatScreen(String content) {
        return ChatScreen.isScreen(content);
    }

    private static String withoutMentalNotes(String text) {
        return MENTAL_NOTE.matcher(text).replaceAll("");
    }

    /** Два куска одного высказывания: что она написала и что сказала вызовом. */
    private static String join(String said, String done) {
        if (said.isBlank()) {
            return done;
        }
        return done.isBlank() ? said : said.strip() + "\n" + done;
    }

    /**
     * Реплики и реакции самой Raine: в тексте её ответа их обычно нет, они
     * лежат в аргументах {@code send_telegram_message} и {@code react_with_emoji}.
     * Без них пересказчик видит только «- thought: …» и конспектирует намерения
     * вместо разговора.
     */
    private static String herWords(Message message) {
        StringBuilder said = new StringBuilder();
        for (ToolCall call : message.toolCallsOrEmpty()) {
            boolean sends = SEND_TOOL.equals(call.name());
            if (!sends && !REACT_TOOL.equals(call.name())) {
                continue;
            }
            JsonNode arguments = argumentsOf(call);
            if (!sends) {
                String emoji = arguments.path("emoji").asText("");
                if (!emoji.isBlank()) {
                    said.append("Rin reacted with ").append(emoji).append('\n');
                }
                continue;
            }
            String text = arguments.path("text").asText("");
            String attachment = arguments.path("photo_filename").asText(
                    arguments.path("audio_filename").asText(""));
            if (text.isBlank() && attachment.isBlank()) {
                continue;
            }
            said.append("Rin: ");
            if (!text.isBlank()) {
                // прямые кавычки, а не «ёлочки»: страница дневника возвращается к
                // ней же в контекст, и ёлочки из пересказа лезут в её сообщения
                said.append('"').append(text).append('"');
            }
            if (!attachment.isBlank()) {
                said.append(text.isBlank() ? "" : " ").append("(вложение: ").append(attachment).append(')');
            }
            said.append('\n');
        }
        return said.toString();
    }

    private static JsonNode argumentsOf(ToolCall call) {
        try {
            return ARGUMENTS.readTree(call.arguments());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // модель порой присылает оборванный json: терять из-за этого весь
            // день памяти нельзя, этот вызов просто останется без реплики
            log.debug("Аргументы вызова {} не читаются: {}", call.name(), e.getMessage());
            return ARGUMENTS.createObjectNode();
        }
    }

    /** Вырезает блоки между разметкой; незакрытый блок уносит всё до конца текста. */
    private static String cutBlocks(String text, String open, String close) {
        StringBuilder kept = new StringBuilder();
        int from = 0;
        for (int start = text.indexOf(open); start >= 0; start = text.indexOf(open, from)) {
            kept.append(text, from, start);
            int end = text.indexOf(close, start);
            if (end < 0) {
                return kept.toString();
            }
            from = end + close.length();
        }
        return kept.append(text, from, text.length()).toString();
    }

    private String askForSummary(String systemPrompt, List<Message> context) {
        List<Message> conversation = new ArrayList<>(context);
        conversation.add(Message.user(savePrompt.get()));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ChatResponse response = llm.chat(systemPrompt, conversation, null);
            String content = response.text() == null ? "" : response.text().strip();

            if (response.finishReason().startsWith("content_filter")) {
                // вердикт фильтр-политики, а не забывчивость: тот же разговор
                // даст тот же отказ, и каждый повтор оплачивается заново
                log.warn("Модель отказала контент-фильтром — пересказ не получится");
                throw new NotRetellable("контент-фильтр забраковал этот пересказ");
            }
            if (content.isBlank()) {
                // модель попыталась действовать инструментом, хотя их здесь нет
                conversation.add(Message.user("Tools are not available; write the summary without tools."));
                continue;
            }
            if (ModelText.looksLikeToolCall(content)) {
                log.warn("Модель написала вызов инструмента текстом, попытка {} из {}", attempt, MAX_ATTEMPTS);
                conversation.add(Message.user(
                        "Do not write tool calls. Answer with plain text only: the diary entries themselves."));
                continue;
            }
            return content;
        }
        return "";
    }

    private List<DiaryEntry> persist(String summary, List<TraitSignal> signals, Origin origin) {
        // модель порой ставит разделитель с лишними пробелами
        String normalized = summary.replace("- --", SEPARATOR).replace("-- -", SEPARATOR);

        List<DiaryEntry> saved = new ArrayList<>();
        List<TraitSignal> attach = signals;
        for (String part : Arrays.stream(normalized.split(SEPARATOR)).map(String::strip).toList()) {
            if (part.length() < MIN_ENTRY_LENGTH) {
                continue;
            }
            double[] embedding = new double[0];
            try {
                embedding = llm.embedding(part);
            } catch (RuntimeException e) {
                // вектор досчитается при первом же поиске, а вот день памяти
                // без записи потерять можно всерьёз
                log.warn("Пересказ сохраняется без вектора: {}", e.getMessage());
            }
            // без вектора сравнивать не с чем: запись уйдёт, дубль отсеется
            // при первом поиске, когда вектор уже будет готов
            if (embedding.length > 0 && isPlagiarism(embedding, part)) {
                continue;
            }
            // заметки родились вместе, хранятся вместе: все — в первой же записи
            saved.add(diary.save(part, embedding, 0, attach, origin));
            attach = List.of();
        }
        log.info("В дневник добавлено записей: {}", saved.size());
        return saved;
    }

    /**
     * То же самое, но другими словами, память не обогащает — только раздувает.
     * <p>
     * Одного косинуса тут мало: записи устроены одинаково — те же названия
     * полей, те же имена, те же подсказки поиска, — и свежий отрезок разговора
     * против прежней записи даёт 0.98. Различаются они только дословными
     * цитатами, которые промпт велит не перефразировать. Поэтому кусок
     * сбрасывается, лишь когда ни одной новой цитаты в нём нет.
     */
    private boolean isPlagiarism(double[] embedding, String part) {
        List<Diary.Match> similar = diary.query(embedding).stream()
                .filter(match -> match.relatedness() > plagiarismThreshold)
                .toList();
        if (similar.isEmpty()) {
            return false;
        }
        Set<Long> known = new HashSet<>();
        for (Diary.Match match : similar) {
            for (String quote : quotes(match.entry().body())) {
                known.add(Fingerprint.of(quote));
            }
        }
        int fresh = 0;
        for (String quote : quotes(part)) {
            if (known.add(Fingerprint.of(quote))) {
                fresh++;
            }
        }
        if (fresh > 0) {
            log.info("Близость {} высока, но цитат новых: {} — сохраняю",
                    String.format("%.3f", similar.getFirst().relatedness()), fresh);
            return false;
        }
        log.info("Уже записано (близость {}), пропускаю: {}",
                String.format("%.3f", similar.getFirst().relatedness()), part.lines().findFirst().orElse("").strip());
        return true;
    }

    /** Дословные цитаты пересказа — и в «ёлочках», и в прямых кавычках. */
    private static List<String> quotes(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = QUOTE.matcher(text);
        while (matcher.find()) {
            String quote = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (!quote.isBlank()) {
                found.add(quote);
            }
        }
        return found;
    }

    /**
     * Этот текст не пересказывается и не перескажется завтра: вердикт
     * фильтр-политики по содержанию либо разговор, от которого после очистки
     * не осталось ни одной реплики. Повтор даёт ровно то же и оплачивается
     * заново всем контекстом, поэтому отложенный разговор по этому признаку
     * убирают из очереди, а не оставляют ждать следующего запуска.
     */
    public static final class NotRetellable extends RuntimeException {

        NotRetellable(String reason) {
            super(reason);
        }
    }
}
