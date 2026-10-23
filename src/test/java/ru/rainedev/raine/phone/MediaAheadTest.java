package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
        // барьер сходится только тогда, когда все шестеро стоят на нём вместе:
        // за ним видно именно одновременность, а не удачное стечение секунд
        CyclicBarrier together = new CyclicBarrier(6);
        AtomicBoolean queued = new AtomicBoolean();
        MediaDescriber sideBySide = message -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (BrokenBarrierException | TimeoutException e) {
                queued.set(true);
            }
            looks.incrementAndGet();
            return "описание";
        };

        MediaAhead.look(sideBySide, List.of(message(1), message(2), message(3), message(4), message(5), message(6)));

        assertEquals(6, looks.get(), "разглядеть надо все");
        assertFalse(queued.get(), "шесть обращений должны идти одновременно, а не друг за другом");
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
