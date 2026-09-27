// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

import java.io.InputStream;

interface FilterDecoder extends FilterCoder {
    int getMemoryUsage();
    InputStream getInputStream(InputStream in, ArrayCache arrayCache);
}
