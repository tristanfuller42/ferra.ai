package app.inkwell;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** MP4 (H.264 via the phone's hardware encoder) and GIF export. No libraries. */
final class Video {
    interface Frames {
        int count();
        Bitmap get(int i); // caller recycles
    }

    /** Rounds a size to what video encoders accept (multiples of 16, max 1920 on the long side). */
    static int[] videoSize(int w, int h) {
        float s = Math.min(1f, 1920f / Math.max(w, h));
        return new int[]{Math.max(16, Math.round(w * s / 16) * 16), Math.max(16, Math.round(h * s / 16) * 16)};
    }

    static void mp4(FileDescriptor fd, int w, int h, int fps, Frames frames) throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, w * h * 3));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface in = enc.createInputSurface();
        enc.start();
        MediaMuxer mux = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] state = {-1, 0}; // track, frames written
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frameUs = 1_000_000L / fps;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        try {
            int n = frames.count();
            for (int i = 0; i < n; i++) {
                Bitmap b = frames.get(i);
                Canvas c = in.lockHardwareCanvas();
                c.drawColor(Color.WHITE);
                if (b != null) {
                    float s = Math.min((float) w / b.getWidth(), (float) h / b.getHeight());
                    int dw = Math.round(b.getWidth() * s), dh = Math.round(b.getHeight() * s);
                    c.drawBitmap(b, null, new Rect((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p);
                    b.recycle();
                }
                in.unlockCanvasAndPost(c);
                // keep the encoder flowing so the input surface never blocks
                for (int tries = 0; tries < 40 && state[1] < i - 1; tries++) drain(enc, mux, info, state, frameUs, 5000);
                drain(enc, mux, info, state, frameUs, 0);
            }
            enc.signalEndOfInputStream();
            for (int tries = 0; tries < 400; tries++) if (drain(enc, mux, info, state, frameUs, 10000)) break;
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            in.release();
            try { if (state[0] >= 0) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
    }

    /** Moves encoded frames to the muxer. Returns true at end of stream. */
    private static boolean drain(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo info, int[] st, long frameUs, long timeoutUs) {
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, timeoutUs);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                st[0] = mux.addTrack(enc.getOutputFormat());
                mux.start();
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer bb = enc.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && st[0] >= 0 && bb != null) {
                info.presentationTimeUs = st[1] * frameUs; // even spacing, whatever the surface timestamps were
                st[1]++;
                bb.position(info.offset);
                bb.limit(info.offset + info.size);
                mux.writeSampleData(st[0], bb, info);
            }
            enc.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            timeoutUs = 0;
        }
    }

    // ---------------- GIF (256 greys = exact for greyscale art) ----------------

    static void gif(OutputStream os, int w, int h, int fps, Frames frames) throws IOException {
        OutputStream o = new java.io.BufferedOutputStream(os, 65536);
        o.write("GIF89a".getBytes("US-ASCII"));
        le16(o, w); le16(o, h);
        o.write(0xF7); o.write(0); o.write(0);
        for (int i = 0; i < 256; i++) { o.write(i); o.write(i); o.write(i); }
        o.write(new byte[]{0x21, (byte) 0xFF, 0x0B});
        o.write("NETSCAPE2.0".getBytes("US-ASCII"));
        o.write(new byte[]{3, 1, 0, 0, 0}); // loop forever
        int delay = Math.max(2, Math.round(100f / fps));
        int[] px = new int[w * h];
        byte[] idx = new byte[w * h];
        for (int f = 0; f < frames.count(); f++) {
            Bitmap b = frames.get(f);
            Bitmap s = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
            s.getPixels(px, 0, w, 0, 0, w, h);
            if (s != b) s.recycle();
            b.recycle();
            for (int i = 0; i < px.length; i++) idx[i] = (byte) grey(px[i]);
            o.write(new byte[]{0x21, (byte) 0xF9, 4, 0});
            le16(o, delay);
            o.write(0); o.write(0);
            o.write(0x2C);
            le16(o, 0); le16(o, 0); le16(o, w); le16(o, h);
            o.write(0);
            lzw(o, idx);
        }
        o.write(0x3B);
        o.flush();
    }

    static int grey(int p) {
        int a = p >>> 24, g = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
        return (g * a + 255 * (255 - a)) / 255; // over white
    }

    private static void le16(OutputStream o, int v) throws IOException { o.write(v & 255); o.write((v >> 8) & 255); }

    /** GIF LZW, 8-bit initial code size, variable 9..12 bit codes. */
    static void lzw(OutputStream o, byte[] data) throws IOException {
        o.write(8);
        final int CLEAR = 256, EOI = 257;
        int[] dict = new int[4096 * 256];
        int[] used = new int[4096];
        int nUsed = 0;
        Bits bits = new Bits(o);
        int size = 9, max = 511, next = 258;
        bits.put(CLEAR, size);
        if (data.length == 0) { bits.put(EOI, size); bits.end(); return; }
        int prefix = data[0] & 255;
        for (int i = 1; i < data.length; i++) {
            int k = data[i] & 255, key = (prefix << 8) | k;
            int c = dict[key];
            if (c != 0) { prefix = c - 1; continue; }
            bits.put(prefix, size);
            if (next > max && size < 12) { size++; max = size == 12 ? 4096 : (1 << size) - 1; }
            if (next < 4096) { dict[key] = next + 1; used[nUsed++] = key; next++; }
            else {
                bits.put(CLEAR, size);
                for (int u = 0; u < nUsed; u++) dict[used[u]] = 0;
                nUsed = 0; next = 258; size = 9; max = 511;
            }
            prefix = k;
        }
        bits.put(prefix, size);
        if (next > max && size < 12) size++;
        bits.put(EOI, size);
        bits.end();
    }

    /** Packs codes LSB-first into 255-byte sub-blocks. */
    private static final class Bits {
        final OutputStream o;
        final byte[] block = new byte[255];
        int n, acc, nb;
        Bits(OutputStream o) { this.o = o; }
        void put(int code, int size) throws IOException {
            acc |= code << nb;
            nb += size;
            while (nb >= 8) { byteOut(acc & 255); acc >>>= 8; nb -= 8; }
        }
        void byteOut(int b) throws IOException {
            block[n++] = (byte) b;
            if (n == 255) { o.write(255); o.write(block, 0, 255); n = 0; }
        }
        void end() throws IOException {
            if (nb > 0) byteOut(acc & 255);
            if (n > 0) { o.write(n); o.write(block, 0, n); }
            o.write(0);
        }
    }
}
