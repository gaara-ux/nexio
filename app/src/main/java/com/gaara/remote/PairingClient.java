package com.gaara.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;

import javax.net.ssl.SSLSocket;

/**
 * Pairing ("Polo") with an Android / Google TV on port 6467.
 *
 * 1. start(): handshake; the TV then shows a 6-character code.
 * 2. finish(code): proves we saw the code; the TV now trusts our certificate.
 *
 * Blocking: call from a background thread.
 */
final class PairingClient {
    static final int PORT = 6467;

    private static final int STATUS_OK = 200;
    private static final int ENCODING_HEX = 3;
    private static final int ROLE_INPUT = 1;

    private final Identity identity;
    private final String host;
    private final String clientName;
    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private X509Certificate serverCert;

    PairingClient(Identity identity, String host, String clientName) {
        this.identity = identity;
        this.host = host;
        this.clientName = clientName;
    }

    /** Runs the handshake up to the point where the TV displays the code. */
    void start() throws Exception {
        socket = (SSLSocket) identity.sslContext().getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(host, PORT), 5000);
        socket.setSoTimeout(15000);
        socket.startHandshake();
        serverCert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        in = socket.getInputStream();
        out = socket.getOutputStream();

        send(outer().message(10, new Proto.Writer()
                .string(1, "atvremote")
                .string(2, clientName)));
        expect(11, "la TV n'a pas accepté la demande");

        send(outer().message(20, new Proto.Writer()
                .message(1, encoding())
                .varint(3, ROLE_INPUT)));
        expect(20, "options refusées");

        send(outer().message(30, new Proto.Writer()
                .message(1, encoding())
                .varint(2, ROLE_INPUT)));
        expect(31, "configuration refusée");
        // The code is now on the TV screen. Leave time for the person to read it.
        socket.setSoTimeout(0);
    }

    /** Sends the code. Returns the TV certificate fingerprint to remember. */
    String finish(String code) throws Exception {
        code = code.trim().toUpperCase();
        if (!code.matches("[0-9A-F]{6}")) throw new IOException("Le code a 6 caractères (0-9 et A-F).");
        byte[] secret = secret(code);
        if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
            throw new IOException("Code incorrect. Vérifie les caractères sur la TV.");
        }
        socket.setSoTimeout(15000);
        send(outer().message(40, new Proto.Writer().bytes(1, secret)));
        expect(41, "code refusé par la TV");
        return Identity.fingerprint(serverCert);
    }

    void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    /** SHA-256(client modulus, client exponent, server modulus, server exponent, code[2..]). */
    private byte[] secret(String code) throws Exception {
        RSAPublicKey c = identity.publicKey();
        RSAPublicKey s = (RSAPublicKey) serverCert.getPublicKey();
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        h.update(unsigned(c.getModulus()));
        h.update(unsigned(c.getPublicExponent()));
        h.update(unsigned(s.getModulus()));
        h.update(unsigned(s.getPublicExponent()));
        h.update(hex(code.substring(2)));
        return h.digest();
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    private static Proto.Writer outer() {
        return new Proto.Writer().varint(1, 2).varint(2, STATUS_OK);
    }

    private static Proto.Writer encoding() {
        return new Proto.Writer().varint(1, ENCODING_HEX).varint(2, 6);
    }

    private void send(Proto.Writer msg) throws IOException {
        Proto.send(out, msg);
    }

    private void expect(int field, String error) throws IOException {
        Proto.Message m = Proto.receive(in);
        long status = m.varint(2, 0);
        if (status != STATUS_OK) throw new IOException(error + " (statut " + status + ")");
        if (!m.has(field)) throw new IOException(error);
    }
}
