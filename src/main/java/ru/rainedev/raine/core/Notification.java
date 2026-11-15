package ru.rainedev.raine.core;

/**
 * Событие, требующее внимания: сообщение, напоминание, повод подумать.
 *
 * @param text    что Raine увидит
 * @param tools   действия, доступные немедленно — например, открыть чат
 * @param ownText текст говорит то, чего в переписке не прочесть: снимок готов,
 *                файл называется так-то. Открытый чат такой текст не заменяет,
 *                а встаёт под ним — иначе сказанное пропадёт, не дойдя до неё
 * @param personId о ком это событие, если известно: её доверие к этому человеку
 *                 меняет тон разговора. Ноль — событие не про человека
 * @param chatId   чат, из которого пришёл повод. Ответа не досталось — чат
 *                 снова повиснет непрочитанным, иначе его уже не найти. Ноль —
 *                 событие не из переписки и отмечать негде
 * @param trigger  кто начал разговор: тот же ход значит для цикла разное —
 *                 порыв заканчивается первым же действием, ответ человеку
 *                 откладывает сон, а её собственные слова сном не торгуются
 */
public record Notification(String text, Toolbox tools, boolean ownText, long personId, long chatId,
                           Trigger trigger) {

    /** Откуда взялся ход. */
    public enum Trigger {
        /** Человек написал: обычный разговор, и он же единственный откладывает сон. */
        INCOMING,
        /** Она сама захотела заговорить: чат уже открыт, действие заканчивает ход. */
        IMPULSE,
        /** Её ночное прощание или утреннее приветствие: его никто не ждал. */
        GREETING,
        /** Повод от кода — кончились строки в файле, упёрся лимит. */
        SYSTEM;

        /** Это она заговорила сама, а не ответила тому, кто написал? */
        public boolean unprompted() {
            return this != INCOMING;
        }
    }

    public Notification(String text, Toolbox tools, boolean ownText, long personId, long chatId) {
        this(text, tools, ownText, personId, chatId, Trigger.INCOMING);
    }

    public Notification(String text, Toolbox tools, boolean ownText) {
        this(text, tools, ownText, 0, 0);
    }

    public Notification(String text, Toolbox tools) {
        this(text, tools, false, 0, 0);
    }

    public Notification(String text) {
        this(text, new Toolbox(), false, 0, 0);
    }

    /** То же событие, но теперь известно, кто его принёс. */
    public Notification about(long personId) {
        return new Notification(text, tools, ownText, personId, chatId, trigger);
    }

    /** Тот же повод, но заговорить с ним решила она сама. */
    public Notification onOwnInitiative() {
        return new Notification(text, tools, ownText, personId, chatId, Trigger.IMPULSE);
    }

    /** Тот же повод, только это её ночное прощание или утреннее приветствие. */
    public Notification asGreeting() {
        return new Notification(text, tools, ownText, personId, chatId, Trigger.GREETING);
    }

    /** Тот же повод, но пришёл он от кода, а не от человека. */
    public Notification fromCode() {
        return new Notification(text, tools, ownText, personId, chatId, Trigger.SYSTEM);
    }

    /**
     * То же событие из переписки. Открытый чат Telegram помечает прочитанным
     * сразу, и потерянное уведомление пропало бы молча — с этим числом чат можно
     * вернуть в непрочитанные.
     */
    public Notification from(long chatId) {
        return new Notification(text, tools, ownText, personId, chatId, trigger);
    }

    /** Ход без входящего сообщения: она начала его сама или повода не было вовсе. */
    public boolean unprompted() {
        return trigger.unprompted();
    }
}
