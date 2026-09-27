// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

/**
 * Generic {@link java.io.IOException IOException} specific to this package.
 * The other IOExceptions in this package extend
 * from {@code XZIOException}.
 */
public class XZIOException extends java.io.IOException {
    private static final long serialVersionUID = 3L;

    public XZIOException() {
        super();
    }

    public XZIOException(String s) {
        super(s);
    }
}
