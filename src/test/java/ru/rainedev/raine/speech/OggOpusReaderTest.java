package ru.rainedev.raine.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Разбор OGG/Opus проверяется на потоке такой же формы, как у Telegram:
 * страница заголовков, страницы звука, пакеты переходят через границы страниц.
 */
class OggOpusReaderTest {

    /** Секунда тона на 48 кГц — с той же частоты Telegram и пишет голосовые. */
    private static byte[] oggOfOneSecond() throws Exception {
        return oggOf(1);
    }

    private static byte[] oggOf(int seconds) throws Exception {
        int rate = 48_000;
        short[] tone = new short[rate * seconds];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000);
        }
        return OggOpus.encode(tone, rate, 24_000);
    }

    @Test
    void aVoiceNoteIsUnpackedToRawSound() throws Exception {
        Optional<Sound> sound = OggOpusReader.decode(oggOfOneSecond(), 300);

        assertTrue(sound.isPresent(), "раскодировать не вышло");
        assertEquals(16_000, sound.get().rate(), "слух просит частоту, на которой слышно речь");
        // секунда тона — это 16000 отсчётов минус то, чем кодек разгоняется
        int length = sound.get().samples().length;
        assertTrue(length > 15_000 && length <= 16_000, "длительность не сошлась: " + length);
        int loudest = loudest(sound.get().samples());
        assertTrue(loudest > 1000, "вместо звука получилась тишина: " + loudest);
        assertFalse(sound.get().cut(), "целую запись нечего помечать обрезанной");
    }

    /**
     * Запись длиннее бюджета раньше уходила пустым результатом: шестиминутное
     * голосовое доходило до модели молчанием, хотя начало в нём есть, и
     * раскодировать его дешевле, чем весь монолог.
     */
    @Test
    void aRecordLongerThanTheBudgetIsCutAndSaysSo() throws Exception {
        Optional<Sound> sound = OggOpusReader.decode(oggOf(3), 1);

        assertTrue(sound.isPresent(), "начало записи терять незачем");
        assertTrue(sound.get().cut(), "хвост отрезан — модель должна об этом знать");
        assertEquals(16_000, sound.get().samples().length, "в бюджет входит ровно секунда речи");
    }

    @Test
    void nothingIsHeardOfAZeroBudget() throws Exception {
        // нулевой бюджет не значит «слушать сколько влезет»: в нём не помещается
        // ни один отсчёт, и отдавать такое как услышанное нельзя
        assertTrue(OggOpusReader.decode(oggOfOneSecond(), 0).isEmpty());
    }

    @Test
    void anythingThatIsNotOpusIsRefused() throws Exception {
        byte[] ogg = oggOfOneSecond();
        // форма целая, но описание потока подменено: внутри мог оказаться
        // vorbis, speex или просто мусор из чужого сообщения
        byte[] wrong = ogg.clone();
        int at = indexOf(wrong, "OpusHead");
        assertTrue(at > 0, "образец должен начинаться с описания потока");
        System.arraycopy("01234567".getBytes(StandardCharsets.US_ASCII), 0, wrong, at, 8);
        assertTrue(OggOpusReader.decode(wrong, 300).isEmpty());

        assertTrue(OggOpusReader.decode(new byte[64], 300).isEmpty(), "не поток — не ищем в нём пакеты");

        // поток, обрезанный на середине: раскодированное до обрыва отдаём, а не теряем
        byte[] half = Arrays.copyOf(ogg, ogg.length / 2);
        Optional<Sound> sound = OggOpusReader.decode(half, 300);
        assertTrue(sound.isPresent(), "половина записи лучше, чем ничего");
        assertTrue(sound.get().samples().length < 16_000);
    }

    @Test
    void aPacketSpanningPagesIsStitchedBack() {
        // пакет в 300 байт: полная строка в 255 и остаток в 45 — страница
        // кончается на полной строке, и остаток дописывается следующей
        byte[] packet = new byte[300];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = (byte) i;
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(page(0x02, 0, List.of(head(), tags())));
        stream.writeBytes(rawPage(0x00, 4800, new int[] {255}, Arrays.copyOf(packet, 255)));
        stream.writeBytes(rawPage(0x01, 9600, new int[] {45}, Arrays.copyOfRange(packet, 255, 300)));

        List<byte[]> packets = OggOpusReader.packets(stream.toByteArray());

        assertEquals(3, packets.size(), "пакет через границу страниц — это один пакет");
        assertArrayEquals(packet, packets.get(2), "хвост дописан из второй страницы");
    }

    /** Описание потока: один канал, разгон в 312 отсчётов, вход на 48 кГц. */
    private static byte[] head() {
        ByteBuffer buffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
        buffer.put((byte) 1).put((byte) 1);
        buffer.putShort((short) 312);
        buffer.putInt(48_000);
        buffer.putShort((short) 0).put((byte) 0);
        return buffer.array();
    }

    private static byte[] tags() {
        byte[] vendor = "raine".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + vendor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("OpusTags".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(vendor.length).put(vendor).putInt(0);
        return buffer.array();
    }

    /**
     * Страница с целыми пакетами внутри: таблица длин составляется по правилу
     * «255 — значит, пакет продолжается».
     */
    private static byte[] page(int flags, long granule, List<byte[]> parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> lacing = new ArrayList<>();
        for (byte[] part : parts) {
            int written = 0;
            while (part.length - written >= 255) {
                lacing.add(255);
                body.writeBytes(Arrays.copyOfRange(part, written, written + 255));
                written += 255;
            }
            lacing.add(part.length - written);
            body.writeBytes(Arrays.copyOfRange(part, written, part.length));
        }
        int[] table = new int[lacing.size()];
        for (int i = 0; i < table.length; i++) {
            table[i] = lacing.get(i);
        }
        return rawPage(flags, granule, table, body.toByteArray());
    }

    /** Страница целиком: подпись, флаги, позиция, таблица длин и тело. */
    private static byte[] rawPage(int flags, long granule, int[] lacing, byte[] body) {
        // контрольную сумму разбор не проверяет, поэтому она нулевая
        ByteBuffer page = ByteBuffer.allocate(27 + lacing.length + body.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes(StandardCharsets.US_ASCII));
        page.put((byte) 0).put((byte) flags);
        page.putLong(granule);
        page.putInt(7);            // поток один
        page.putInt(lacing.length);
        page.putInt(0);
        page.put((byte) lacing.length);
        for (int value : lacing) {
            page.put((byte) value);
        }
        page.put(body);
        return page.array();
    }

    private static int indexOf(byte[] data, String marker) {
        byte[] needle = marker.getBytes(StandardCharsets.US_ASCII);
        for (int at = 0; at + needle.length <= data.length; at++) {
            int i = 0;
            while (i < needle.length && data[at + i] == needle[i]) {
                i++;
            }
            if (i == needle.length) {
                return at;
            }
        }
        return -1;
    }

    private static int loudest(short[] samples) {
        int loudest = 0;
        for (short sample : samples) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        return loudest;
    }
}
