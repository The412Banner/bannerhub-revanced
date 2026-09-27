// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.check;

public class None extends Check {
    public None() {
        size = 0;
        name = "None";
    }

    @Override
    public void update(byte[] buf, int off, int len) {}

    @Override
    public byte[] finish() {
        byte[] empty = new byte[0];
        return empty;
    }
}
