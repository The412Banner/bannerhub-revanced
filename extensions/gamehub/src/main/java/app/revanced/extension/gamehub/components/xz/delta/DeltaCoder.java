// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>
// SPDX-FileContributor: Brett Okken <brett.okken.os@gmail.com>

package app.revanced.extension.gamehub.components.xz.delta;

abstract class DeltaCoder {
    static final int DISTANCE_MIN = 1;
    static final int DISTANCE_MAX = 256;

    final int distance;
    final byte[] history;

    DeltaCoder(int distance) {
        if (distance < DISTANCE_MIN || distance > DISTANCE_MAX)
            throw new IllegalArgumentException("Invalid distance: " +
                                               distance);

        this.distance = distance;
        this.history = new byte[distance];
    }
}
