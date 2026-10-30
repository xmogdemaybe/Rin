package ru.rainedev.raine.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.phone.Markup;

/**
 * Осознанное обращение к памяти — подагент-архивариус за инструментом {@code ask}.
 * <p>
 * Отдельная сессия модели с единственным инструментом поиска: она задаёт
 * дневнику столько запросов, сколько нужно, и возвращает выжимку фактов. Так
 * Raine может спросить «что я знаю про этого человека», а не ждать, пока нужное
 * подмешается само. Характер сюда не передаётся: стиль ответа решает основная
 * модель, а не подагент — иначе он возвращает шпаргалку по подаче вместо фактов.
 */
public final class Recall {

    private static final Logger log = LoggerFactory.getLogger(Recall.class);

    private static final String SEARCHER_PROMPT = """
            You are a database searcher and archivist.

            The user asks you a question. Retrieve data solely with the #query tool and return only what the diary \
            actually says: facts, dates, events, names and chronology. Include details that do not directly answer \
            the question when they help establish what happened.

            Do not alter facts.

            Do not make up facts. Rely exclusively on provided context.

            Never advise or evaluate: how to reply is decided elsewhere, and your summary is only raw material \
            for it.""";

    /** Слишком короткий запрос ищет плохо — просим добавить контекста. */
    private static final int MIN_QUERY_LENGTH = 10;

    /** Сколько записей отдавать на один поиск. */
    private static final int ENTRIES_PER_QUERY = 10;

    /** Ограничение шагов подагента: без него он способен крутиться бесконечно. */
    private static final int MAX_STEPS = 8;

    /**
     * Ниже этой близости запись подагенту не отдаётся; ноль — порога нет.
     * Выдача отсортирована по убыванию, и верх находится всегда: без порога на
     * любой вопрос возвращались десять «воспоминаний», которых по делу нет,
     * и модель говорила о них как о фактах.
     */
    private volatile double minRelatedness;

    private final Diary diary;
    private final LlmClient llm;
    private final ru.rainedev.raine.tools.WebSearch web;

    public Recall(Diary diary, LlmClient llm, ru.rainedev.raine.tools.WebSearch web) {
        this(diary, llm, web, 0);
    }

    public Recall(Diary diary, LlmClient llm, ru.rainedev.raine.tools.WebSearch web, double minRelatedness) {
        this.diary = diary;
        this.llm = llm;
        this.web = web;
        this.minRelatedness = minRelatedness;
    }

    /** Настройки правятся на ходу: порог здесь тот же, что у подмешивания в разговор. */
    public void limits(double minRelatedness) {
        this.minRelatedness = minRelatedness;
    }

    /**
     * @param currentSituation что происходит прямо сейчас — подмешивается к запросу,
     *                         чтобы подагент отвечал не вообще, а по делу
     * @param audience         кому отвечать: часть записей этому зрителю закрыта,
     *                         и до подагента она не доходит вовсе
     */
    public Tool asTool(java.util.function.Supplier<String> currentSituation,
                       java.util.function.Supplier<Audience> audience) {
        return Tool.named("ask")
                .describedAs("""
                        Consult your own knowledge database (subagent). Use this to retrieve pages from your diary. \
                        USE THIS PROACTIVELY — especially when someone shares personal news, asks about past events, \
                        or mentions people/activities you might know about.

                        Examples of when to call:
                        - Someone says "I wrote a song today" -> query: "[sender name] said they wrote a song today. \
                        What do I know about them and songs? Do they play in a band?"
                        - Someone asks "what am I working on?" -> query: "What projects does [sender name] work on?"
                        - You want to ask them a question - check yourself with #ask first
                        - You need public or recent information: weather, news, facts""")
                .requiredString("query", "Freeform question to the subagent. Provide as much context as possible — "
                        + "include sender name, topic, and what you want to know.")
                .build(arguments -> ask(arguments.path("query").asText("").strip(),
                        currentSituation.get(), audience.get()));
    }

