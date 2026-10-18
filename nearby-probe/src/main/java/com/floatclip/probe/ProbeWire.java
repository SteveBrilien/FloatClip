package com.floatclip.probe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** Synthetic P0 probes only. SHA-256 detects corruption; it is NOT authentication. */
public final class ProbeWire {
    public static final UUID SERVICE = UUID.fromString("db7aa0e6-0ccd-47d0-b749-80f17b9bfc21");
    public static final int MAGIC = 0x46435030; // FCP0
    public static final int VERSION = 1;
    public static final int PROBE = 1;
    public static final int ACK = 2;
    public static final int HEADER_BYTES = 58;
    public static final int MAX_PAYLOAD_BYTES = 16_384;

    private ProbeWire() { }

    public static final class Frame {
        public final int kind;
        public final UUID id;
        private final byte[] data;

        public Frame(int kind, UUID id, byte[] payload) {
            if ((kind != PROBE && kind != ACK) || id == null || payload == null
                    || payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Invalid probe frame");
            }
            if (kind == ACK && payload.length != 32) {
                throw new IllegalArgumentException("ACK must carry the original digest");
            }
            this.kind = kind;
            this.id = id;
            this.data = payload.clone();
        }

        public byte[] payload() { return data.clone(); }
    }

    public static byte[] testPayload(UUID id) {
        return ("FloatClip-P0\n  Unicode: 中文 / emoji \uD83D\uDCCB\n\tindent\r\n"
                + id.toString() + "  \n").getBytes(StandardCharsets.UTF_8);
    }

    public static Frame probe(UUID id) { return new Frame(PROBE, id, testPayload(id)); }

    public static Frame acknowledge(Frame frame) throws IOException {
        if (frame.kind != PROBE || !Arrays.equals(testPayload(frame.id), frame.data)) {
            throw new IOException("Only generated P0 payloads are accepted");
        }
        return new Frame(ACK, frame.id, digest(frame.data));
    }

    public static boolean matchesAck(Frame ack, Frame sent) {
        return ack.kind == ACK && sent.kind == PROBE && ack.id.equals(sent.id)
                && MessageDigest.isEqual(ack.data, digest(sent.data));
    }

    public static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(frame.kind);
        out.writeLong(frame.id.getMostSignificantBits());
        out.writeLong(frame.id.getLeastSignificantBits());
        out.writeInt(frame.data.length);
        out.write(digest(frame.data));
        out.write(frame.data);
        out.flush();
    }

    /** null means clean EOF before a frame; a partial frame always throws. */
    public static Frame read(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) return null;
        DataInputStream in = new DataInputStream(input);
        int magic = (first << 24) | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (magic != MAGIC) throw new IOException("Wrong protocol magic");
        if (in.readUnsignedByte() != VERSION) throw new IOException("Unsupported protocol version");
        int kind = in.readUnsignedByte();
        if (kind != PROBE && kind != ACK) throw new IOException("Unknown frame kind");
        UUID id = new UUID(in.readLong(), in.readLong());
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD_BYTES || (kind == ACK && length != 32)) {
            throw new IOException("Invalid frame length");
        }
        byte[] expected = new byte[32];
        in.readFully(expected);
        byte[] payload = new byte[length];
        in.readFully(payload);
        if (!MessageDigest.isEqual(expected, digest(payload))) throw new IOException("Digest mismatch");
        return new Frame(kind, id, payload);
    }
}
