package resynth;

/**
 * The engine. Mirrors engine.c.
 *
 * Independent of GIMP and of platform. Takes target and corpus maps in the engine's
 * internal interleaved format (see FormatIndices), writes synthesized colors into targetMap.
 */
public final class Engine {
    public static final int SUCCESS = 0;
    public static final int ERROR_INVALID_IMAGE_FORMAT = 1;
    public static final int ERROR_IMAGE_MASK_MISMATCH = 2;
    public static final int ERROR_PATCH_SIZE_EXCEEDED = 3;
    public static final int ERROR_MATCH_CONTEXT_TYPE_RANGE = 4;
    public static final int ERROR_EMPTY_TARGET = 5;
    public static final int ERROR_EMPTY_CORPUS = 6;

    public static final int MASK_PIXELEL_INDEX = 0;
    public static final int FIRST_PIXELEL_INDEX = 1;
    public static final int IMAGE_SYNTH_MAX_NEIGHBORS = 64;
    public static final int MASK_TOTALLY_SELECTED = 0xFF;
    public static final int MASK_UNSELECTED = 0;
    public static final int ALPHA_TOTAL_TRANSPARENCY = 0;

    /** Progress sink: percentComplete in [0..], context is the caller's. */
    public interface ProgressCallback {
        void progress(int percentComplete, Object context);
    }

    private Engine() {}

    public static int engine(Params parameters,
                             FormatIndices indices,
                             Map targetMap,
                             Map corpusMap,
                             ProgressCallback progressCallback,
                             Object contextInfo,
                             CancelFlag cancelFlag) {
        if (parameters.patchSize > IMAGE_SYNTH_MAX_NEIGHBORS) {
            return ERROR_PATCH_SIZE_EXCEEDED;
        }

        // target prep
        Map hasValueMap = Map.newBytemap(targetMap.width, targetMap.height);
        PointVector targetPoints = new PointVector(targetMap.width * targetMap.height);
        prepareTargetPoints(parameters.matchContextType != 0, indices, targetMap, hasValueMap, targetPoints);

        if (targetPoints.len == 0) {
            return ERROR_EMPTY_TARGET;
        }
        Map sourceOfMap = prepareTargetSources(targetMap);

        // source prep
        PointVector corpusPoints = new PointVector(corpusMap.width * corpusMap.height + 1);
        prepareCorpusPoints(indices, corpusMap, corpusPoints);
        if (corpusPoints.len == 0) {
            return ERROR_EMPTY_CORPUS;
        }

        // prep things not images
        PointVector sortedOffsets = prepareSortedOffsets(targetMap, corpusMap);
        Metrics metrics = new Metrics(parameters.sensitivityToOutliers, parameters.mapWeight);

        // Now we need a prng, before order_targetPoints.
        // Fixed seed, exactly as the C engine, for repeatable tests.
        GRand prng = new GRand(1198472);

        int error = OrderTarget.order(parameters, targetPoints, prng);
        if (error != 0) return error;

        Map recentProberMap = prepareRecentProber(corpusMap);

        // Preparations done, begin actual synthesis
        Refiner.refiner(
                parameters,
                indices,
                targetMap,
                corpusMap,
                recentProberMap,
                hasValueMap,
                sourceOfMap,
                targetPoints,
                corpusPoints,
                sortedOffsets,
                prng,
                metrics,
                progressCallback,
                contextInfo,
                cancelFlag);

        return SUCCESS; // Success, even if canceled
    }

    // ------------------------------------------------------------------

    static void prepareTargetPoints(boolean isUseContext,
                                    FormatIndices indices,
                                    Map targetMap,
                                    Map hasValueMap,
                                    PointVector targetPoints) {
        for (int y = 0; y < targetMap.height; y++) {
            for (int x = 0; x < targetMap.width; x++) {
                boolean isTarget = isSelectedTarget(targetMap, x, y);
                boolean value = isUseContext
                        // outside the target
                        && !isTarget
                        && notTransparentImage(indices, targetMap, x, y);
                hasValueMap.pix[x + y * targetMap.width] = (byte) (value ? 1 : 0);

                if (isTarget) {
                    targetPoints.add(x, y);
                }
            }
        }
    }

