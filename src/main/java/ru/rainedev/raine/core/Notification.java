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
 */
public record Notification(String text, Toolbox tools, boolean ownText, long personId, long chatId) {

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
        return new Notification(text, tools, ownText, personId, chatId);
    }

    /**
     * То же событие из переписки. Открытый чат Telegram помечает прочитанным
     * сразу, и потерянное уведомление пропало бы молча — с этим числом чат можно
     * вернуть в непрочитанные.
     */
    public Notification from(long chatId) {
        return new Notification(text, tools, ownText, personId, chatId);
    }
}
