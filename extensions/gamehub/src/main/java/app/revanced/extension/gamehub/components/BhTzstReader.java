package app.revanced.extension.gamehub.components;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Best-effort look inside a component before it is injected: the entry
 * names (for {@link BhComponentType#detectFromEntries}) plus the small
 * descriptors ({@code meta.json} / {@code profile.json}) for version and
 * display-name hints. Works on a {@code .tzst} (tar + zstd, the GameHub
 * component format), on any other tar container a {@code .wcp} comes in
 * (container detection and the zip side live in {@link BhArchiveReader}),
 * and on an already-extracted folder. The same tar walker also EXTRACTS
 * (see {@link Sink}) so the listing and the extraction can never disagree
 * on what an entry is.
 *
 * <p>The host ships zstd-jni ({@code com.github.luben.zstd}) but no
 * commons-compress, so the tar side is a minimal ustar/GNU/pax walker here,
 * and the zstd side borrows the host's stream class by reflection. R8
 * narrowed {@code ZstdInputStreamNoFinalizer.<init>} to take the host's
 * commons-io {@code BoundedInputStream} (letter class {@value #BOUNDED_CLS}),
 * so we build one the way the host's own extractor does:
 * {@code ql3.p()} (builder) → {@code .Y(InputStream)} → {@code new ql3(builder)}.
 * Every letter is 6.3.1-specific — re-derive on a base bump (grep
 * {@code new-instance .*, Lql3;} next to the zstd ctor). When any of it is
 * missing the sniff degrades to "no entries" and the caller falls back to the
 * file name; injection itself never depends on this class (the archive is
 * copied verbatim and the plugin extracts it).
 */
final class BhTzstReader {

    private static final String TAG = "BhTzstReader";

    // ── 6.3.1 host letter map ──────────────────────────────────────────────
    private static final String ZSTD_STREAM_CLS = "com.github.luben.zstd.ZstdInputStreamNoFinalizer";
    private static final String BOUNDED_CLS     = "ql3";   // commons-io BoundedInputStream
    private static final String BUILDER_FACTORY = "p";     // static ql3.p() → pl3 (Builder)
    private static final String BUILDER_SET_IN  = "Y";     // v8.Y(InputStream) = setInputStream
    private static final String BUILDER_MAXCOUNT_FIELD = "g"; // pl3.g:J  = maxCount (-1 = EOF)
    private static final String BUILDER_PROPAGATE_FIELD = "i"; // pl3.i:Z = propagateClose

    /** zstd frame magic, little-endian 0xFD2FB528. */
    private static final byte[] ZSTD_MAGIC = { (byte) 0x28, (byte) 0xB5, (byte) 0x2F, (byte) 0xFD };

    static final int MAX_ENTRIES = 20000;
    private static final int MAX_DESCRIPTOR_BYTES = 256 * 1024;

    private BhTzstReader() {}

    /**
     * What a sniff found. {@code entries} is empty when the archive could not
     * be read. Entry names are normalised ('/'-separated, no leading "./",
     * directories end in '/'); anything that failed {@link BhArchiveReader#safePath}
     * or is a link is NOT in {@code entries} — it is in {@code problems}.
     */
    static final class Sniff {
        final List<String> entries = new ArrayList<>();
        String metaJson;
        String profileJson;
        /** Sum of entry sizes (archives) or file sizes (folders). */
        long totalBytes;
        /** Non-null when every entry sits under one top-level directory (nested layout warning). */
        String singleTopDir;
        /** Container the bytes were recognised as; UNKNOWN when no magic matched. */
        BhArchiveReader.Container container = BhArchiveReader.Container.UNKNOWN;
        /** Set when the archive is not a zstd frame at all (the .tzst park route needs one). */
        boolean notZstd;
        /** Set when the decompressor could not be built (reflection failed) or the archive was unreadable. */
        boolean unreadable;
        /** Blocking findings: traversal / absolute paths / links / oversize. Empty = safe to extract. */
        final List<String> problems = new ArrayList<>();
    }

    /** Receives each regular file during an extraction walk; {@code body} is bounded to {@code size}. */
    interface Sink {
        void file(String path, long size, InputStream body) throws IOException;
    }

    /** Thrown by the walkers when an entry must not be extracted (path escape, link, oversize). */
    static final class UnsafeEntryException extends IOException {
        UnsafeEntryException(String msg) { super(msg); }
    }

    // ── Archive ───────────────────────────────────────────────────────────

    static boolean hasZstdMagic(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            int n = in.read(b);
            if (n < 4) return false;
            for (int i = 0; i < 4; i++) if (b[i] != ZSTD_MAGIC[i]) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Sniff by container magic (zstd / xz / gzip / plain tar / zip), not by
     * extension. {@code notZstd} still says whether the .tzst park route can
     * take the file verbatim.
     */
    static Sniff sniffArchive(File archive) {
        return BhArchiveReader.sniff(archive);
    }

    /**
     * The host's zstd decompressing stream over {@code raw}, or null when the
     * reflective construction fails (logged once per call site).
     */
    static InputStream openZstd(InputStream raw) {
        try {
            Class<?> boundedCls = Class.forName(BOUNDED_CLS);
            Object builder = boundedCls.getMethod(BUILDER_FACTORY).invoke(null);
            if (builder == null) return null;
            Method setIn = findMethod(builder.getClass(), BUILDER_SET_IN, InputStream.class);
            if (setIn == null) {
                Log.w(TAG, "builder setInputStream not found on " + builder.getClass());
                return null;
            }
            setIn.setAccessible(true);
            setIn.invoke(builder, raw);
            // maxCount = -1 (read to EOF), propagateClose = true — the host sets
            // both explicitly; they are also the builder defaults, so a miss is
            // logged but not fatal.
            try {
                Field max = builder.getClass().getField(BUILDER_MAXCOUNT_FIELD);
                max.setLong(builder, -1L);
                Field prop = builder.getClass().getField(BUILDER_PROPAGATE_FIELD);
                prop.setBoolean(builder, true);
            } catch (Throwable t) {
                Log.w(TAG, "builder field tweak skipped: " + t);
            }
            Constructor<?> boundedCtor = boundedCls.getConstructor(builder.getClass());
            Object bounded = boundedCtor.newInstance(builder);

            Class<?> zcls = Class.forName(ZSTD_STREAM_CLS);
            Constructor<?> zctor = zcls.getConstructor(boundedCls);
            Object z = zctor.newInstance(bounded);
            return (InputStream) z;
        } catch (Throwable t) {
            Log.w(TAG, "host zstd stream unavailable (letters moved?): " + t);
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    // ── tar walker (ustar + GNU longname + pax path) ──────────────────────

    /**
     * One pass over a tar stream. With {@code sink == null} it only lists
     * (and reads the two descriptors); with a sink every regular file is
     * handed over, body bounded to its header size. Unsafe entries (path
     * escape, symlink / hardlink, oversize) are recorded in
     * {@code s.problems} when listing and thrown as
     * {@link UnsafeEntryException} when extracting — the extraction never
     * trusts the earlier listing.
     */
    static void walkTar(InputStream in, Sniff s, Sink sink) throws IOException {
        byte[] hdr = new byte[512];
        String pendingLongName = null;
        int count = 0;
        while (true) {
            if (!readFully(in, hdr, 512)) break;
            if (isZeroBlock(hdr)) break;
            if (++count > MAX_ENTRIES) {
                unsafe(s, sink, "more than " + MAX_ENTRIES + " entries");
                break;
            }

            String name = cstr(hdr, 0, 100);
            long size = parseSize(hdr, 124, 12);
            byte type = hdr[156];
            boolean ustar = cstr(hdr, 257, 6).startsWith("ustar");
            if (ustar) {
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }
            long padded = (size + 511) & ~511L;

            if (type == 'L') {                       // GNU long name: body is the next entry's name
                byte[] body = readBody(in, size, MAX_DESCRIPTOR_BYTES);
                pendingLongName = body == null ? null : cstr(body, 0, body.length);
                skip(in, padded - Math.min(size, body == null ? 0 : body.length));
                continue;
            }
            if (type == 'x' || type == 'g') {        // pax headers: honour "path"
                byte[] body = readBody(in, size, MAX_DESCRIPTOR_BYTES);
                if (body != null && type == 'x') {
                    String p = paxValue(body, "path");
                    if (p != null) pendingLongName = p;
                }
                skip(in, padded - Math.min(size, body == null ? 0 : body.length));
                continue;
            }
            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }
            if (type == '1' || type == '2') {        // hard / symbolic link: never materialised
                unsafe(s, sink, "link entry: " + name);
                skip(in, padded);
                continue;
            }
            boolean dir = type == '5' || name.endsWith("/");
            if (!dir && type != 0 && type != '0' && type != '7') {   // char/block/fifo/GNU extras
                skip(in, padded);
                continue;
            }
            String safe = BhArchiveReader.safePath(name);
            if (safe == null) {
                if (!name.isEmpty() && !name.equals(".") && !name.equals("./")) {
                    unsafe(s, sink, "unsafe path: " + name);
                }
                skip(in, padded);
                continue;
            }
            if (dir) {
                s.entries.add(safe + "/");
                skip(in, padded);
                continue;
            }
            if (size < 0 || size > BhArchiveReader.MAX_TOTAL_BYTES) {
                unsafe(s, sink, "entry too large: " + name);
                skip(in, padded);
                continue;
            }
            s.entries.add(safe);
            s.totalBytes += size;
            if (s.totalBytes > BhArchiveReader.MAX_TOTAL_BYTES) {
                unsafe(s, sink, "archive larger than " + BhComponentUi.humanSize(BhArchiveReader.MAX_TOTAL_BYTES));
                break;
            }
            String base = safe.substring(safe.lastIndexOf('/') + 1);
            boolean descriptor = size <= MAX_DESCRIPTOR_BYTES
                    && (("meta.json".equals(base) && s.metaJson == null)
                        || ("profile.json".equals(base) && s.profileJson == null));
            if (sink != null) {
                BoundedIn body = new BoundedIn(in, size);
                sink.file(safe, size, body);
                skip(in, padded - (size - body.remaining()));
            } else if (descriptor) {
                byte[] body = readBody(in, size, MAX_DESCRIPTOR_BYTES);
                if (body != null) {
                    String text = new String(body, StandardCharsets.UTF_8);
                    if ("meta.json".equals(base)) s.metaJson = text; else s.profileJson = text;
                }
                skip(in, padded - Math.min(size, body == null ? 0 : body.length));
            } else {
                skip(in, padded);
            }
        }
    }

    private static void unsafe(Sniff s, Sink sink, String why) throws IOException {
        if (sink != null) throw new UnsafeEntryException(why);
        if (s.problems.size() < 20) s.problems.add(why);
    }

    /** Reads at most {@code left} bytes of the wrapped stream; never closes it. */
    static final class BoundedIn extends InputStream {
        private final InputStream in;
        private long left;

        BoundedIn(InputStream in, long size) { this.in = in; this.left = size; }

        long remaining() { return left; }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long k = in.skip(Math.min(n, left));
            if (k > 0) left -= k;
            return k;
        }

        @Override public void close() { /* owned by the walker */ }
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return false;             // EOF mid-block = truncated (or clean end at off == 0)
            off += n;
        }
        return true;
    }

    private static byte[] readBody(InputStream in, long size, int cap) throws IOException {
        if (size < 0 || size > cap) return null;
        byte[] b = new byte[(int) size];
        return readFully(in, b, b.length) ? b : null;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] scratch = null;
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) {
                if (scratch == null) scratch = new byte[1 << 16];
                int r = in.read(scratch, 0, (int) Math.min(scratch.length, n));
                if (r < 0) throw new IOException("truncated tar");
                k = r;
            }
            n -= k;
        }
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(b.length, off + len);
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** Octal ASCII, or GNU base-256 when the high bit of the first byte is set. */
    private static long parseSize(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = 0;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c == 0 || c == ' ') { if (v != 0) break; else continue; }
            if (c < '0' || c > '7') break;
            v = (v << 3) | (c - '0');
        }
        return v;
    }

    /** pax record format: "<len> <key>=<value>\n" repeated. */
    private static String paxValue(byte[] body, String key) {
        String text = new String(body, StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < text.length()) {
            int sp = text.indexOf(' ', pos);
            if (sp < 0) break;
            int len;
            try { len = Integer.parseInt(text.substring(pos, sp)); } catch (NumberFormatException e) { break; }
            if (len <= 0 || pos + len > text.length()) break;
            String rec = text.substring(sp + 1, pos + len);
            int eq = rec.indexOf('=');
            if (eq > 0 && rec.substring(0, eq).equals(key)) {
                String v = rec.substring(eq + 1);
                return v.endsWith("\n") ? v.substring(0, v.length() - 1) : v;
            }
            pos += len;
        }
        return null;
    }

    // ── Folder ────────────────────────────────────────────────────────────

    static Sniff sniffFolder(File dir) {
        Sniff s = new Sniff();
        try {
            walkDir(dir, "", s, 0);
        } catch (Throwable t) {
            Log.w(TAG, "folder sniff failed for " + dir, t);
        }
        s.singleTopDir = singleTopDir(s.entries);
        return s;
    }

    private static void walkDir(File dir, String rel, Sniff s, int depth) throws IOException {
        if (depth > 12 || s.entries.size() >= MAX_ENTRIES) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String r = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            if (k.isDirectory()) {
                s.entries.add(r + "/");
                walkDir(k, r, s, depth + 1);
            } else {
                s.entries.add(r);
                s.totalBytes += k.length();
                if (k.length() <= MAX_DESCRIPTOR_BYTES) {
                    if ("meta.json".equals(k.getName()) && s.metaJson == null) s.metaJson = readText(k);
                    else if ("profile.json".equals(k.getName()) && s.profileJson == null) s.profileJson = readText(k);
                }
            }
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            return readFully(in, b, b.length) ? new String(b, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────

    /** The one directory everything sits under, or null (flat / mixed / empty). */
    static String singleTopDir(List<String> entries) {
        if (entries == null || entries.isEmpty()) return null;
        Set<String> tops = new HashSet<>();
        for (String e : entries) {
            int slash = e.indexOf('/');
            if (slash < 0) return null;             // a file at the root → not nested
            tops.add(e.substring(0, slash));
            if (tops.size() > 1) return null;
        }
        return tops.isEmpty() ? null : tops.iterator().next();
    }
}
