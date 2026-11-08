package com.gaara.remote;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The phone's identity towards the TV: an RSA key and a self-signed certificate,
 * created once and kept in the app's private storage. The TV remembers this
 * certificate when pairing, so it must stay the same afterwards.
 */
final class Identity {
    // v2: unique name + CA/SAN extensions. The TV refused the v1 certificate on reconnection.
    private static final String KEY_FILE = "client_key_v2.pk8";
    private static final String CERT_FILE = "client_cert_v2.der";
    private static final String[] OLD_FILES = {"client_key.pk8", "client_cert.der"};

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private Identity(PrivateKey key, X509Certificate cert) {
        this.privateKey = key;
        this.certificate = cert;
    }

    private static Identity cached;

    /** An older identity is unknown to the TVs: drop it and forget its pairings, so the app asks again. */
    static void migrate(Context ctx) {
        boolean hadOld = false;
        for (String old : OLD_FILES) hadOld |= new File(ctx.getFilesDir(), old).delete();
        if (hadOld) Devices.clear(ctx);
    }

    static synchronized Identity get(Context ctx) throws Exception {
        if (cached != null) return cached;
        File dir = ctx.getFilesDir();
        File keyFile = new File(dir, KEY_FILE);
        File certFile = new File(dir, CERT_FILE);
        if (keyFile.exists() && certFile.exists()) {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            cached = new Identity(key, parseCert(Files.readAllBytes(certFile.toPath())));
            return cached;
        }
        migrate(ctx);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair kp = gen.generateKeyPair();
        // The TV replaces any stored certificate with the same name, so the name must be ours alone.
        String model = android.os.Build.MODEL.replaceAll("[^A-Za-z0-9-]", "");
        String cn = "Telecommande-" + model + "-" + String.format("%06x", new SecureRandom().nextInt(0x1000000));
        byte[] certDer = selfSignedCert(kp, cn);
        write(keyFile, kp.getPrivate().getEncoded());
        write(certFile, certDer);
        // Public certificate only (no key), to check its format with openssl if needed.
        android.util.Log.i("Nexio", "cert " + android.util.Base64.encodeToString(certDer, android.util.Base64.NO_WRAP));
        cached = new Identity(kp.getPrivate(), parseCert(certDer));
        return cached;
    }

    /** TLS context that presents our certificate. The server certificate is checked by the caller. */
    SSLContext sslContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{new AlwaysOurs()}, new TrustManager[]{new AcceptAny()}, new SecureRandom());
        return ctx;
    }

    /**
     * Always offers our certificate. The default key manager drops it when the TV
     * asks for certificates from specific issuers, and the TV then refuses the link.
     */
    private final class AlwaysOurs extends X509ExtendedKeyManager {
        private static final String ALIAS = "client";

        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[]{ALIAS}; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[]{certificate}; }
        @Override public PrivateKey getPrivateKey(String alias) { return privateKey; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) certificate.getPublicKey();
    }

    /** SHA-256 of a certificate, as hex: used to recognise a TV after pairing. */
    static String fingerprint(X509Certificate cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * The TV's certificate is self-signed, so the usual chain check cannot apply.
     * Instead, the app pins the certificate seen during pairing (see RemoteClient).
     */
    private static final class AcceptAny implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
            if (c == null || c.length == 0) throw new CertificateException("aucun certificat");
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static X509Certificate parseCert(byte[] der) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    // ---- Minimal DER encoder for one self-signed X.509 v3 certificate ----

    private static final byte[] SHA256_WITH_RSA = {
            0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00};

    private static byte[] selfSignedCert(KeyPair kp, String cn) throws Exception {
        byte[] name = seq(set(seq(cat(
                new byte[]{0x06, 0x03, 0x55, 0x04, 0x03}, // OID commonName
                tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8))))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(cat(
                utcTime(new Date(now - 24L * 3600 * 1000)),
                utcTime(new Date(now + 10L * 365 * 24 * 3600 * 1000))));
        // Same extensions as the reference client: basicConstraints CA:TRUE pathlen 0, and a DNS name.
        byte[] basicConstraints = seq(cat(
                new byte[]{0x06, 0x03, 0x55, 0x1D, 0x13},
                tlv(0x04, seq(cat(new byte[]{0x01, 0x01, (byte) 0xFF}, new byte[]{0x02, 0x01, 0x00})))));
        byte[] subjectAltName = seq(cat(
                new byte[]{0x06, 0x03, 0x55, 0x1D, 0x11},
                tlv(0x04, seq(tlv(0x82, cn.getBytes(StandardCharsets.US_ASCII))))));
        byte[] tbs = seq(cat(
                tlv(0xA0, tlv(0x02, new byte[]{2})),          // version v3
                tlv(0x02, BigInteger.valueOf(Math.abs(new SecureRandom().nextLong()) | 1).toByteArray()),
                SHA256_WITH_RSA,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),                   // SubjectPublicKeyInfo
                tlv(0xA3, seq(cat(basicConstraints, subjectAltName)))));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        return seq(cat(tbs, SHA256_WITH_RSA, tlv(0x03, bitString)));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] seq(byte[] content) { return tlv(0x30, content); }

    private static byte[] set(byte[] content) { return tlv(0x31, content); }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
