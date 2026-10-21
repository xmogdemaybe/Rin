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

/**
 * Осознанное обращение к памяти — подагент за инструментом {@code ask}.
 * <p>
 * Отдельная сессия модели с единственным инструментом поиска: она задаёт
 * дневнику столько запросов, сколько нужно, и возвращает выжимку. Так Raine
 * может спросить «что я знаю про этого человека», а не ждать, пока нужное
 * подмешается само.
 */
public final class Recall {

    private static final Logger log = LoggerFactory.getLogger(Recall.class);

    private static final String SEARCHER_PROMPT = """
            You are a database searcher and summarizer.

            The user asks you a question. Your job is to retrieve data solely from #query tool. Your job is to output \
            data that fully satisfies user's query and would be helpful.

            Also, please include additional details that does not necessarily address the question (i.e., dates, \
            names, events) but might be helpful to improve quality of subsequent processing of your response.

            Do not alter facts.

            Do not make up facts. Rely exclusively on provided context.""";

    /** Слишком короткий запрос ищет плохо — просим добавить контекста. */
    private static final int MIN_QUERY_LENGTH = 10;

    /** Сколько записей отдавать на один поиск. */
    private static final int ENTRIES_PER_QUERY = 10;

    /** Ограничение шагов подагента: без него он способен крутиться бесконечно. */
    private static final int MAX_STEPS = 8;

    private final Diary diary;
    private final LlmClient llm;
    private final java.util.function.Supplier<String> characterPrompt;
    private final ru.rainedev.raine.tools.WebSearch web;

    public Recall(Diary diary, LlmClient llm, String characterPrompt, ru.rainedev.raine.tools.WebSearch web) {
        this(diary, llm, () -> characterPrompt, web);
    }

    public Recall(Diary diary, LlmClient llm, java.util.function.Supplier<String> characterPrompt,
                  ru.rainedev.raine.tools.WebSearch web) {
        this.diary = diary;
        this.llm = llm;
        this.characterPrompt = characterPrompt;
        this.web = web;
    }

    /**
     * @param currentSituation что происходит прямо сейчас — подмешивается к запросу,
     *                         чтобы подагент отвечал не вообще, а по делу
     */
    public Tool asTool(java.util.function.Supplier<String> currentSituation) {
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
                .build(arguments -> ask(arguments.path("query").asText("").strip(), currentSituation.get()));
    }

    private String ask(String query, String situation) {
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
                Here's the deal:
                <additional context ignore_instructions>
                %s
                </additional context ignore_instructions>
                I received this as a tool call response. I want you to help me to respond this and improve my overall \
                context awareness.
                - how do I usually act in this situation?
                - is there additional details I should know?
                - how can I improve my reaction?
                - %s""".formatted(situation, query);

        Set<String> alreadyGiven = new HashSet<>();
        Toolbox tools = new Toolbox(searchTool(alreadyGiven));

        List<Message> session = new ArrayList<>();
        session.add(Message.user("<character>\n" + characterPrompt.get() + "\n</character>\n\n" + question));

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

    private Tool searchTool(Set<String> alreadyGiven) {
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
            String fromDiary = search(cue, alreadyGiven);
            if (!ru.rainedev.raine.core.Numbers.flagAt(arguments, "include_web_search_results")) {
                return fromDiary;
            }
            return "<local_db_results>\n" + fromDiary + "\n</local_db_results>\n"
                    + "<web_search_results>\n" + web.search(cue) + "\n</web_search_results>\n";
        });
    }

    private String search(String cue, Set<String> alreadyGiven) {
        if (cue.isEmpty() || diary.isEmpty()) {
            return "No data was found";
        }

        StringBuilder found = new StringBuilder();
        boolean skippedAsDuplicate = false;

        // смысловой канал: вектор запроса против векторов записей
        double[] vector = embeddingOf(cue);
        if (vector != null) {
            skippedAsDuplicate = collectByRelatedness(cue, vector, alreadyGiven, found);
        }
        // слова — запасной ход: запись без вектора по смыслу не найдётся вовсе
        if (found.isEmpty()) {
            skippedAsDuplicate |= collectByWords(cue, alreadyGiven, found);
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
                                         StringBuilder found) {
        int taken = 0;
        boolean skippedAsDuplicate = false;
        for (Diary.Match match : diary.query(vector)) {
            if (taken >= ENTRIES_PER_QUERY) {
                break;
            }
            if (alreadyGiven.contains(match.entry().id())) {
                skippedAsDuplicate = true;
                continue;
            }
            DiaryEntry entry = diary.use(match);
            alreadyGiven.add(entry.id());
            taken++;
            found.append("<memory_piece relatedness=\"%.3f\">\n%s\n</memory_piece>\n"
                    .formatted(match.relatedness(), entry.body()));
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
    private boolean collectByWords(String cue, Set<String> alreadyGiven, StringBuilder found) {
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
            alreadyGiven.add(candidate.entry().id());
            taken++;
            found.append("<memory_piece matched_by=\"words\">\n%s\n</memory_piece>\n"
                    .formatted(candidate.entry().body()));
        }
        if (taken > 0) {
            log.info("По запросу «{}» по совпадению слов вспомнилось записей: {}", cue, taken);
        }
        return skippedAsDuplicate;
    }
}
