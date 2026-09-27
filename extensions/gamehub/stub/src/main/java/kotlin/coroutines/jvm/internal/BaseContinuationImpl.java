package kotlin.coroutines.jvm.internal;

import kotlin.coroutines.Continuation;

/**
 * Compile-time stub of the KEPT kotlin.coroutines.jvm.internal
 * .BaseContinuationImpl. The real class drives {@code invokeSuspend} from its
 * final {@code resumeWith} and forwards the outcome to the completion passed
 * to the constructor. Extension code subclasses {@link ContinuationImpl} only.
 */
public abstract class BaseContinuationImpl implements Continuation<Object> {
    public BaseContinuationImpl(Continuation<?> completion) {
        throw new UnsupportedOperationException("stub");
    }

    public abstract Object invokeSuspend(Object result);

    public final Continuation<?> getCompletion() {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public final void resumeWith(Object result) {
        throw new UnsupportedOperationException("stub");
    }
}
