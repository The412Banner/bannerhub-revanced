// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.check;

import app.revanced.extension.gamehub.components.xz.XZ;
import app.revanced.extension.gamehub.components.xz.UnsupportedOptionsException;

public abstract class Check {
    int size;
    String name;

    public abstract void update(byte[] buf, int off, int len);
    public abstract byte[] finish();

    public void update(byte[] buf) {
        update(buf, 0, buf.length);
    }

    public int getSize() {
        return size;
    }

    public String getName() {
        return name;
    }

    public static Check getInstance(int checkType)
            throws UnsupportedOptionsException {
        switch (checkType) {
            case XZ.CHECK_NONE:
                return new None();

            case XZ.CHECK_CRC32:
                return new CRC32();

            case XZ.CHECK_CRC64:
                return new CRC64();

            case XZ.CHECK_SHA256:
                try {
                    return new SHA256();
                } catch (java.security.NoSuchAlgorithmException e) {}

                break;
        }

        throw new UnsupportedOptionsException(
                "Unsupported Check ID " + checkType);
    }
}
