package resynth;

/** Thread-safe cancel flag. Mirrors int* cancelFlag. */
public final class CancelFlag {
    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }
}
