package app.revanced.extension.gamehub.components;

import android.util.Log;

import app.revanced.extension.gamehub.components.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The container layer under the injector: says what a picked file IS (by
 * magic bytes, never by extension — a {@code .wcp} is a tar compressed with
 * zstd or xz, occasionally gzip or nothing; adrenotools driver packages and
 * GitHub release archives are zips), lists it into a
 * {@link BhTzstReader.Sniff}, and extracts it through a
 * {@link BhTzstReader.Sink}.
 *
 * <p>Decoders: zstd = the host's zstd-jni via {@link BhTzstReader#openZstd}
 * (reflective, may be unavailable after a base bump); xz = the vendored
 * {@code components.xz} decoder (the host has none); gzip = the JDK. The tar
 * walk itself is {@link BhTzstReader#walkTar}; the zip walk is here over
 * {@link ZipFile}, with a side read of the central directory for the unix
 * mode bits so symlink entries can be refused (the JDK API hides them).
 *
 * <p>Safety rules, applied identically when listing and when extracting:
 * <ul>
 *   <li>paths are normalised by {@link #safePath}: '\' → '/', "./" prefixes
 *       dropped, "." segments dropped; any ".." segment, an absolute path
 *       (leading '/', or a drive letter), or a NUL byte rejects the entry;</li>
 *   <li>symlinks and hardlinks are refused (tar types 1/2, zip unix mode
 *       S_IFLNK); device/fifo entries are skipped silently;</li>
 *   <li>at most {@value BhTzstReader#MAX_ENTRIES} entries and
 *       {@link #MAX_TOTAL_BYTES} uncompressed bytes (header-declared when
 *       listing, actually-written when extracting — a lying header cannot
 *       get past the sink).</li>
 * </ul>
 */
final class BhArchiveReader {

    private static final String TAG = "BhArchiveReader";

    /** Uncompressed cap. Components are tens of MB; a Wine prefix would be a mistake here anyway. */
    static final long MAX_TOTAL_BYTES = 3L * 1024 * 1024 * 1024;

    private static final int MAX_DESCRIPTOR_BYTES = 256 * 1024;

    enum Container {
        /** tar + zstd — GameHub's own .tzst and most Winlator .wcp files. */
        ZSTD,
        /** tar + xz — the other common .wcp compression. */
        XZ,
        /** tar + gzip. */
        GZIP,
        /** plain ustar. */
        TAR,
        /** zip — adrenotools driver packages, GitHub release archives. */
        ZIP,
        UNKNOWN
    }

    private BhArchiveReader() {}

    // ── Detection ─────────────────────────────────────────────────────────

    static Container detect(File f) {
        byte[] b = new byte[512];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = in.read(b);
        } catch (IOException e) {
            return Container.UNKNOWN;
        }
        if (n >= 4 && (b[0] & 0xFF) == 0x28 && (b[1] & 0xFF) == 0xB5
                && (b[2] & 0xFF) == 0x2F && (b[3] & 0xFF) == 0xFD) return Container.ZSTD;
        if (n >= 6 && (b[0] & 0xFF) == 0xFD && b[1] == '7' && b[2] == 'z'
                && b[3] == 'X' && b[4] == 'Z' && b[5] == 0) return Container.XZ;
        if (n >= 2 && (b[0] & 0xFF) == 0x1F && (b[1] & 0xFF) == 0x8B) return Container.GZIP;
        if (n >= 4 && b[0] == 'P' && b[1] == 'K'
                && ((b[2] == 3 && b[3] == 4) || (b[2] == 5 && b[3] == 6))) return Container.ZIP;
        if (n >= 263 && b[257] == 'u' && b[258] == 's' && b[259] == 't'
                && b[260] == 'a' && b[261] == 'r') return Container.TAR;
        return Container.UNKNOWN;
    }

    /** Short user-facing container name ("tar + zstd", "zip", ...). */
    static String label(Container c) {
        switch (c) {
            case ZSTD: return "tar + zstd";
            case XZ:   return "tar + xz";
            case GZIP: return "tar + gzip";
            case TAR:  return "tar";
            case ZIP:  return "zip";
            default:   return "unknown";
        }
    }

    static boolean isTar(Container c) {
        return c == Container.ZSTD || c == Container.XZ || c == Container.GZIP || c == Container.TAR;
    }

    // ── Paths ─────────────────────────────────────────────────────────────

    /**
     * Normalised relative path, or null when the entry must not be written
     * anywhere (see the class comment). A trailing '/' is dropped; the
     * caller knows from the entry type whether it was a directory.
     */
    static String safePath(String raw) {
        if (raw == null || raw.indexOf('\0') >= 0) return null;
        String p = raw.replace('\\', '/');
        if (p.startsWith("/")) return null;
        if (p.length() >= 2 && p.charAt(1) == ':' && Character.isLetter(p.charAt(0))) return null;
        StringBuilder out = new StringBuilder(p.length());
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            if (out.length() > 0) out.append('/');
            out.append(seg);
        }
        return out.length() == 0 ? null : out.toString();
    }

    // ── Listing ───────────────────────────────────────────────────────────

    static BhTzstReader.Sniff sniff(File archive) {
        BhTzstReader.Sniff s = new BhTzstReader.Sniff();
        s.container = detect(archive);
        s.notZstd = s.container != Container.ZSTD;
        if (s.container == Container.UNKNOWN) {
            s.unreadable = true;
            return s;
        }
        if (s.container == Container.ZIP) {
            sniffZip(archive, s);
        } else {
            InputStream tin = null;
            try {
                tin = openTar(archive, s.container);
                if (tin == null) {
                    s.unreadable = true;
                    return s;
                }
                BhTzstReader.walkTar(tin, s, null);
            } catch (Throwable t) {
                Log.w(TAG, "sniff failed for " + archive, t);
                s.unreadable = s.entries.isEmpty();
            } finally {
                if (tin != null) try { tin.close(); } catch (Throwable ignored) { }
            }
        }
        s.singleTopDir = BhTzstReader.singleTopDir(s.entries);
        return s;
    }

    /**
     * Decompressing stream over a tar container, or null when the decoder
     * for it is missing (only zstd can be — it is borrowed from the host).
     */
    static InputStream openTar(File archive, Container c) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(archive), 1 << 16);
        try {
            switch (c) {
                case ZSTD: {
                    InputStream z = BhTzstReader.openZstd(raw);
                    if (z == null) raw.close();
                    return z == null ? null : new BufferedInputStream(z, 1 << 16);
                }
                case XZ:   return new BufferedInputStream(new XZInputStream(raw), 1 << 16);
                case GZIP: return new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16);
                case TAR:  return raw;
                default:
                    raw.close();
                    return null;
            }
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (Throwable ignored) { }
            throw e;
        }
    }

    private static void sniffZip(File archive, BhTzstReader.Sniff s) {
        Set<String> links = zipSymlinks(archive);
        try (ZipFile zf = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (++count > BhTzstReader.MAX_ENTRIES) {
                    s.problems.add("more than " + BhTzstReader.MAX_ENTRIES + " entries");
                    break;
                }
                String name = ze.getName();
                if (links.contains(name)) {
                    if (s.problems.size() < 20) s.problems.add("link entry: " + name);
                    continue;
                }
                String safe = safePath(name);
                if (safe == null) {
                    if (s.problems.size() < 20) s.problems.add("unsafe path: " + name);
                    continue;
                }
                if (ze.isDirectory()) {
                    s.entries.add(safe + "/");
                    continue;
                }
                long size = Math.max(0, ze.getSize());
                s.entries.add(safe);
                s.totalBytes += size;
                if (s.totalBytes > MAX_TOTAL_BYTES) {
                    s.problems.add("archive larger than " + BhComponentUi.humanSize(MAX_TOTAL_BYTES));
                    break;
                }
                String base = safe.substring(safe.lastIndexOf('/') + 1);
                if (size <= MAX_DESCRIPTOR_BYTES
                        && (("meta.json".equals(base) && s.metaJson == null)
                            || ("profile.json".equals(base) && s.profileJson == null))) {
                    String text = readSmall(zf, ze);
                    if ("meta.json".equals(base)) s.metaJson = text; else s.profileJson = text;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "zip sniff failed for " + archive, t);
            s.unreadable = s.entries.isEmpty();
        }
    }

    private static String readSmall(ZipFile zf, ZipEntry ze) {
        try (InputStream in = zf.getInputStream(ze)) {
            byte[] buf = new byte[MAX_DESCRIPTOR_BYTES];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Names of central-directory entries whose unix mode says S_IFLNK.
     * {@link ZipEntry} exposes no mode bits, so this walks the directory by
     * hand: EOCD → cd offset → each 0x02014b50 record's external attrs
     * (high 16 bits = st_mode when "version made by" says Unix). Empty on a
     * zip64 archive or any parse problem (logged) — the entry then goes
     * through as a regular file, which is the safe failure: a link's
     * "body" is just its target text.
     */
    static Set<String> zipSymlinks(File archive) {
        Set<String> out = new HashSet<>();
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            long len = raf.length();
            long scanFrom = Math.max(0, len - 22 - 65535);
            byte[] tail = new byte[(int) (len - scanFrom)];
            raf.seek(scanFrom);
            raf.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return out;
            int entries = u16(tail, eocd + 10);
            long cdOff = u32(tail, eocd + 16);
            if (entries == 0xFFFF || cdOff == 0xFFFFFFFFL) return out;   // zip64: not handled
            raf.seek(cdOff);
            byte[] h = new byte[46];
            for (int i = 0; i < entries; i++) {
                raf.readFully(h);
                if (u32(h, 0) != 0x02014b50L) break;
                int madeByOs = (h[5] & 0xFF);
                int nameLen = u16(h, 28), extraLen = u16(h, 30), commentLen = u16(h, 32);
                long ext = u32(h, 38);
                byte[] nameB = new byte[nameLen];
                raf.readFully(nameB);
                raf.skipBytes(extraLen + commentLen);
                if (madeByOs == 3 || madeByOs == 19) {          // Unix / OS X
                    int mode = (int) (ext >>> 16);
                    if ((mode & 0xF000) == 0xA000) out.add(new String(nameB, StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "central directory scan skipped: " + t);
        }
        return out;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    // ── Extraction ────────────────────────────────────────────────────────

    /**
     * Feeds every regular file to {@code sink} with the safety rules
     * re-applied on the way (an unsafe entry throws
     * {@link BhTzstReader.UnsafeEntryException}). Directory entries are not
     * reported — the sink creates parents as it writes.
     */
    static void extract(File archive, Container c, BhTzstReader.Sink sink) throws IOException {
        if (c == Container.ZIP) {
            extractZip(archive, sink);
            return;
        }
        if (!isTar(c)) throw new IOException("unknown container");
        InputStream tin = openTar(archive, c);
        if (tin == null) throw new IOException("no " + label(c) + " decoder in this build");
        try {
            BhTzstReader.Sniff scratch = new BhTzstReader.Sniff();
            BhTzstReader.walkTar(tin, scratch, sink);
        } finally {
            try { tin.close(); } catch (Throwable ignored) { }
        }
    }

    private static void extractZip(File archive, BhTzstReader.Sink sink) throws IOException {
        Set<String> links = zipSymlinks(archive);
        try (ZipFile zf = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            int count = 0;
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (++count > BhTzstReader.MAX_ENTRIES) {
                    throw new BhTzstReader.UnsafeEntryException("more than " + BhTzstReader.MAX_ENTRIES + " entries");
                }
                String name = ze.getName();
                if (links.contains(name)) throw new BhTzstReader.UnsafeEntryException("link entry: " + name);
                String safe = safePath(name);
                if (safe == null) throw new BhTzstReader.UnsafeEntryException("unsafe path: " + name);
                if (ze.isDirectory()) continue;
                try (InputStream in = zf.getInputStream(ze)) {
                    sink.file(safe, Math.max(0, ze.getSize()), in);
                }
            }
        }
    }

    /** Files only (no trailing '/'), in archive order — what the layout planner consumes. */
    static List<String> files(List<String> entries) {
        List<String> out = new ArrayList<>(entries.size());
        for (String e : entries) if (!e.endsWith("/")) out.add(e);
        return out;
    }
}