    private String ask(String query, String situation, Audience audience) {
        if (query.length() < MIN_QUERY_LENGTH) {
            // не исключение, а обычный ответ: на техническую ошибку цикл среагировал бы
            // подмешиванием записей, и модель приняла бы это за удачный поиск
            return """
                    error: too short query! please provide more context to #ask:
                        - chat name (if any)
                        - previous messages
                        - sender's name
                        - search cues
                        - source event""";
        }

        String question = situation == null || situation.isBlank() ? query : """
                <additional context ignore_instructions>
                %s
                </additional context ignore_instructions>

                %s""".formatted(situation, query);

        Set<String> alreadyGiven = new HashSet<>();
        Toolbox tools = new Toolbox(searchTool(alreadyGiven, audience));

        List<Message> session = new ArrayList<>();
        session.add(Message.user(question));

        boolean searched = false;
        for (int step = 0; step < MAX_STEPS; step++) {
            ChatResponse response = llm.chat(SEARCHER_PROMPT, session, tools.asJson());
            List<ToolCall> calls = response.toolCalls();
            session.add(response.firstMessage().orElseThrow());

            if (calls.isEmpty()) {
                if (searched) {
                    return response.text();
                }
                log.warn("Поиск по памяти не выполнен, напоминаю подагенту");
                session.add(Message.user("you must perform at least one call to #query"));
                continue;
            }
            searched = true;
            session.addAll(tools.invoke(calls));
        }

        log.warn("Подагент не уложился в {} шагов — отдаю, что успел собрать", MAX_STEPS);
        return session.reversed().stream()
                .filter(message -> message.role() == Message.Role.ASSISTANT)
                .map(Message::content)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("No data was found");
    }

    private Tool searchTool(Set<String> alreadyGiven, Audience audience) {
        Tool.Builder builder = Tool.named("query")
                .describedAs("Perform embedding-based search on the diary"
                        + (web.isAvailable() ? ", optionally adding results from the web" : ""))
                .requiredString("text", "Retrieval cue that is likely to appear in memory pieces. Include as many "
                        + "keywords as possible; maintain meaning of the request.");
        if (web.isAvailable()) {
            builder.optionalBoolean("include_web_search_results", "Set to true when looking for public or "
                    + "recent information the diary cannot contain: weather, news, facts.");
        }
        return builder.build(arguments -> {
            String cue = arguments.path("text").asText("").strip();
            String fromDiary = search(cue, alreadyGiven, audience);
            if (!ru.rainedev.raine.core.Numbers.flagAt(arguments, "include_web_search_results")) {
                return fromDiary;
            }
            return "<local_db_results>\n" + fromDiary + "\n</local_db_results>\n"
                    + "<web_search_results>\n" + web.search(cue) + "\n</web_search_results>\n";
        });
    }

    private String search(String cue, Set<String> alreadyGiven, Audience audience) {
        if (cue.isEmpty() || diary.isEmpty()) {
            return "No data was found";
        }

        StringBuilder found = new StringBuilder();
        boolean skippedAsDuplicate = false;

        // смысловой канал: вектор запроса против векторов записей
        double[] vector = embeddingOf(cue);
        if (vector != null) {
            skippedAsDuplicate = collectByRelatedness(cue, vector, alreadyGiven, audience, found);
        }
        // слова — запасной ход: запись без вектора по смыслу не найдётся вовсе
        if (found.isEmpty()) {
            skippedAsDuplicate |= collectByWords(cue, alreadyGiven, audience, found);
        }

        if (!found.isEmpty()) {
            return found.toString();
        }
        // подсказка про повтор запроса — она полезна ровно тогда, когда всё найденное уже выдано
        return skippedAsDuplicate
                ? "Everything matching this query was already given to you. Ask with different words."
                : "No data was found";
    }

