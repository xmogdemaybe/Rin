package ru.rainedev.raine.speech;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Достаёт звук из OGG/Opus — в этом контейнере Telegram присылает всякое
 * голосовое сообщение.
 * <p>
 * Обратно мы умели писать давно ({@link OggOpusWriter}), а в обратную сторону
 * приходилось полагаться на распознавание речи у Telegram или на саму модель,
 * если она принимает ogg. Разговорным эндпоинтам нужен wav, поэтому упаковку
 * разбираем сами: контейнер — это счётная таблица и заголовки, а кодирует и
 * раскодирует один и тот же чистый Java-порт libopus. Внешних программ в проекте
 * нет сознательно.
 */
public final class OggOpusReader {

    private static final Logger log = LoggerFactory.getLogger(OggOpusReader.class);

    /** Речь разбирать и с такой частоты, а байт втрое меньше, чем у 48 кГц. */
    private static final int RATE = 16_000;

    /** Самый длинный кадр, который вообще помещается в пакет Opus, — 120 мс. */
    private static final int MAX_FRAME = RATE * 120 / 1000;

    /**
     * Байт, на котором лежит счётчик сегментов. Заголовок страницы занимает 27
     * байт, но таблица длин идёт после счётчика, поэтому отсчёт таблицы — на
     * единицу дальше.
     */
    private static final int PAGE_HEAD = 26;

    /** Позиция в ogg-потоке Opus считается всегда в 48 кГц, какой бы частоты ни был звук. */
    private static final int GRANULE_RATE = 48_000;

    private OggOpusReader() {}

    /**
     * @param maxSeconds длиннее этого слушать не станем: целиком не пустит
     *                   эндпоинт, а держать в памяти часовой монолог ни к чему
     * @return сырой звук одного канала или пусто, если файл не оказался opus-потоком
     */
    public static Optional<Sound> decode(byte[] file, int maxSeconds) {
        if (file.length < PAGE_HEAD || !isPage(file, 0)) {
            return Optional.empty();
        }
        List<byte[]> packets = packets(file);
        if (packets.isEmpty()) {
            log.debug("Голосовое распаковалось в ноль пакетов");
            return Optional.empty();
        }
        Optional<Head> head = Head.of(packets.get(0));
        if (head.isEmpty()) {
            log.debug("Внутри не Opus — слушать нечем");
            return Optional.empty();
        }
        // два первых пакета — описание потока и теги: звука в них нет
        List<byte[]> sound = packets.subList(Math.min(2, packets.size()), packets.size());
        if (sound.isEmpty()) {
            log.debug("В потоке нет ни одного звукового пакета");
            return Optional.empty();
        }
        if (durationOf(file) > (long) maxSeconds * GRANULE_RATE) {
            log.debug("Запись длиннее {} с — целиком не слушаем", maxSeconds);
            return Optional.empty();
        }
        return of(head.get(), sound);
    }

    /**
     * Пакеты из-под страниц. Границы задаёт не содержимое, а таблица длин в
     * заголовке страницы: пакет тянется через 255-байтовые отрезки и обрывается
     * на отрезке короче 255, а если страница кончилась на полном отрезке, пакет
     * продолжается на следующей.
     * <p>
     * Признак начала страницы для этого не годится: он описывает первый пакет
     * страницы, а не тот, что на ней оборвался, — полагаться на него значило бы
     * резать пополам всякий пакет, уместившийся ровно в 255 байт. Накопленное
     * дописываем молча через границы страниц и отдаём, только когда поток
     * кончился или оборвался.
     * <p>
     * Искать границы по самому звуку нельзя: в закодированных отсчётах что угодно
     * похоже на что угодно.
     * <p>
     * Видно пакету: сшитие пакетов проверяют без кодека, на собранном вручную
     * потоке.
     */
    static List<byte[]> packets(byte[] file) {
        List<byte[]> found = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int at = 0;
        while (at + PAGE_HEAD + 1 <= file.length) {
            if (!isPage(file, at)) {
                log.debug("Поток обрывается на байте {} — беру собранное", at);
                break;
            }
            int segments = file[at + PAGE_HEAD] & 0xFF;
            int cursor = at + PAGE_HEAD + 1 + segments;
            if (cursor > file.length) {
                break;
            }
            for (int i = 0; i < segments; i++) {
                int size = file[at + PAGE_HEAD + 1 + i] & 0xFF;
                if (cursor + size > file.length) {
                    if (current.size() > 0) {
                        found.add(current.toByteArray());
                    }
                    return found;
                }
                current.writeBytes(Arrays.copyOfRange(file, cursor, cursor + size));
                cursor += size;
                if (size < 255) {
                    found.add(current.toByteArray());
                    current.reset();
                }
            }
            at = cursor;
        }
        if (current.size() > 0) {
            found.add(current.toByteArray());
        }
        return found;
    }

