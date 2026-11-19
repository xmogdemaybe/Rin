package ru.rainedev.raine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * У зрения и эмбеддингов адрес и ключ наследуются от разговора, пока владелец
 * не впишет свои: иначе отдельный сервис требовал бы правки в двух местах.
 */
class EndpointFollowsChatTest {

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

    @Test
    void withoutOwnAddressesBothInheritTheChatEndpoint(@TempDir Path dir) throws IOException {
        Config config = load(dir, "vision_base_url=\nvision_api_key=\nembedding_base_url=\nembedding_api_key=\n");

        assertEquals("http://example", config.vision().baseUrl());
        assertEquals("key", config.vision().apiKey());
        assertEquals("http://example", config.embeddingBaseUrl());
        assertEquals("key", config.embeddingApiKey());
        assertEquals("http://example", load(dir, "").vision().baseUrl());
    }

    @Test
    void ownAddressesStillWin(@TempDir Path dir) throws IOException {
        Config config = load(dir, """
                vision_base_url=http://vision.example/v1
                vision_api_key=vision-key
                embedding_base_url=http://embedding.example/v1
                embedding_api_key=embedding-key
                """);

        assertEquals("http://vision.example/v1", config.vision().baseUrl());
        assertEquals("vision-key", config.vision().apiKey());
        assertEquals("http://embedding.example/v1", config.embeddingBaseUrl());
        assertEquals("embedding-key", config.embeddingApiKey());
    }
}
