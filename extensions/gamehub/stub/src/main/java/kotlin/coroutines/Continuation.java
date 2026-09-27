package kotlin.coroutines;

/** Compile-time stub of the KEPT kotlin.coroutines.Continuation interface. */
public interface Continuation<T> {
    CoroutineContext getContext();
    void resumeWith(Object result);
}