    static Map prepareTargetSources(Map targetMap) {
        Map sourceOfMap = Map.newCoordmap(targetMap.width, targetMap.height);
        // initialized to 0,0; set all to null_coords (-1,-1)
        java.util.Arrays.fill(sourceOfMap.xs, -1);
        java.util.Arrays.fill(sourceOfMap.ys, -1);
        return sourceOfMap;
    }

    static void prepareCorpusPoints(FormatIndices indices, Map corpusMap, PointVector corpusPoints) {
        for (int y = 0; y < corpusMap.height; y++) {
            for (int x = 0; x < corpusMap.width; x++) {
                if (isSelectedCorpus(corpusMap, x, y) && notTransparentCorpus(indices, corpusMap, x, y)) {
                    corpusPoints.add(x, y);
                }
            }
        }
    }

    /** Vector of offsets to surrounding points, sorted ascending on distance from (0,0). */
    static PointVector prepareSortedOffsets(Map targetMap, Map corpusMap) {
        int width = Math.min(corpusMap.width, targetMap.width);
        int height = Math.min(corpusMap.height, targetMap.height);
        // C: 2*width-1; here 2*width-1 with int arithmetic (guint allocatedSize)
        int rowCount = (2 * height - 1);
        int colCount = (2 * width - 1);
        PointVector offsets = new PointVector(rowCount * colCount);
        for (int y = -height + 1; y < height; y++) {
            for (int x = -width + 1; x < width; x++) {
                offsets.add(x, y);
            }
        }
        // g_array_sort with lessCartesian. to_sort_result(a<b): -1 if a<b else 1.
        Integer[] order = new Integer[offsets.len];
        for (int i = 0; i < offsets.len; i++) order[i] = i;
        final PointVector finalOffsets = offsets;
        java.util.Arrays.sort(order, (a, b) -> {
            long av = (long) finalOffsets.y(a) * finalOffsets.y(a) + (long) finalOffsets.x(a) * finalOffsets.x(a);
            long bv = (long) finalOffsets.y(b) * finalOffsets.y(b) + (long) finalOffsets.x(b) * finalOffsets.x(b);
            // C's to_sort_result never returns 0; Java's comparator must be consistent.
            return Long.compare(av, bv);
        });
        PointVector sorted = new PointVector(offsets.len);
        for (int i = 0; i < offsets.len; i++) {
            int idx = order[i];
            sorted.add(offsets.x(idx), offsets.y(idx));
        }
        return sorted;
    }

    static Map prepareRecentProber(Map corpusMap) {
        Map recentProberMap = Map.newIntmap(corpusMap.width, corpusMap.height);
        java.util.Arrays.fill(recentProberMap.ints, -1);
        return recentProberMap;
    }

    // ---- selection / transparency predicates (called from the bottleneck) ----

    static boolean isSelectedCorpus(Map corpusMap, int x, int y) {
        return (corpusMap.pix[(x + y * corpusMap.width) * corpusMap.depth + MASK_PIXELEL_INDEX] & 0xff)
                == MASK_TOTALLY_SELECTED;
    }

    static boolean isSelectedTarget(Map targetMap, int x, int y) {
        return (targetMap.pix[(x + y * targetMap.width) * targetMap.depth + MASK_PIXELEL_INDEX] & 0xff)
                != MASK_UNSELECTED;
    }

    static boolean notTransparentImage(FormatIndices indices, Map targetMap, int x, int y) {
        if (!indices.isAlphaTarget) return true;
        return (targetMap.pix[(x + y * targetMap.width) * targetMap.depth + indices.alphaBip] & 0xff)
                != ALPHA_TOTAL_TRANSPARENCY;
    }

    static boolean notTransparentCorpus(FormatIndices indices, Map corpusMap, int x, int y) {
        if (!indices.isAlphaSource) return true;
        return (corpusMap.pix[(x + y * corpusMap.width) * corpusMap.depth + indices.alphaBip] & 0xff)
                != ALPHA_TOTAL_TRANSPARENCY;
    }
}
