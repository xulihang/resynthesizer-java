package resynth;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Intermediate between the deeper engine and the app's progress callback.
 * Mirrors progress.c. Called from synthesize() every IMAGE_SYNTH_CALLBACK_COUNT target pixels.
 */
public final class Progress {
    final int estimatedPixelCountToCompletion;
    private final AtomicInteger completedPixelCount = new AtomicInteger();
    private final AtomicInteger priorReportedPercentComplete = new AtomicInteger();
    private final Engine.ProgressCallback callback;
    private final Object context;

    Progress(int estimatedPixelCountToCompletion, Engine.ProgressCallback callback, Object context) {
        this.estimatedPixelCountToCompletion = estimatedPixelCountToCompletion;
        this.callback = callback;
        this.context = context;
    }

    static int estimatePixelsToSynth(int[] repetitionEnds) {
        int total = 0;
        for (int e : repetitionEnds) total += e;
        return total;
    }

    /** Threaded deepProgressCallback. */
    void onDeepProgress() {
        int completed = completedPixelCount.addAndGet(Synthesizer.IMAGE_SYNTH_CALLBACK_COUNT);
        int percentComplete = (int) (((float) completed / estimatedPixelCountToCompletion) * 100);
        if (percentComplete > priorReportedPercentComplete.get()) {
            if (callback != null) callback.progress(percentComplete, context);
            priorReportedPercentComplete.set(percentComplete);
        }
    }
}
