// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.check;

public class SHA256 extends Check {
    private final java.security.MessageDigest sha256;

    public SHA256() throws java.security.NoSuchAlgorithmException {
        size = 32;
        name = "SHA-256";
        sha256 = java.security.MessageDigest.getInstance("SHA-256");
    }

    @Override
    public void update(byte[] buf, int off, int len) {
        sha256.update(buf, off, len);
    }

    @Override
    public byte[] finish() {
        byte[] buf = sha256.digest();
        sha256.reset();
        return buf;
    }
}
