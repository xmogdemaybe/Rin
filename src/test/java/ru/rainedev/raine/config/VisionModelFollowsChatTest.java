package ru.rainedev.raine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Зрение живёт на том же адресе, что и разговор, поэтому по умолчанию зовёт ту
 * же модель.
 */
class VisionModelFollowsChatTest {

    private static final String MINIMAL = """
            api_id=1
            api_hash=hash
            phone=+70000000000
            owner_id=1
            llm_base_url=http://example
            llm_model=gemini-3.5-flash-lite
            llm_api_key=key
            """;

    private static Config load(Path dir, String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, MINIMAL + extra);
        return Config.load(file);
    }

    /**
     * Имя с чужого адреса этот эндпоинт не знает и разворачивает запрос сороковым
     * ответом — глаз как бы нет, но молча подставлять его хуже, чем просить
     * разговорную модель, которая видит.
     */
    @Test
    void withoutItsOwnNameVisionTakesTheChatModel(@TempDir Path dir) throws IOException {
        Config config = load(dir, """
                vision_model=
                vision_model_cheap=
                """);

        assertEquals("gemini-3.5-flash-lite", config.vision().model());
        assertEquals("gemini-3.5-flash-lite", config.vision().cheapModel());
        // строк нет вовсе — тот же результат
        assertEquals("gemini-3.5-flash-lite", load(dir, "").vision().model());
    }

    @Test
    void ownNameStillWins(@TempDir Path dir) throws IOException {
        Config config = load(dir, """
                vision_model=models/gemini-3.5-flash
                vision_model_cheap=models/gemini-3.1-flash-lite-image
                """);

        assertEquals("models/gemini-3.5-flash", config.vision().model());
        assertEquals("models/gemini-3.1-flash-lite-image", config.vision().cheapModel());
    }
}
