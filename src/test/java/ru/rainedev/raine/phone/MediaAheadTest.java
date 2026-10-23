package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MediaAheadTest {

    private static TdApi.Message message(long id) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.content = new TdApi.MessagePhoto();
        return message;
    }

    @Test
    void attachmentsAreLookedAtSideBySide() {
        // по одному десяток картинок — это минута молчания, пока собеседник ждёт
        AtomicInteger looks = new AtomicInteger();
        MediaDescriber slow = message -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            looks.incrementAndGet();
            return "описание";
        };

        long startedAt = System.currentTimeMillis();
        MediaAhead.look(slow, List.of(message(1), message(2), message(3), message(4), message(5), message(6)));
        long spent = System.currentTimeMillis() - startedAt;

        assertEquals(6, looks.get(), "разглядеть надо все");
        assertTrue(spent < 700, "шесть по 200 мс должны уложиться в одно ожидание, а вышло " + spent);
    }

    @Test
    void nothingToLookAtIsNotAnError() {
        MediaAhead.look(MediaDescriber.NONE, List.of(message(1), message(2)));
        MediaAhead.look(message -> "описание", List.of());
    }

    @Test
    void withoutEyesNothingIsWarmedUp() {
        // зрение выключено: гонять сеть заранее ради пустого ответа — значит
        // отнимать время у разговора
        AtomicInteger looks = new AtomicInteger();
        MediaDescriber blind = new MediaDescriber() {
            @Override
            public String describe(TdApi.Message message) {
                looks.incrementAndGet();
                return "";
            }

            @Override
            public boolean sees() {
                return false;
            }
        };

        MediaAhead.look(blind, List.of(message(1), message(2), message(3)));

        assertEquals(0, looks.get(), "вслепую разглядывать нечего");
    }
}
