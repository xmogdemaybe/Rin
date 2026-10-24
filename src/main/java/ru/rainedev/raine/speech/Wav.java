package ru.rainedev.raine.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Укладывает сырой звук в WAV.
 * <p>
 * Никакого сжатия здесь нет: отсчёты лежат как есть, а шапка говорит, сколько
 * их и с какой частоты. Платой — размер: минута речи втрое тяжелее той же
 * минуты в Opus, поэтому длину записи считают до отправки.
 * <p>
 * Выбор не прихоть: разговорные эндпоинты принимают в запросе только wav и mp3,
 * а голосовые Telegram приходит как OGG/Opus. Из wav в mp3 без своего кодека не
 * свернуть, зато wav понимают все.
 */
public final class Wav {

    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WAVE = "WAVE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORMAT = "fmt ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA = "data".getBytes(StandardCharsets.US_ASCII);

    /** Сколько байт занимает сама шапка. */
    private static final int HEADER = 44;

    private Wav() {}

    /** @param samples отсчёты 16 бит, один канал */
    public static byte[] of(short[] samples, int rate) {
        int body = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER + body).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(RIFF);
        buffer.putInt(HEADER - 8 + body);
        buffer.put(WAVE);
        buffer.put(FORMAT);
        buffer.putInt(16);              // столько весит описание формата
        buffer.putShort((short) 1);     // 1 — звук без сжатия
        buffer.putShort((short) 1);     // канал один: на речи второго не на чем экономить
        buffer.putInt(rate);
        buffer.putInt(rate * 2);        // байт в секунду
        buffer.putShort((short) 2);     // шаг между одноимёнными отсчётами соседних кадров
        buffer.putShort((short) 16);    // бит в отсчёте
        buffer.put(DATA);
        buffer.putInt(body);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }
}
