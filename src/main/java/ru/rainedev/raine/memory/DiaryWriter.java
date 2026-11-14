package ru.rainedev.raine.memory;

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
     * и в пересказе её служебные фразы стоят наравне с человеческими: промпт
     * велит избегать повторов прежних страниц, а без страницы перед глазами
     * модель пересказывает и их.
     * <p>
     * Средней памяти срез не касается: ей нужна целая переписка, а не пересказ.
     */
    private static List<Message> conversationOnly(List<Message> context) {
        List<Message> kept = new ArrayList<>();
        for (Message message : context) {
            if (message.role() == Message.Role.TOOL) {
                // «Success», «message_id=...», выдачи #ask — протокол шага, а не
                // слова разговора: сказанное видно в репликах и в снимках чата
                continue;
            }
            String text = message.content() == null ? "" : message.content();
            text = cutBlocks(text, RECALL_OPEN, RECALL_CLOSE);
            text = cutBlocks(text, NOTE_OPEN, NOTE_CLOSE);
            for (String insertion : NotificationLoop.serviceInsertions()) {
                text = text.replace(insertion, "");
            }
            text = text.replaceAll("\n{3,}", "\n\n").strip();
            if (text.isEmpty()) {
                // реплика без текста — это вызов инструмента, а не высказывание
                continue;
            }
            // tool_calls не переносятся: без ответов на них запрос невалиден
            kept.add(new Message(message.role(), text, null, null, null));
        }
        return kept;
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
