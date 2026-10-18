import com.floatclip.probe.ProbeWire;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Dependency-free protocol regression tests; not a Bluetooth hardware test. */
public final class ProbeWireTest {
    private static int checks;
    private static final UUID ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private static byte[] bytes(ProbeWire.Frame f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ProbeWire.write(out, f); return out.toByteArray();
    }
    private static void rejects(byte[] data) throws Exception {
        checks++;
        try { ProbeWire.read(new ByteArrayInputStream(data)); }
        catch (IOException expected) { return; }
        throw new AssertionError("Accepted invalid frame at check " + checks);
    }
    public static void main(String[] args) throws Exception {
        ProbeWire.Frame sent = ProbeWire.probe(ID);
        byte[] encoded = bytes(sent);
        ProbeWire.Frame read = ProbeWire.read(new ByteArrayInputStream(encoded));
        check(Arrays.equals(read.payload(), sent.payload()));
        check(ProbeWire.matchesAck(ProbeWire.acknowledge(read), sent));
        check(!ProbeWire.matchesAck(ProbeWire.acknowledge(read), ProbeWire.probe(UUID.randomUUID())));
        check(ProbeWire.read(new ByteArrayInputStream(new byte[0])) == null);
        InputStream partial = new ByteArrayInputStream(encoded) {
            public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(1, len)); }
        };
        check(Arrays.equals(ProbeWire.read(partial).payload(), sent.payload()));
        byte[] twice = new byte[encoded.length * 2];
        System.arraycopy(encoded, 0, twice, 0, encoded.length);
        System.arraycopy(encoded, 0, twice, encoded.length, encoded.length);
        InputStream combined = new ByteArrayInputStream(twice);
        check(ProbeWire.read(combined).id.equals(ID));
        check(ProbeWire.read(combined).id.equals(ID));
        check(ProbeWire.read(combined) == null);
        for (int i = 1; i < encoded.length; i++) rejects(Arrays.copyOf(encoded, i));
        byte[] changed = encoded.clone(); changed[0] ^= 1; rejects(changed);
        changed = encoded.clone(); changed[4] = 2; rejects(changed);
        changed = encoded.clone(); changed[5] = 99; rejects(changed);
        changed = encoded.clone(); Arrays.fill(changed, 22, 26, (byte)255); rejects(changed);
        changed = encoded.clone(); changed[22] = 0x7f; rejects(changed);
        changed = encoded.clone(); changed[changed.length - 1] ^= 1; rejects(changed);
        byte[] copy = sent.payload(); copy[0] = 0;
        check(sent.payload()[0] == 'F');
        byte[] max = new byte[ProbeWire.MAX_PAYLOAD_BYTES];
        check(ProbeWire.read(new ByteArrayInputStream(bytes(new ProbeWire.Frame(1, ID, max)))).payload().length == max.length);
        try { ProbeWire.acknowledge(new ProbeWire.Frame(1, ID, "private".getBytes())); throw new AssertionError(); }
        catch (IOException expected) { checks++; }
        try { new ProbeWire.Frame(2, ID, new byte[31]); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        try { new ProbeWire.Frame(1, ID, new byte[ProbeWire.MAX_PAYLOAD_BYTES + 1]); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        if (args.length > 0) {
            Path dir = Paths.get(args[0]); Files.createDirectories(dir);
            Files.write(dir.resolve("probe.bin"), encoded);
            Files.write(dir.resolve("ack.bin"), bytes(ProbeWire.acknowledge(sent)));
        }
        System.out.println("PASS: " + checks + " protocol checks (no radio hardware exercised)");
    }
}
