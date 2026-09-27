package kotlin.coroutines.jvm.internal;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;

/**
 * Compile-time stub of the KEPT kotlin.coroutines.jvm.internal.ContinuationImpl
 * (6.3.1: smali_classes5/kotlin/coroutines/jvm/internal/ContinuationImpl.smali —
 * public abstract, ctors {@code (Continuation)} and
 * {@code (Continuation, CoroutineContext)}, public {@code getContext()}).
 * Subclassed by the Steam relay to satisfy the R8-typed suspend parameter
 * of the host's Steam bridge ({@code twv.c(..., ContinuationImpl)}).
 */
public abstract class ContinuationImpl extends BaseContinuationImpl {
    public ContinuationImpl(Continuation<?> completion) {
        super(completion);
    }

    public ContinuationImpl(Continuation<?> completion, CoroutineContext context) {
        super(completion);
    }

    @Override
    public CoroutineContext getContext() {
        throw new UnsupportedOperationException("stub");
    }
}
