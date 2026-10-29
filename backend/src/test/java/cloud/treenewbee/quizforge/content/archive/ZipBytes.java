package cloud.treenewbee.quizforge.content.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Minimal single-disk ZIP writer: deliberately permits invalid metadata and duplicate names. */
final class ZipBytes {
    private ZipBytes() {}

    record Entry(byte[] name, byte[] encoded, int method, int flags, long crc, long size, long attributes) {
        static Entry stored(String name, byte[] bytes) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            return new Entry(name.getBytes(StandardCharsets.UTF_8), bytes, 0, 0x800, crc.getValue(), bytes.length, 0);
        }

        Entry named(byte[] value) { return new Entry(value, encoded, method, flags, crc, size, attributes); }
        Entry size(long value) { return new Entry(name, encoded, method, flags, crc, value, attributes); }
        Entry crc(long value) { return new Entry(name, encoded, method, flags, value, size, attributes); }
        Entry flags(int value) { return new Entry(name, encoded, method, value, crc, size, attributes); }
        Entry mode(int value) { return new Entry(name, encoded, method, flags, crc, size, (long) value << 16); }
        Entry encoded(byte[] value) { return new Entry(name, value, method, flags, crc, size, attributes); }
    }

    static Entry deflatedZeros(String name, long expandedBytes) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        CRC32 crc = new CRC32();
        byte[] block = new byte[8192];
        try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed, deflater)) {
            for (long remaining = expandedBytes; remaining > 0;) {
                int count = (int) Math.min(remaining, block.length);
                stream.write(block, 0, count);
                crc.update(block, 0, count);
                remaining -= count;
            }
        } finally { deflater.end(); }
        return new Entry(name.getBytes(StandardCharsets.UTF_8), compressed.toByteArray(), 8, 0x800,
                crc.getValue(), expandedBytes, 0);
    }

    static Path write(Path target, Entry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        for (Entry entry : entries) {
            offsets.add(out.size());
            integer(out, 0x04034b50); word(out, 20); word(out, entry.flags()); word(out, entry.method());
            word(out, 0); word(out, 0); integer(out, entry.crc());
            integer(out, entry.encoded().length); integer(out, entry.size());
            word(out, entry.name().length); word(out, 0);
            out.writeBytes(entry.name()); out.writeBytes(entry.encoded());
        }
        int centralOffset = out.size();
        for (int index = 0; index < entries.length; index++) {
            Entry entry = entries[index];
            integer(out, 0x02014b50); word(out, 0x0314); word(out, 20);
            word(out, entry.flags()); word(out, entry.method()); word(out, 0); word(out, 0);
            integer(out, entry.crc()); integer(out, entry.encoded().length); integer(out, entry.size());
            word(out, entry.name().length); word(out, 0); word(out, 0); word(out, 0); word(out, 0);
            integer(out, entry.attributes()); integer(out, offsets.get(index)); out.writeBytes(entry.name());
        }
        int centralSize = out.size() - centralOffset;
        integer(out, 0x06054b50); word(out, 0); word(out, 0);
        word(out, entries.length); word(out, entries.length);
        integer(out, centralSize); integer(out, centralOffset); word(out, 0);
        Files.write(target, out.toByteArray());
        return target;
    }

    private static void word(ByteArrayOutputStream out, long value) {
        out.write((int) value & 255); out.write((int) (value >>> 8) & 255);
    }

    private static void integer(ByteArrayOutputStream out, long value) {
        word(out, value); word(out, value >>> 16);
    }
}
