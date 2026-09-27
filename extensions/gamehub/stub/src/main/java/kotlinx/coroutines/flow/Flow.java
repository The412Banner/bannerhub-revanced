package kotlinx.coroutines.flow;

import kotlin.coroutines.Continuation;

/** Compile-time stub of the KEPT kotlinx.coroutines.flow.Flow interface. */
public interface Flow<T> {
    Object collect(FlowCollector<?> collector, Continuation<?> continuation);
}