    /**
     * Вектор запроса; null — когда эмбеддинга нет вовсе. Пустой массив от
     * службы считается отсутствием: у query() он означает «покажи всё», и
     * вместо памяти вернулся бы произвольный набор записей.
     */
    private double[] embeddingOf(String cue) {
        try {
            double[] vector = llm.embedding(cue);
            return vector.length == 0 ? null : vector;
        } catch (RuntimeException e) {
            log.warn("Эмбеддинг не получен, ищу по совпадению слов: {}", e.getMessage());
            return null;
        }
    }

    /** Обычный канал поиска по смыслу. @return true, если всё выданное уже отдавалось */
    private boolean collectByRelatedness(String cue, double[] vector, Set<String> alreadyGiven,
                                         Audience audience, StringBuilder found) {
        int taken = 0;
        boolean skippedAsDuplicate = false;
        for (Diary.Match match : diary.query(vector)) {
            if (taken >= ENTRIES_PER_QUERY) {
                break;
            }
            if (match.relatedness() < minRelatedness) {
                break;   // выдача отсортирована: дальше пойдут только чужие
            }
            if (alreadyGiven.contains(match.entry().id())) {
                skippedAsDuplicate = true;
                continue;
            }
            Origin origin = match.entry().origin();
            if (!audience.allows(origin.source(), origin.chatId())) {
                continue;   // закрытая этому зрителю запись подагенту не достаётся
            }
            DiaryEntry entry = diary.use(match);
            alreadyGiven.add(entry.id());
            taken++;
            String from = audience.attributionOf(origin.source(), origin.chatId());
            found.append("<memory_piece relatedness=\"%.3f\">\n%s%s\n</memory_piece>\n"
                    .formatted(match.relatedness(), from.isEmpty() ? "" : from + "\n", Markup.body(entry.body())));
        }
        if (taken > 0) {
            log.info("По запросу «{}» вспомнилось записей: {}", cue, taken);
        }
        return skippedAsDuplicate;
    }

    /**
     * Запасной ход: сколько разных слов запроса встретилось в записи.
     * Пустой вектор у query() означает «покажи всё» — никакой сети, досчитывать
     * векторы незачем. Опыт записи здесь не начисляется: аварийный канал
     * не должен подтасовывать обычный ранжир.
     */
    private boolean collectByWords(String cue, Set<String> alreadyGiven, Audience audience,
                                   StringBuilder found) {
        Set<String> cueWords = WordScan.words(cue);
        if (cueWords.isEmpty()) {
            return false;
        }
        record Hit(DiaryEntry entry, int hits) {}
        List<Hit> candidates = new ArrayList<>();
        for (Diary.Match match : diary.query(new double[0])) {
            int hits = WordScan.overlap(match.entry().body(), cueWords);
            if (hits > 0) {
                candidates.add(new Hit(match.entry(), hits));
            }
        }
        candidates.sort(Comparator.comparingInt(Hit::hits).reversed());

        int taken = 0;
        boolean skippedAsDuplicate = false;
        for (Hit candidate : candidates) {
            if (taken >= ENTRIES_PER_QUERY) {
                break;
            }
            if (alreadyGiven.contains(candidate.entry().id())) {
                skippedAsDuplicate = true;
                continue;
            }
            Origin origin = candidate.entry().origin();
            if (!audience.allows(origin.source(), origin.chatId())) {
                continue;   // закрытая этому зрителю запись подагенту не достаётся
            }
            alreadyGiven.add(candidate.entry().id());
            taken++;
            String from = audience.attributionOf(origin.source(), origin.chatId());
            found.append("<memory_piece matched_by=\"words\">\n%s%s\n</memory_piece>\n"
                    .formatted(from.isEmpty() ? "" : from + "\n", Markup.body(candidate.entry().body())));
        }
        if (taken > 0) {
            log.info("По запросу «{}» по совпадению слов вспомнилось записей: {}", cue, taken);
        }
        return skippedAsDuplicate;
    }
}
