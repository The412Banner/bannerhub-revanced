// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

/**
 * Thrown when the input data is not in the XZ format.
 */
public class XZFormatException extends XZIOException {
    private static final long serialVersionUID = 3L;

    /**
     * Creates a new exception with the default error detail message.
     */
    public XZFormatException() {
        super("Input is not in the XZ format");
    }
}
