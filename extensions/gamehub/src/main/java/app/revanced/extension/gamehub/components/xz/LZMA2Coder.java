// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

abstract class LZMA2Coder implements FilterCoder {
    public static final long FILTER_ID = 0x21;

    @Override
    public boolean changesSize() {
        return true;
    }

    @Override
    public boolean nonLastOK() {
        return false;
    }

    @Override
    public boolean lastOK() {
        return true;
    }
}
