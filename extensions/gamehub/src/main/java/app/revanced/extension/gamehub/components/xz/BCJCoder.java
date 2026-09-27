// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

abstract class BCJCoder implements FilterCoder {
    public static final long X86_FILTER_ID = 0x04;
    public static final long POWERPC_FILTER_ID = 0x05;
    public static final long IA64_FILTER_ID = 0x06;
    public static final long ARM_FILTER_ID = 0x07;
    public static final long ARMTHUMB_FILTER_ID = 0x08;
    public static final long SPARC_FILTER_ID = 0x09;
    public static final long ARM64_FILTER_ID = 0X0A;
    public static final long RISCV_FILTER_ID = 0X0B;

    public static boolean isBCJFilterID(long filterID) {
        return filterID >= X86_FILTER_ID && filterID <= RISCV_FILTER_ID;
    }

    @Override
    public boolean changesSize() {
        return false;
    }

    @Override
    public boolean nonLastOK() {
        return true;
    }

    @Override
    public boolean lastOK() {
        return false;
    }
}
