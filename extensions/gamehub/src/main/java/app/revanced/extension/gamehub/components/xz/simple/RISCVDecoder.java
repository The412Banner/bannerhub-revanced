// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz.simple;

import app.revanced.extension.gamehub.components.xz.common.ByteArrayView;

// BCJ filter decoder for RISC-V instructions
//
// See the comments in RISCVEncoder.java. Most of them aren't duplicated here.
public final class RISCVDecoder implements SimpleFilter {
    private int pos;

    public RISCVDecoder(int startPos) {
        pos = startPos;
    }

    @Override
    public int code(byte[] buf, int off, int len) {
        int end = off + len - 8;
        int i;

        for (i = off; i <= end; i += 2) {
            int inst = buf[i] & 0xFF;

            if (inst == 0xEF) {
                // JAL
                final int b1 = buf[i + 1] & 0xFF;

                // Only filter rd=x1(ra) and rd=x5(t0).
                if ((b1 & 0x0D) != 0)
                    continue;

                final int b2 = buf[i + 2] & 0xFF;
                final int b3 = buf[i + 3] & 0xFF;
                final int pc = pos + i - off;

// |          b3             |          b2             |          b1         |
// | 31 30 29 28 27 26 25 24 | 23 22 21 20 19 18 17 16 | 15 14 13 12 x x x x |
// | 20 10  9  8  7  6  5  4 |  3  2  1 11 19 18 17 16 | 15 14 13 12 x x x x |
// |  7  6  5  4  3  2  1  0 |  7  6  5  4  3  2  1  0 |  7  6  5  4 x x x x |

                int addr = ((b1 & 0xF0) << 13) | (b2 << 9) | (b3 << 1);

                addr -= pc;

                buf[i + 1] = (byte)((b1 & 0x0F) | ((addr >>> 8) & 0xF0));

                buf[i + 2] = (byte)(((addr >>> 16) & 0x0F)
                                    | ((addr >>> 7) & 0x10)
                                    | ((addr << 4) & 0xE0));

                buf[i + 3] = (byte)(((addr >>> 4) & 0x7F)
                                    | ((addr >>> 13) & 0x80));

                i += 4 - 2;

            } else if ((inst & 0x7F) == 0x17) {
                // AUIPC
                int inst2;

                inst |= (buf[i + 1] & 0xFF) << 8;
                inst |= (buf[i + 2] & 0xFF) << 16;
                inst |= (buf[i + 3] & 0xFF) << 24;

                if ((inst & 0xE80) != 0) {
                    // AUIPC's rd doesn't equal x0 or x2.

                    // Check if it is a "fake" AUIPC+inst2 pair.
                    inst2 = ByteArrayView.getIntLE(buf, i + 4);

                    if ((((inst << 8) ^ inst2) & 0xF8003) != 3) {
                        i += 6 - 2;
                        continue;
                    }

                    // Decode (or more like re-encode) the "fake" pair.
                    // The "fake" format doesn't do sign-extension,
                    // address conversion, or use big endian.
                    int addr = (inst & 0xFFFFF000) + (inst2 >>> 20);

                    inst = 0x17 | (2 << 7) | (inst2 << 12);
                    inst2 = addr;
                } else {
                    // AUIPC's rd equals x0 or x2.

                    // Check if inst matches the special format
                    // used by the encoder.
                    final int inst2Rs1 = inst >>> 27;

                    if (((inst - 0x3100) & 0x3F80) >= (inst2Rs1 & 0x1D)) {
                        i += 4 - 2;
                        continue;
                    }

                    // Decode the "real" pair.
                    int addr = ByteArrayView.getIntBE(buf, i + 4);

                    addr -= pos + i - off;

                    // The second instruction:
                    //   - Get the lowest 20 bits from inst.
                    //   - Add the lowest 12 bits of the address
                    //     as the immediate field.
                    inst2 = (inst >>> 12) | (addr << 20);

                    // AUIPC:
                    //   - rd is the same as inst2_rs1.
                    //   - The sign extension of the lowest 12 bits
                    //     must be taken into account.
                    inst = 0x17 | (inst2Rs1 << 7)
                           | ((addr + 0x800) & 0xFFFFF000);
                }

                // Both decoder branches write in little endian order.
                ByteArrayView.setIntLE(buf, i, inst);
                ByteArrayView.setIntLE(buf, i + 4, inst2);

                i += 8 - 2;
            }
        }

        i -= off;
        pos += i;
        return i;
    }
}
