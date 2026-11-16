package ru.rainedev.raine.core;

import ru.rainedev.raine.phone.Markup;

/**
 * Её собственный канал: туда она пишет сама, а не отвечает.
 * <p>
 * Без этой строки в промпте канал живёт только в настройке: модель не знает ни
 * номера, ни того, чем в него писать, и за постом идёт наугад — часто через
 * {@code send_message}, который в канал не пишет.
 */
public record OwnChannel(long chatId, String title, String username) {

    public static final OwnChannel NONE = new OwnChannel(0, "", "");

    public boolean isSet() {
        return chatId != 0;
    }

    /**
     * @param canPost тулза публикации появляется только при разрешённой
     *                публикации каналов — обещать её иначе значит учить несуществующему
     */
    public String asPromptSuffix(boolean canPost) {
        if (!isSet()) {
            return "";
        }
        String how = canPost
                ? "Open it with open_chat, then publish with post_to_channel. Write there in your own voice."
                : "Publishing there is switched off in settings, so reading is all this channel is for.";
        return """

                <own_channel>
                Your own channel: "%s"%s (chat_id=%d). %s
                </own_channel>
                """.formatted(Markup.attribute(title),
                username.isBlank() ? "" : " @" + Markup.attribute(username), chatId, how);
    }
}
