package com.floatclip.probe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;

/** Foreground, one-use LAN probe. No clipboard or user files. */
public final class LanProbe implements AutoCloseable {
    public interface Events {
        void ready(String address, int port, String certificatePin, String invite);
        void event(String message);
        void ended(boolean success);
    }
    private static final String ALIAS = "floatclip-p0-lan-tls-v2";
    private final Events events;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private int generation;
    private ServerSocket listener;
    private Socket session;
    private ScheduledFuture<?> deadline;

    public LanProbe(Events events) { this.events = events; }

    public synchronized void start(String localAddress, int port) {
        stop();
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Use port 1024..65535");
        final int epoch = generation;
        worker.execute(() -> serve(epoch, localAddress, port));
    }

    private static InetAddress checkedAddress(String value) throws IOException {
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("Use a local IPv4 address");
        InetAddress address = InetAddress.getByName(value);
        NetworkInterface nic = NetworkInterface.getByInetAddress(address);
        if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress() || nic == null ||
                !nic.isUp() || !(nic.getName().startsWith("wlan") || nic.getName().startsWith("eth") ||
                nic.getName().startsWith("ap") || nic.getName().startsWith("en"))) {
            throw new IOException("Select an existing private Wi-Fi/hotspot/Ethernet IPv4 address");
        }
        return address;
    }

    private static KeyStore identity() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    // Conscrypt signs TLS digests itself; authorize raw operations per Android TLS guidance.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,
                            KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=FloatClip LAN P0"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(new Date(System.currentTimeMillis() + 365L * 86400000L))
                    .build());
            generator.generateKeyPair();
            keys.load(null);
        }
        return keys;
    }

    /** Only this probe identity may be selected, even when an older debug key exists. */
    private static X509KeyManager serverIdentity(KeyStore keys) throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, null);
        for (KeyManager manager : factory.getKeyManagers()) {
            if (!(manager instanceof X509KeyManager)) continue;
            final X509KeyManager delegate = (X509KeyManager) manager;
            return new X509KeyManager() {
                @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
                @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
                @Override public String[] getServerAliases(String type, Principal[] issuers) {
                    return "RSA".equals(type) ? new String[]{ALIAS} : null;
                }
                @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
                    return "RSA".equals(type) ? ALIAS : null;
                }
                @Override public X509Certificate[] getCertificateChain(String alias) {
                    return ALIAS.equals(alias) ? delegate.getCertificateChain(alias) : null;
                }
                @Override public PrivateKey getPrivateKey(String alias) {
                    return ALIAS.equals(alias) ? delegate.getPrivateKey(alias) : null;
                }
            };
        }
        throw new IOException("No X509 key manager for LAN identity");
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private void serve(int epoch, String addressText, int port) {
        try {
            InetAddress address = checkedAddress(addressText);
            KeyStore keys = identity();
            String pin = hex(ProbeWire.digest(keys.getCertificate(ALIAS).getEncoded()));
            byte[] invite = new byte[32];
            new SecureRandom().nextBytes(invite);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[]{serverIdentity(keys)}, null, new SecureRandom());
            try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(address, port), 1);
                server.setEnabledProtocols(new String[]{"TLSv1.2"});
                server.setSoTimeout(90000);
                synchronized (this) {
                    if (epoch != generation) return;
                    listener = server;
                    deadline = timer.schedule(() -> finish(epoch, false, "LAN invitation expired / session timed out"), 90, TimeUnit.SECONDS);
                }
                if (!active(epoch)) return;
                events.ready(address.getHostAddress(), port, pin, hex(invite));
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    synchronized (this) {
                        if (epoch != generation) return;
                        session = socket;
                        server.close();
                        listener = null;
                        deadline.cancel(false);
                        deadline = timer.schedule(() -> finish(epoch, false, "LAN session timed out"), 30, TimeUnit.SECONDS);
                    }
                    socket.setSoTimeout(10000);
                    socket.setTcpNoDelay(true);
                    socket.startHandshake();
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    if (input.readInt() != 0x46434c31) throw new IOException("Wrong LAN invitation protocol");
                    byte[] offered = new byte[32];
                    input.readFully(offered);
                    if (!MessageDigest.isEqual(invite, offered)) throw new IOException("LAN invitation rejected");
                    java.util.Arrays.fill(invite, (byte) 0);
                    socket.getOutputStream().write(1);
                    socket.getOutputStream().flush();
                    events.event("LAN " + socket.getSession().getProtocol() + " " +
                            socket.getSession().getCipherSuite() + " + one-use invitation authenticated");
                    for (int i = 1; i <= 10; i++) {
                        if (!active(epoch)) return;
                        ProbeWire.Frame sent = ProbeWire.probe(UUID.randomUUID());
                        long began = System.nanoTime();
                        ProbeWire.write(socket.getOutputStream(), sent);
                        ProbeWire.Frame ack = ProbeWire.read(input);
                        if (ack == null || !ProbeWire.matchesAck(ack, sent)) throw new IOException("LAN ACK mismatch");
                        double ms = (System.nanoTime() - began) / 1000000.0;
                        ProbeWire.Frame received = ProbeWire.read(input);
                        if (received == null) throw new IOException("LAN peer disconnected");
                        ProbeWire.write(socket.getOutputStream(), ProbeWire.acknowledge(received));
                        events.event(String.format(java.util.Locale.ROOT, "LAN TX/RX %d/10 verified; RTT %.1f ms", i, ms));
                    }
                    finish(epoch, true, "PASS LAN: 10 outgoing + 10 incoming generated probes");
                }
            }
        } catch (Exception error) {
            finish(epoch, false, "LAN failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private synchronized boolean active(int epoch) { return epoch == generation; }
    private synchronized void finish(int epoch, boolean success, String message) {
        if (epoch != generation) return;
        stop();
        events.event(message);
        events.ended(success);
    }
    public synchronized void stop() {
        generation++;
        if (deadline != null) { deadline.cancel(false); deadline = null; }
        try { if (session != null) session.close(); } catch (IOException ignored) { }
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        session = null;
        listener = null;
    }
    @Override public void close() { stop(); worker.shutdownNow(); timer.shutdownNow(); }
}
