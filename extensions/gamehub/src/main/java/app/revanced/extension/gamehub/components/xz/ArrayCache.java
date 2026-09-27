// SPDX-License-Identifier: 0BSD
// SPDX-FileCopyrightText: The XZ for Java authors and contributors
// SPDX-FileContributor: Lasse Collin <lasse.collin@tukaani.org>

package app.revanced.extension.gamehub.components.xz;

/**
 * Caches large arrays for reuse (base class and a dummy cache implementation).
 * <p>
 * When compressing or decompressing many (very) small files in a row, the
 * time spent in construction of new compressor or decompressor objects
 * can be longer than the time spent in actual compression or decompression.
 * A large part of this initialization overhead comes from allocation and
 * garbage collection of large arrays.
 * <p>
 * The {@code ArrayCache} API provides a way to cache large array allocations
 * for reuse. It can give a major performance improvement when compressing or
 * decompressing many tiny files. If you are only (de)compressing one or two
 * files or the files a very big, array caching won't improve anything,
 * although it won't make anything slower either.
 * <p>
 * <b>Important: The users of ArrayCache don't return the allocated arrays
 * back to the cache in all situations.</b>
 * This a reason why it's called a cache instead of a pool.
 * If it is important to be able to return every array back to a cache,
 * {@link ResettableArrayCache} can be useful.
 * <p>
 * In compressors (OutputStreams) the arrays are returned to the cache
 * when a call to {@code finish()} or {@code close()} returns
 * successfully (no exceptions are thrown).
 * <p>
 * In decompressors (InputStreams) the arrays are returned to the cache when
 * the decompression is successfully finished ({@code read} returns {@code -1})
 * or {@code close()} or {@code close(boolean)} is called. This is true even
 * if closing throws an exception.
 * <p>
 * Raw decompressors don't support {@code close(boolean)}. With raw
 * decompressors, if one wants to put the arrays back to the cache without
 * closing the underlying {@code InputStream}, one can wrap the
 * {@code InputStream} into {@link CloseIgnoringInputStream} when creating
 * the decompressor instance. Then one can use {@code close()}.
 * <p>
 * Different cache implementations can be extended from this base class.
 * All cache implementations must be thread safe.
 * <p>
 * This class also works as a dummy cache that simply calls {@code new}
 * to allocate new arrays and doesn't try to cache anything. A statically
 * allocated dummy cache is available via {@link #getDummyCache()}.
 * <p>
 * If no {@code ArrayCache} is specified when constructing a compressor or
 * decompressor, the default {@code ArrayCache} implementation is used.
 * See {@link #getDefaultCache()} and {@link #setDefaultCache(ArrayCache)}.
 * Since 1.10, the default can also be set using the system property
 * {@code app.revanced.extension.gamehub.components.xz.ArrayCache}. Supported values are {@code Dummy}
 * (the default) and {@code Basic} (to use {@code BasicArrayCache}).
 * <p>
 * This is a class instead of an interface because it's possible that in the
 * future we may want to cache other array types too. New methods can be
 * added to this class without breaking existing cache implementations.
 *
 * @since 1.7
 *
 * @see BasicArrayCache
 */
public class ArrayCache {
    /**
     * Global dummy cache instance that is returned by {@code getDummyCache()}.
     */
    private static final ArrayCache dummyCache = new ArrayCache();

    /**
     * Global default {@code ArrayCache} that is used when no other cache has
     * been specified.
     */
    private static volatile ArrayCache defaultCache;

    static {
        String cacheType = System.getProperty("app.revanced.extension.gamehub.components.xz.ArrayCache");
        if (cacheType == null)
            cacheType = "Dummy";

        switch (cacheType) {
            case "Dummy":
                defaultCache = dummyCache;
                break;

            case "Basic":
                defaultCache = BasicArrayCache.getInstance();
                break;

            default:
                throw new Error("Unsupported value '" + cacheType +
                                "' in the system property " +
                                "app.revanced.extension.gamehub.components.xz.ArrayCache. " +
                                "Supported values: Dummy, Basic");
        }
    }

    /**
     * Returns a statically-allocated {@code ArrayCache} instance.
     * It can be shared by all code that needs a dummy cache.
     */
    public static ArrayCache getDummyCache() {
        return dummyCache;
    }

    /**
     * Gets the default {@code ArrayCache} instance.
     * This is a global cache that is used when the application
     * specifies nothing else. The default is a dummy cache
     * (see {@link #getDummyCache()}).
     */
    public static ArrayCache getDefaultCache() {
        // It's volatile so no need for synchronization.
        return defaultCache;
    }

    /**
     * Sets the default {@code ArrayCache} instance.
     * Use with care. Other libraries using this package probably shouldn't
     * call this function as libraries cannot know if there are other users
     * of the xz package in the same application.
     */
    public static void setDefaultCache(ArrayCache arrayCache) {
        if (arrayCache == null)
            throw new NullPointerException();

        // It's volatile so no need for synchronization.
        defaultCache = arrayCache;
    }

    /**
     * Creates a new {@code ArrayCache} that does no caching
     * (a dummy cache). If you need a dummy cache, you may want to call
     * {@link #getDummyCache()} instead.
     */
    public ArrayCache() {}

    /**
     * Allocates a new byte array.
     * <p>
     * This implementation simply returns {@code new byte[size]}.
     *
     * @param   size            the minimum size of the array to allocate;
     *                          an implementation may return an array that
     *                          is larger than the given {@code size}
     *
     * @param   fillWithZeros   if true, the caller expects that the first
     *                          {@code size} elements in the array are zero;
     *                          if false, the array contents can be anything,
     *                          which speeds things up when reusing a cached
     *                          array
     */
    public byte[] getByteArray(int size, boolean fillWithZeros) {
        return new byte[size];
    }

    /**
     * Puts the given byte array to the cache. The caller must no longer
     * use the array.
     * <p>
     * This implementation does nothing.
     */
    public void putArray(byte[] array) {}

    /**
     * Allocates a new int array.
     * <p>
     * This implementation simply returns {@code new int[size]}.
     *
     * @param   size            the minimum size of the array to allocate;
     *                          an implementation may return an array that
     *                          is larger than the given {@code size}
     *
     * @param   fillWithZeros   if true, the caller expects that the first
     *                          {@code size} elements in the array are zero;
     *                          if false, the array contents can be anything,
     *                          which speeds things up when reusing a cached
     *                          array
     */
    public int[] getIntArray(int size, boolean fillWithZeros) {
        return new int[size];
    }

    /**
     * Puts the given int array to the cache. The caller must no longer
     * use the array.
     * <p>
     * This implementation does nothing.
     */
    public void putArray(int[] array) {}
}
