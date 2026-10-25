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

    /**
     * Голосовое слушают ушами, и глаза тут ни при чём. Если вслепую греют только
     * картинки, расшифровка собирается по очереди внутри разметки: три голосовых
     * в открытой истории — это три обращения к эндпоинту одно за другим.
     */
    @Test
    void earsWithoutEyesStillWarmTheVoiceNotes() {
        AtomicInteger looks = new AtomicInteger();

        MediaAhead.look(withoutEyes(looks, true), List.of(voiceNote(1), voiceNote(2)));

        assertEquals(2, looks.get(), "голосовые греются и без зрения");
    }

    @Test
    void withoutEyesAndEarsNothingIsWarmedUp() {
        // зрение выключено, слуха нет: гонять сеть заранее ради пустого ответа —
        // значит отнимать время у разговора
        AtomicInteger looks = new AtomicInteger();

        MediaAhead.look(withoutEyes(looks, false), List.of(message(1), message(2), message(3)));

        assertEquals(0, looks.get(), "вслепую и без ушей разглядывать нечего");
    }

    /** Разметка вслепую — с ушами или без них. */
    private static MediaDescriber withoutEyes(AtomicInteger looks, boolean withEars) {
        return new MediaDescriber() {
            @Override
            public String describe(TdApi.Message message) {
                looks.incrementAndGet();
                return "";
            }

            @Override
            public boolean sees() {
                return false;
            }

            @Override
            public boolean hears() {
                return withEars;
            }
        };
    }

    private static TdApi.Message voiceNote(long id) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.content = new TdApi.MessageVoiceNote(
                new TdApi.VoiceNote(3, new byte[0], "audio/ogg", null, new TdApi.File()), null, false);
        return message;
    }
}
