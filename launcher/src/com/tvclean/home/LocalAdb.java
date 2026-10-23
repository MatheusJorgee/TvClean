package com.tvclean.home;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * A tiny ADB client that talks to the TV's own adbd on 127.0.0.1:5555 (network debugging is on), so Home
 * can do what tv/check.ps1 does from the PC: switch TCL's apps back off, reapply settings, free memory.
 * Shell-level rights only, the same as `adb shell`. The first connection makes the TV ask
 * "Allow debugging?" for this app's key; after "Always allow", it connects silently.
 *
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 */
final class LocalAdb implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** ASN.1 DigestInfo header for SHA-1: adbd's token is signed as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private LocalAdb(Socket s) throws Exception {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Connects and authenticates. `waitForUserMs` is how long to wait for the "Allow debugging?" answer
     * the first time (0: fail at once if the key is not authorized yet).
     */
    static LocalAdb connect(Context c, int waitForUserMs) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setSoTimeout(5000);
        LocalAdb adb = new LocalAdb(s);
        try {
            adb.handshake(keys(c), waitForUserMs);
            return adb;
        } catch (Exception e) {
            adb.close();
            throw e;
        }
    }

    private void handshake(KeyPair keys, int waitForUserMs) throws Exception {
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, offeredKey = false;
        while (true) {
            Message m = read();
            if (m.command == CNXN) return;
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (!signed) {
                send(AUTH, AUTH_SIGNATURE, 0, sign((RSAPrivateKey) keys.getPrivate(), m.data));
                signed = true;
            } else if (!offeredKey) {
                if (waitForUserMs <= 0) throw new Exception("not authorized yet");
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                offeredKey = true;
                socket.setSoTimeout(waitForUserMs);   // the TV is showing "Allow debugging?" now
            } else {
                throw new Exception("refused");
            }
        }
    }

    /** Runs `command` in a shell on the TV and returns what it printed. */
    String shell(String command) throws Exception {
        socket.setSoTimeout(30000);
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != local && m.command != CNXN) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                text.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                if (remote != 0) send(CLSE, local, remote, new byte[0]);
                break;
            }
        }
        return text.toString("UTF-8");
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (Exception ignored) {
            // Already gone.
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws Exception {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws Exception {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    /** PKCS#1 v1.5 (block type 1) over SHA-1 DigestInfo + token, computed directly: no provider quirks. */
    private static byte[] sign(RSAPrivateKey key, byte[] token) {
        int size = (key.getModulus().bitLength() + 7) / 8;
        byte[] block = new byte[size];
        block[0] = 0;
        block[1] = 1;
        int dataStart = size - SHA1_PREFIX.length - token.length;
        for (int i = 2; i < dataStart - 1; i++) block[i] = (byte) 0xFF;
        block[dataStart - 1] = 0;
        System.arraycopy(SHA1_PREFIX, 0, block, dataStart, SHA1_PREFIX.length);
        System.arraycopy(token, 0, block, dataStart + SHA1_PREFIX.length, token.length);
        byte[] s = new BigInteger(1, block).modPow(key.getPrivateExponent(), key.getModulus()).toByteArray();
        byte[] outBytes = new byte[size];   // left-pad (or drop the sign byte) to exactly `size`
        int copy = Math.min(s.length, size);
        System.arraycopy(s, s.length - copy, outBytes, size - copy, copy);
        return outBytes;
    }

    /** adbd's key format: Android's mincrypt RSAPublicKey struct, base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        int words = key.getModulus().bitLength() / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = key.getModulus();
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvclean@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** This app's ADB key pair, made once and kept in its private files. */
    private static synchronized KeyPair keys(Context c) throws Exception {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    f.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(priv)) {
            o.write(kp.getPrivate().getEncoded());
        }
        try (FileOutputStream o = new FileOutputStream(pub)) {
            o.write(kp.getPublic().getEncoded());
        }
        return kp;
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        try (DataInputStream d = new DataInputStream(new FileInputStream(f))) {
            d.readFully(data);
        }
        return data;
    }
}
