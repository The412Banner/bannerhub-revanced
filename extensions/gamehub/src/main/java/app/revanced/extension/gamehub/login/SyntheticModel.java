package app.revanced.extension.gamehub.login;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Constructor;

/**
 * Instantiates one of the host's model classes without hard-coding its
 * constructor signature.
 *
 * WHY THIS EXISTS — a real device failure, 6.1.0 (2026-07-30).
 * `FakeAuthToken` and `FakeUserAccount` each pinned the model's EXACT constructor
 * signature. On 6.1.0 the user-profile model gained one parameter (27 -> 28: a
 * `boolean` before the trailing two `long`s), so `getDeclaredConstructor(...)`
 * threw NoSuchMethodException, `get()` returned null, the faked user StateFlow
 * held null, and the Library rendered "Log in to view your game library" — i.e.
 * the login bypass silently did nothing even though the patch applied 9/9. The
 * only visible symptom was `FakeAuthToken: construction failed` in logcat.
 *
 * Pinning a 28-parameter signature is a maintenance trap: it breaks whenever
 * upstream adds a single field, and it fails LATE (at runtime, on device) rather
 * than at patch time. So instead of naming the signature, we pick the class's
 * widest declared constructor and fill every parameter with a type-appropriate
 * zero value.
 *
 * The one value that must be real is the user id, which on BOTH models is the
 * FIRST String parameter (auth token `.a` = userId; user profile `.a` = userId —
 * verified in 6.1.0's UserToken/UserProfile and unchanged since 6.0.x). Every
 * other field is inert for our purposes: the library reader keys off the user id.
 *
 * Still version-dependent: the CLASS NAMES (R8 letters). Those stay in the
 * callers, where the per-version map already lives.
 */
final class SyntheticModel {

    private SyntheticModel() {}

    /**
     * @param className        R8-mangled model class name (no L…; wrapper).
     * @param userId           value to place in the first String parameter.
     * @return the instance, or {@code null} (already traced) on any failure.
     */
    static Object build(String className, String userId) {
        try {
            Class<?> cls = Class.forName(className);

            Constructor<?> widest = null;
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                if (widest == null || c.getParameterTypes().length > widest.getParameterTypes().length) {
                    widest = c;
                }
            }
            if (widest == null) {
                DebugTrace.write("SyntheticModel: " + className + " has no declared constructor");
                return null;
            }

            Class<?>[] types = widest.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean userIdPlaced = false;
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (!userIdPlaced && t == String.class) {
                    args[i] = userId;
                    userIdPlaced = true;
                } else if (t == String.class) {
                    // EMPTY STRING, NEVER NULL. Kotlin non-null parameters are
                    // enforced inside the constructor, and R8 compiles those checks
                    // down to a bare `invoke-virtual {pN} Object->getClass()` rather
                    // than an Intrinsics call — so they are invisible if you grep for
                    // `checkNotNullParameter` (which is exactly how I missed them).
                    // Passing null to such a parameter throws NPE from INSIDE the
                    // constructor, surfacing only as InvocationTargetException.
                    // Device-diagnosed on 6.1.0: UserToken guards params 1-2 and
                    // UserProfile guards 1 and 18 — all String — so filling every
                    // String with "" satisfies them without caring which are guarded.
                    args[i] = "";
                } else {
                    args[i] = zeroFor(t);
                }
            }

            widest.setAccessible(true);
            Object instance = widest.newInstance(args);
            DebugTrace.write("SyntheticModel: built " + className
                    + " via " + types.length + "-arg ctor, userId=" + userId
                    + (userIdPlaced ? "" : " (WARNING: no String param to place it in)"));
            return instance;
        } catch (Throwable e) {
            DebugTrace.write("SyntheticModel: failed to build " + className, e);
            return null;
        }
    }

    /** Type-appropriate zero: primitives get their default, objects get null. */
    private static Object zeroFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }
}
