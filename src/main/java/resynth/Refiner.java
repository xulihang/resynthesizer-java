package resynth;

/**
 * Repeated passes over the target, threaded (alternative 1): each pass divides
 * targetPoints among THREAD_LIMIT threads and rejoins before the next pass.
 * Mirrors refinerThreaded.h and passes.h.
 */
public final class Refiner {
    public static final int THREAD_LIMIT = 8;
    public static final int MAX_PASSES = 6;
    public static final double IMAGE_SYNTH_TERMINATE_FRACTION = 0.1;
    private static final boolean VERBOSE = Boolean.getBoolean("resynth.verbose");

    private Refiner() {}

    /** One pass's end index; repetition_params[pass][1] = 0 for passes past the useful range. */
    static int[] prepareRepetitionParameters(int countTargetPoints) {
        int[] ends = new int[MAX_PASSES];
        int n = countTargetPoints;
        ends[0] = n;
        for (int i = 1; i < MAX_PASSES; i++) {
            if (n == 0) {
                ends[i] = 0;
            } else {
                ends[i] = n;
            }
            n = (n * 3) / 4;
        }
        return ends;
    }

    static void refiner(Params parameters, FormatIndices indices,
                        Map targetMap, Map corpusMap, Map recentProberMap,
                        Map hasValueMap, Map sourceOfMap,
                        PointVector targetPoints, PointVector corpusPoints, PointVector sortedOffsets,
                        GRand prng, Metrics metrics,
                        Engine.ProgressCallback progressCallback, Object contextInfo,
                        CancelFlag cancelFlag) {

        int[] ends = prepareRepetitionParameters(targetPoints.len);
        Progress progress = new Progress(Progress.estimatePixelsToSynth(ends), progressCallback, contextInfo);
        Object colorLock = new Object();

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            int endTargetIndex = ends[pass];
            if (endTargetIndex == 0) break;

            long[] betters = synthesizePass(parameters, indices, targetMap, corpusMap, recentProberMap,
                    hasValueMap, sourceOfMap, targetPoints, corpusPoints, sortedOffsets,
                    prng, metrics, colorLock, progress, cancelFlag, endTargetIndex, pass);

            long totalBetters = 0;
            for (long b : betters) totalBetters += b;

            if (VERBOSE) {
                System.out.println("  pass " + pass + " endIndex=" + endTargetIndex + " betters=" + totalBetters);
            }
            if ((float) totalBetters / targetPoints.len < IMAGE_SYNTH_TERMINATE_FRACTION) {
                break;
            }
            if (cancelFlag.isCancelled()) break;
        }
    }

    private static long[] synthesizePass(Params parameters, FormatIndices indices,
                                         Map targetMap, Map corpusMap, Map recentProberMap,
                                         Map hasValueMap, Map sourceOfMap,
                                         PointVector targetPoints, PointVector corpusPoints, PointVector sortedOffsets,
                                         GRand prng, Metrics metrics, Object colorLock, Progress progress,
                                         CancelFlag cancelFlag, int endTargetIndex, int pass) {
        int threadCount = THREAD_LIMIT;
        long[] betters = new long[threadCount];
        Thread[] threads = new Thread[threadCount];
        Throwable[] errors = new Throwable[threadCount];

        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            final int ti = threadIndex;
            // Every thread works on a prefix of targetPoints, splits it modulo threadIndex.
            final Synthesizer synth = new Synthesizer(parameters, indices, targetMap, corpusMap, recentProberMap,
                    hasValueMap, sourceOfMap, targetPoints, corpusPoints, sortedOffsets, prng, metrics,
                    colorLock, progress, cancelFlag);
            // Deterministic per (pass, thread) seed: unlike the C engine's shared PRNG data race,
            // the output does not depend on thread timing. Depends on the base seed in Params.
            final long threadSeed = parameters.randomSeed
                    + 0x9E3779B97F4A7C15L * (pass + 1) + 0xBF58476D1CE4E5B9L * (ti + 1);
            threads[ti] = new Thread(() -> {
                synth.setRandomSeed(threadSeed);
                try {
                    betters[ti] = synth.synthesize(ti, 0, endTargetIndex);
                } catch (Throwable t) {
                    errors[ti] = t;
                }
            });
            threads[ti].start();
        }

        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            try {
                threads[threadIndex].join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (errors[threadIndex] != null) {
                throw new RuntimeException("worker failed", errors[threadIndex]);
            }
        }
        return betters;
    }
}
