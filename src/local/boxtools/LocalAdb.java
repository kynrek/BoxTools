package local.boxtools;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Just enough of the ADB protocol to run one shell command through the box's own adbd, which this firmware
 * keeps listening on TCP port 5555 whether or not there's Wi-Fi. The first connection makes Android ask
 * "Allow USB debugging?" on screen; with "Always allow" ticked, later ones go straight through.
 */
final class LocalAdb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    // DER prefix of a SHA-1 DigestInfo: adbd checks the signature as RSA PKCS#1 v1.5 over a SHA-1 "digest".
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    static final class Unauthorized extends IOException {
        Unauthorized() { super("not allowed on the box yet"); }
    }

    private final Socket socket = new Socket();
    private DataInputStream in;
    private OutputStream out;
    private int remoteMax = 4096;

    private LocalAdb() { }

    /** Connect and authenticate. Waits up to authWaitMs for the person to answer the "Allow" prompt. */
    static LocalAdb connect(Context c, int port, long authWaitMs) throws IOException, GeneralSecurityException {
        LocalAdb adb = new LocalAdb();
        try {
            adb.open(c, port, authWaitMs);
            return adb;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    private void open(Context c, int port, long authWaitMs) throws IOException, GeneralSecurityException {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setSoTimeout(10000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        KeyPair key = key(c);
        boolean signed = false;
        while (true) {
            Message m = read();
            if (m.command == CNXN) {
                remoteMax = m.arg1;
                socket.setSoTimeout(10000);
                return;
            }
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected " + m);
            if (!signed) {
                signed = true;
                Signature s = Signature.getInstance("NONEwithRSA");
                s.initSign(key.getPrivate());
                s.update(SHA1_PREFIX);
                s.update(m.data);
                send(AUTH, AUTH_SIGNATURE, 0, s.sign());
            } else {
                // Not known yet: offer the public key, which makes Android show the prompt.
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) key.getPublic()));
                socket.setSoTimeout((int) authWaitMs);
                try {
                    Message reply = read();
                    if (reply.command != CNXN) throw new IOException("unexpected " + reply);
                    remoteMax = reply.arg1;
                    socket.setSoTimeout(10000);
                    return;
                } catch (java.net.SocketTimeoutException e) {
                    throw new Unauthorized();
                }
            }
        }
    }

    /**
     * Run a shell command and return its output. Uses "exec:", not "shell:": the plain shell service runs the
     * command on a terminal, and closing it at hang-up killed the Shizuku server the command had just started.
     */
    String exec(String command, long timeoutMs) throws IOException {
        int local = 1;
        send(OPEN, local, 0, ("exec:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        socket.setSoTimeout((int) timeoutMs);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                output.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                if (remote != 0) send(CLSE, local, remote, new byte[0]);
                return output.toString("UTF-8");
            }
        }
    }

    void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- messages ----

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
        @Override public String toString() { return Integer.toHexString(command) + " " + arg0 + " " + arg1; }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        if (data.length > Math.max(remoteMax, 4096) && command != CNXN) throw new IOException("message too big");
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int length = h.getInt();
        h.getInt();   // checksum; adbd at this version doesn't require it
        if (h.getInt() != ~m.command) throw new IOException("bad message");
        if (length < 0 || length > MAX_DATA) throw new IOException("bad length " + length);
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    // ---- key ----

    /** This app's own ADB key, made on first use and kept in its private storage. */
    private static synchronized KeyPair key(Context c) throws IOException, GeneralSecurityException {
        File priv = new File(c.getNoBackupFilesDir(), "adbkey"), pub = new File(c.getNoBackupFilesDir(), "adbkey.pub");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            PrivateKey p = rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath())));
            return new KeyPair(rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))), p);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        Files.write(priv.toPath(), k.getPrivate().getEncoded());
        Files.write(pub.toPath(), k.getPublic().getEncoded());
        return k;
    }

    /** The public key in adbd's format: Android's RSA public key struct, base64, then a name. */
    private static byte[] publicKey(RSAPublicKey k) {
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        int words = 64;   // 2048-bit key
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());   // -1 / n[0] mod 2^32
        b.put(littleEndian(n, 256));
        b.put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(n), 256));   // R^2 mod n, R = 2^2048
        b.putInt(k.getPublicExponent().intValue());
        String line = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " boxtools@box\0";
        return line.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] big = v.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }
}