    /** Длительность потока по последней гранулярной позиции — она считается в 48 кГц. */
    private static long durationOf(byte[] file) {
        long last = 0;
        int at = 0;
        while (at + PAGE_HEAD + 1 <= file.length && isPage(file, at)) {
            int segments = file[at + PAGE_HEAD] & 0xFF;
            if (at + PAGE_HEAD + 1 + segments > file.length) {
                // таблица длин оборвалась: считать дальше нечем, берём собранное
                break;
            }
            long granule = ByteBuffer.wrap(file, at + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            last = Math.max(last, granule);
            at += PAGE_HEAD + 1 + segments + bodyOf(file, at, segments);
        }
        return last;
    }

    private static int bodyOf(byte[] file, int at, int segments) {
        int body = 0;
        for (int i = 0; i < segments; i++) {
            body += file[at + PAGE_HEAD + 1 + i] & 0xFF;
        }
        return body;
    }

    private static Optional<Sound> of(Head head, List<byte[]> packets) {
        OpusDecoder decoder;
        try {
            decoder = new OpusDecoder(RATE, head.channels());
        } catch (OpusException e) {
            log.debug("Раскодировать голосовое не вышло: {}", e.getMessage());
            return Optional.empty();
        }
        // отсчёты копят отдельными кусками и склеивают один раз: минутная запись —
        // это полторы тысячи пакетов, а прирастать массивом на каждом выходит
        // квадратичный счёт
        List<short[]> chunks = new ArrayList<>();
        short[] buffer = new short[MAX_FRAME * head.channels()];
        int total = 0;
        for (byte[] packet : packets) {
            int decoded;
            try {
                decoded = decoder.decode(packet, 0, packet.length, buffer, 0, MAX_FRAME, false);
            } catch (OpusException | RuntimeException e) {
                // хвост можем и потерять: начало записи важнее её конца
                log.debug("Пакет не раскодировался: {}", e.getMessage());
                break;
            }
            if (decoded <= 0) {
                continue;
            }
            chunks.add(Arrays.copyOf(buffer, decoded * head.channels()));
            total += decoded * head.channels();
        }
        if (total == 0) {
            return Optional.empty();
        }
        short[] collected = new short[total];
        int at = 0;
        for (short[] chunk : chunks) {
            System.arraycopy(chunk, 0, collected, at, chunk.length);
            at += chunk.length;
        }
        short[] samples = Pcm.mono(collected, head.channels());
        // кодек разгоняется первые миллисекунды и платит за это щелчком на старте;
        // сколько выбросить — сказано в заголовке потока, но в отсчётах 48 кГц
        int skip = Math.min(head.preSkip() * RATE / GRANULE_RATE, samples.length);
        return Optional.of(new Sound(Arrays.copyOfRange(samples, skip, samples.length), RATE));
    }

    /** Страница всегда начинается с подписи; больше ничто её не отличает. */
    private static boolean isPage(byte[] file, int at) {
        return at + 4 <= file.length
                && file[at] == 'O' && file[at + 1] == 'g' && file[at + 2] == 'g' && file[at + 3] == 'S';
    }

    /**
     * Описание потока: сколько каналов и сколько звука отрезать в начале.
     * Частоту входного файла не читаем — раскодировать просим свою.
     */
    private record Head(int channels, int preSkip) {

        private static final byte[] MAGIC = "OpusHead".getBytes(StandardCharsets.US_ASCII);

        static Optional<Head> of(byte[] packet) {
            if (packet.length < 19) {
                return Optional.empty();
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (packet[i] != MAGIC[i]) {
                    return Optional.empty();
                }
            }
            ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            int channels = buffer.get(9) & 0xFF;
            // раскладка каналов за нулевой строчкой: под ней surround-потоки,
            // а их мы расшифровать не пытаемся
            if (channels < 1 || channels > 2 || buffer.get(18) != 0) {
                return Optional.empty();
            }
            return Optional.of(new Head(channels, buffer.getShort(10) & 0xFFFF));
        }
    }
}
