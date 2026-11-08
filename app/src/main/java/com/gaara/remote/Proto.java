package com.gaara.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal protobuf encoding, just what the Android TV Remote v2 protocol needs:
 * varints, strings/bytes and nested messages. Messages on the wire are prefixed
 * by their length as a varint.
 */
final class Proto {
    private Proto() {}

    /** Builds one message. */
    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer varint(int field, long value) {
            writeVarint(out, ((long) field << 3));
            writeVarint(out, value);
            return this;
        }

        Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        Writer bytes(int field, byte[] value) {
            writeVarint(out, ((long) field << 3) | 2);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Writer message(int field, Writer inner) {
            return bytes(field, inner.toBytes());
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }

    /** One decoded field: number, wire type and value (varint or bytes). */
    static final class Field {
        final int number;
        final int wireType;
        final long varint;
        final byte[] data;

        Field(int number, int wireType, long varint, byte[] data) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.data = data;
        }

        String string() {
            return data == null ? "" : new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Decoded message: the list of its top-level fields. */
    static final class Message {
        final List<Field> fields;

        Message(List<Field> fields) {
            this.fields = fields;
        }

        Field get(int number) {
            for (Field f : fields) if (f.number == number) return f;
            return null;
        }

        boolean has(int number) {
            return get(number) != null;
        }

        long varint(int number, long def) {
            Field f = get(number);
            return f == null ? def : f.varint;
        }

        String string(int number) {
            Field f = get(number);
            return f == null ? "" : f.string();
        }

        /** Nested message, or an empty one when the field is absent. */
        Message message(int number) {
            Field f = get(number);
            return parse(f == null || f.data == null ? new byte[0] : f.data);
        }
    }

    static Message parse(byte[] buf) {
        List<Field> fields = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < buf.length) {
            long key = readVarint(buf, pos);
            int number = (int) (key >>> 3);
            int type = (int) (key & 7);
            switch (type) {
                case 0:
                    fields.add(new Field(number, type, readVarint(buf, pos), null));
                    break;
                case 2: {
                    int len = (int) readVarint(buf, pos);
                    if (len < 0 || pos[0] + len > buf.length) throw new IllegalArgumentException("bad length");
                    byte[] d = new byte[len];
                    System.arraycopy(buf, pos[0], d, 0, len);
                    pos[0] += len;
                    fields.add(new Field(number, type, 0, d));
                    break;
                }
                case 1:
                    pos[0] += 8;
                    break;
                case 5:
                    pos[0] += 4;
                    break;
                default:
                    throw new IllegalArgumentException("wire type " + type);
            }
        }
        return new Message(fields);
    }

    static void writeVarint(OutputStream out, long value) {
        try {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= buf.length) throw new IllegalArgumentException("truncated varint");
            int b = buf[pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("varint too long");
        }
    }

    /** Writes a length-prefixed message. */
    static void send(OutputStream out, Writer msg) throws IOException {
        byte[] body = msg.toBytes();
        ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 5);
        writeVarint(frame, body.length);
        frame.write(body, 0, body.length);
        out.write(frame.toByteArray());
        out.flush();
    }

    /** Reads one length-prefixed message; blocks. */
    static Message receive(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new IOException("connexion fermée");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("longueur invalide");
        }
        if (len > 1 << 20) throw new IOException("message trop gros");
        byte[] body = new byte[(int) len];
        int off = 0;
        while (off < body.length) {
            int n = in.read(body, off, body.length - off);
            if (n < 0) throw new IOException("connexion fermée");
            off += n;
        }
        return parse(body);
    }
}
