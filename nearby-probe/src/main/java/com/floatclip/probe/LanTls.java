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

final class LanTls {
    private static final String ALIAS = "floatclip-p0-lan-tls-v2";
    static InetAddress checkedAddress(String value) throws IOException {
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

    private static synchronized KeyStore identity() throws Exception {
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

    static SSLContext context() throws Exception {
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{serverIdentity(identity())},null,new SecureRandom());
        return context;
    }
    static String pin() throws Exception {
        byte[] digest=com.floatclip.share.ShareWire.digest(identity().getCertificate(ALIAS).getEncoded());
        StringBuilder out=new StringBuilder();
        for(byte b:digest) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
}
