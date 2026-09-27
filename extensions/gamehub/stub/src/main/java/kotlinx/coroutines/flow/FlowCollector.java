package kotlinx.coroutines.flow;

import kotlin.coroutines.Continuation;

/** Compile-time stub of the KEPT kotlinx.coroutines.flow.FlowCollector interface. */
public interface FlowCollector<T> {
    /** Returns {@code kotlin.Unit.INSTANCE} (or any non-COROUTINE_SUSPENDED value) to let the flow continue. */
    Object emit(Object value, Continuation<?> continuation);
}
