package com.tvclean.home;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Reading a stream to the end, shared by the pictures, the trending lists and the weather. */
final class Net {
    private Net() {
    }

    static byte[] readAll(InputStream in) throws Exception {
        return readAll(in, Integer.MAX_VALUE);
    }

    /** Everything in `in`, which is closed; null when it holds more than `max` bytes. */
    static byte[] readAll(InputStream in, int max) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) return null;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
