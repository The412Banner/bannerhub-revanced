package app.revanced.extension.gamehub.components;

import android.content.ContentResolver;
import android.net.Uri;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a user-supplied component archive (.wcp/.zst tarball, .xz tarball, or .zip)
 * into the destination directory. Mirrors the BannerHub 3.5.0 smali implementation.
 *
 * Format detection by magic bytes:
 *   ZIP  → 50 4B __ __     → flat extraction (basename only — Turnip / adrenotools layout)
 *   zstd → 28 B5 2F FD     → tar inside, possibly FEXCore (flatten if profile.json says so)
 *   xz   → FD 37 7A 58     → tar inside, same handling
 *
 * Tar entries preserve directory structure (system32/syswow64 for DXVK/VKD3D/Box64) unless
 * profile.json declares the component is FEXCore, in which case all files are flattened
 * to the destination root.
 */
public final class WcpExtractor {

    private WcpExtractor() {}

    public static void extract(ContentResolver cr, Uri uri, File destDir) throws Exception {
        clearDir(destDir);
        destDir.mkdirs();

        try (InputStream raw = cr.openInputStream(uri);
             BufferedInputStream buf = new BufferedInputStream(raw)) {

            byte[] hdr = new byte[4];
            buf.mark(4);
            int read = 0;
            while (read < 4) {
                int n = buf.read(hdr, read, 4 - read);
                if (n < 0) break;
                read += n;
            }
            buf.reset();

            if (read >= 2 && hdr[0] == (byte) 0x50 && hdr[1] == (byte) 0x4B) {
                extractZip(buf, destDir);
                return;
            }

            if (read >= 4 && hdr[0] == (byte) 0x28 && hdr[1] == (byte) 0xB5
                    && hdr[2] == (byte) 0x2F && hdr[3] == (byte) 0xFD) {
                try (ZstdInputStreamNoFinalizer zstd = new ZstdInputStreamNoFinalizer(buf)) {
                    extractTar(zstd, destDir);
                }
                return;
            }

            if (read >= 4 && hdr[0] == (byte) 0xFD && hdr[1] == (byte) 0x37
                    && hdr[2] == (byte) 0x7A && hdr[3] == (byte) 0x58) {
                try (XZInputStream xz = new XZInputStream(buf, -1)) {
                    extractTar(xz, destDir);
                }
                return;
            }

            throw new Exception("Unknown format (not ZIP/zstd/XZ)");
        }
    }

    private static void clearDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) clearDir(f);
            f.delete();
        }
    }

    private static void extractZip(InputStream in, File destDir) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            byte[] buffer = new byte[0x2000];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String basename = new File(entry.getName()).getName();
                File outFile = new File(destDir, basename);
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void extractTar(InputStream in, File destDir) throws Exception {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            byte[] buffer = new byte[0x2000];
            boolean flattenToRoot = false;
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith("/")) continue;

                if (name.endsWith("profile.json")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = tar.read(buffer)) > 0) {
                        bos.write(buffer, 0, n);
                    }
                    if (bos.toString().contains("FEXCore")) {
                        flattenToRoot = true;
                    }
                    continue;
                }

                if (name.startsWith("./")) name = name.substring(2);

                File outFile;
                if (flattenToRoot) {
                    outFile = new File(destDir, new File(name).getName());
                } else {
                    outFile = new File(destDir, name);
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                }

                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = tar.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }
}
