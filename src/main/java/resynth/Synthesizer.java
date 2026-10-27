package resynth;


/**
 * The heart of the algorithm: matches a target patch against corpus patches.
 * Mirrors synthesize.h (computeBestFit, prepare_neighbors, synthesize).
 *
 * Each thread runs its own Synthesizer instance (own neighbors buffer and PRNG),
 * like each C thread has its own stack frame.
 */
final class Synthesizer {
    static final int PERFECT_MATCH = 0;
    static final int NO_BETTERMENT = 1;
    static final int GENERIC_BETTERMENT = 2;
    static final int NEIGHBORS_SOURCE = 3;
    static final int RANDOM_CORPUS = 4;

    static final int IMAGE_SYNTH_CALLBACK_COUNT = 4095;

    private final Params parameters;
    private final FormatIndices indices;
    private final Map targetMap;
    private final Map corpusMap;
    private final Map recentProberMap;
    private final Map hasValueMap;
    private final Map sourceOfMap;
    private final PointVector targetPoints;
    private final PointVector corpusPoints;
    private final PointVector sortedOffsets;
    private final GRand prng;
    private final Metrics metrics;
    private final Object colorLock;
    private final Progress progress;
    private final CancelFlag cancelFlag;

    // Copy of the whole Pixel for each neighbor (MAX_IMAGE_SYNTH_BPP bytes) + offset, sourceOf
    private final byte[] neighborPixel = new byte[Engine.IMAGE_SYNTH_MAX_NEIGHBORS * 8];
    private final int[] neighborOffX = new int[Engine.IMAGE_SYNTH_MAX_NEIGHBORS];
    private final int[] neighborOffY = new int[Engine.IMAGE_SYNTH_MAX_NEIGHBORS];
    private final int[] neighborSrcX = new int[Engine.IMAGE_SYNTH_MAX_NEIGHBORS];
    private final int[] neighborSrcY = new int[Engine.IMAGE_SYNTH_MAX_NEIGHBORS];

    // fast paths
    private final int targetDepth;
    private final int corpusDepth;
    private final byte[] targetPix;
    private final byte[] corpusPix;
    private final int[] recentInts;
    private final byte[] hasValuePix;
    private final int[] sourceXs;
    private final int[] sourceYs;
    private final int[] corpusTargetMetric;
    private final int[] mapsMetric;

    // Per-thread PRNG. The C engine shares one GRand across threads (a data race, hence
    // nondeterministic output). Here each worker thread gets its own, explicitly seeded PRNG,
    // so output is repeatable and independent of thread scheduling.
    private java.util.SplittableRandom rnd;

    private final boolean useContext;
    private final boolean isAlphaTarget;
    private final int alphaBip;

    Synthesizer(Params parameters, FormatIndices indices,
                Map targetMap, Map corpusMap, Map recentProberMap,
                Map hasValueMap, Map sourceOfMap,
                PointVector targetPoints, PointVector corpusPoints, PointVector sortedOffsets,
                GRand prng, Metrics metrics, Object colorLock,
                Progress progress, CancelFlag cancelFlag) {
        this.parameters = parameters;
        this.indices = indices;
        this.targetMap = targetMap;
        this.corpusMap = corpusMap;
        this.recentProberMap = recentProberMap;
        this.hasValueMap = hasValueMap;
        this.sourceOfMap = sourceOfMap;
        this.targetPoints = targetPoints;
        this.corpusPoints = corpusPoints;
        this.sortedOffsets = sortedOffsets;
        this.prng = prng;
        this.metrics = metrics;
        this.colorLock = colorLock;
        this.progress = progress;
        this.cancelFlag = cancelFlag;

        this.targetPix = targetMap.pix;
        this.corpusPix = corpusMap.pix;
        this.recentInts = recentProberMap.ints;
        this.hasValuePix = hasValueMap.pix;
        this.sourceXs = sourceOfMap.xs;
        this.sourceYs = sourceOfMap.ys;
        this.corpusTargetMetric = metrics.corpusTargetMetric;
        this.mapsMetric = metrics.mapsMetric;
        this.targetDepth = targetMap.depth;
        this.corpusDepth = corpusMap.depth;
        this.rnd = new java.util.SplittableRandom(parameters.randomSeed);
        this.useContext = parameterUseContext(parameters);
        this.isAlphaTarget = indices.isAlphaTarget;
        this.alphaBip = indices.alphaBip;

        // Optional weighting of neighbors (distance from patch center) and of corpus pixels
        // (near the context boundary). Java-only extension for healing quality;
        // both weights 0 reproduces the C engine exactly.
        this.weightNeighbors = parameters.neighborWeight > 0;
        if (weightNeighbors) {
            neighborWeight = new double[Engine.IMAGE_SYNTH_MAX_NEIGHBORS];
            this.neighborSigma2 = 2.0 * parameters.neighborWeight * parameters.neighborWeight;
        } else {
            neighborWeight = null;
            neighborSigma2 = 1;
        }
    }

    private final boolean weightNeighbors;
    private final double[] neighborWeight;
    private final double neighborSigma2;

    private static boolean parameterUseContext(Params p) {
        // the C engine always calls prepareTargetPoints with is_use_context = (matchContextType != 0)
        return p.matchContextType != 0;
    }

    // ------------------------------------------------------------------
    // Neighbor (patch) preparation
    // ------------------------------------------------------------------

    private int countNeighbors; // number of valid neighbors after prepareNeighbors

    private int prepareNeighbors(int positionX, int positionY) {
        int count = 0;
        // Target point is always its own first neighbor.
        int offX = sortedOffsets.x(0);
        int offY = sortedOffsets.y(0);
        newNeighbor(0, offX, offY, positionX + offX, positionY + offY);
        count++;

        int patchSize = parameters.patchSize;
        for (int j = 1; j < sortedOffsets.len; j++) {
            offX = sortedOffsets.x(j);
            offY = sortedOffsets.y(j);
            int nx = positionX + offX;
            int ny = positionY + offY;

            // clipToTargetOrWrapIfTiled
            int cx = nx;
            int cy = ny;
            boolean inTarget = true;
            while (cx < 0) {
                if (parameters.tileHorizontally) cx += targetMap.width; else { inTarget = false; break; }
            }
            if (!inTarget) continue;
            while (cx >= targetMap.width) {
                if (parameters.tileHorizontally) cx -= targetMap.width; else { inTarget = false; break; }
            }
            if (!inTarget) continue;
            while (cy < 0) {
                if (parameters.tileVertically) cy += targetMap.height; else { inTarget = false; break; }
            }
            if (!inTarget) continue;
            while (cy >= targetMap.height) {
                if (parameters.tileVertically) cy -= targetMap.height; else { inTarget = false; break; }
            }
            if (!inTarget) continue;

            if (hasValuePix[cx + cy * targetMap.width] != 0) {
                newNeighbor(count, offX, offY, cx, cy);
                count++;
                if (count >= patchSize) break;
            }
        }
        return count;
    }

    private void newNeighbor(int index, int offX, int offY, int px, int py) {
        if (weightNeighbors) {
            double d2 = (double) offX * offX + (double) offY * offY;
            neighborWeight[index] = Math.exp(-d2 / neighborSigma2);
        }
        int ti = px + py * targetMap.width;
        neighborOffX[index] = offX;
        neighborOffY[index] = offY;
        neighborSrcX[index] = sourceXs[ti];
        neighborSrcY[index] = sourceYs[ti];
        int base = index * 8;
        int tbase = ti * targetDepth;
        for (int k = 0; k < indices.totalBpp; k++) {
            neighborPixel[base + k] = targetPix[tbase + k];
        }
    }

    // ------------------------------------------------------------------
    // computeBestFit: sum of weighted pixelel differences, with early out.
    // ------------------------------------------------------------------

    /** @return true if PERFECT_MATCH. Updates best.outDiff / best.outX / best.outY. */
    private boolean computeBestFit(int pointX, int pointY,
                                   int[] best) {
        int sum = 0;
        int countNeighbors = this.countNeighbors;
        int colorEndBip = indices.colorEndBip;
        int mapStartBip = indices.mapStartBip;
        int mapEndBip = indices.mapEndBip;
        boolean hasMaps = indices.mapMatchBpp > 0;
        int cw = corpusMap.width;
        int ch = corpusMap.height;
        int[] ctm = corpusTargetMetric;
        int[] mm = mapsMetric;
        final boolean simpleMode = !weightNeighbors;

        for (int i = 0; i < countNeighbors; i++) {
            int offX = neighborOffX[i];
            int offY = neighborOffY[i];
            int px = pointX + offX;
            int py = pointY + offY;
            int ci = (px < 0 || py < 0 || px >= cw || py >= ch) ? -1 : px + py * cw;
            // clippedOrMaskedCorpus
            if (ci < 0 || (corpusPix[ci * corpusDepth] & 0xff) != Engine.MASK_TOTALLY_SELECTED) {
                // max weighted difference for this neighbor outside corpus
                sum += Metrics.MAX_WEIGHT * indices.imgMatchBpp + mm[0] * indices.mapMatchBpp;
            } else {
                int cbase = ci * corpusDepth;
                int tbase = i * 8;
                if (i != 0) {
                    if (simpleMode) {
                        for (int j = Engine.FIRST_PIXELEL_INDEX; j < colorEndBip; j++) {
                            sum += ctm[256 + (neighborPixel[tbase + j] & 0xff) - (corpusPix[cbase + j] & 0xff)];
                        }
                    } else {
                        double factor = neighborWeight[i];
                        if (factor != 0) {
                            int s2 = 0;
                            for (int j = Engine.FIRST_PIXELEL_INDEX; j < colorEndBip; j++) {
                                s2 += ctm[256 + (neighborPixel[tbase + j] & 0xff) - (corpusPix[cbase + j] & 0xff)];
                            }
                            sum += (int) Math.round(s2 * factor);
                        }
                    }
                }
                if (hasMaps) {
                    for (int j = mapStartBip; j < mapEndBip; j++) {
                        sum += mm[256 + (neighborPixel[tbase + j] & 0xff) - (corpusPix[cbase + j] & 0xff)];
                    }
                }
            }
            if (sum >= best[0]) return false; // short circuit for neighbors
        }

        best[0] = sum;
        best[1] = pointX;
        best[2] = pointY;
        return sum <= 0;
    }

    void setRandomSeed(long seed) {
        this.rnd = new java.util.SplittableRandom(seed);
    }

    private int randomCorpusPointIndex(int len) {
        return rnd.nextInt(len);
    }

    // ------------------------------------------------------------------
    // synthesize: over a slice of targetPoints
    // ------------------------------------------------------------------

    /** Returns count of target pixels that were bettered this pass. */
    int synthesize(int threadIndex, int startTargetIndex, int endTargetIndex) {
        int threadLimit = Refiner.THREAD_LIMIT;
        int repeatCountBetters = 0;
        int[] best = new int[3];

        int maxProbeCount = parameters.maxProbeCount;
        int tw = targetMap.width;

        for (int targetIndex = startTargetIndex + threadIndex % threadLimit;
             targetIndex < endTargetIndex;
             targetIndex += threadLimit) {

            if ((targetIndex & IMAGE_SYNTH_CALLBACK_COUNT) == 0) {
                progress.onDeepProgress();
                if (cancelFlag.isCancelled()) break;
            }

            int positionX = targetPoints.x(targetIndex);
            int positionY = targetPoints.y(targetIndex);

            countNeighbors = prepareNeighbors(positionX, positionY);
            int neighborCount = countNeighbors;

            best[0] = Integer.MAX_VALUE; // G_MAXUINT as unsigned; use signed max
            boolean isPerfectMatch = false;

            // Heuristic 1: try neighbors of sources of neighbors of target pixel.
            for (int neighborIndex = 0; neighborIndex < neighborCount && best[0] != 0; neighborIndex++) {
                if (neighborSrcX[neighborIndex] != -1) {
                    int corpusX = neighborSrcX[neighborIndex] - neighborOffX[neighborIndex];
                    int corpusY = neighborSrcY[neighborIndex] - neighborOffY[neighborIndex];
                    // clippedOrMaskedCorpus
                    if (corpusX < 0 || corpusY < 0 || corpusX >= corpusMap.width || corpusY >= corpusMap.height) continue;
                    int ci = corpusX + corpusY * corpusMap.width;
                    if ((corpusPix[ci * corpusDepth] & 0xff) != Engine.MASK_TOTALLY_SELECTED) continue;
                    if (recentInts[ci] == targetIndex) continue; // Heuristic 2
                    isPerfectMatch = computeBestFit(corpusX, corpusY, best);
                    if (isPerfectMatch) break;
                    recentInts[ci] = targetIndex;
                }
            }

            // Random probes from corpus.
            if (!isPerfectMatch) {
                for (int j = 0; j < maxProbeCount; j++) {
                    int idx = randomCorpusPointIndex(corpusPoints.len);
                    isPerfectMatch = computeBestFit(corpusPoints.x(idx), corpusPoints.y(idx), best);
                    if (isPerfectMatch) break;
                }
            }

            // C: if (latestBettermentKind != NO_BETTERMENT). computeBestFit only updates best[]
            // on a real betterment, so best[0] != initial value means a betterment happened.
            if (best[0] != Integer.MAX_VALUE) { // a betterment happened (any candidate beaten the large initial diff)
                int bestX = best[1];
                int bestY = best[2];
                int ti = positionX + positionY * tw;
                boolean sameSource = (sourceXs[ti] == bestX && sourceYs[ti] == bestY);
                if (!sameSource) {
                    repeatCountBetters++;
                    synchronized (colorLock) {
                        // setColor: copy color pixelels (not alpha) from corpus to target
                        int cbase = (bestX + bestY * corpusMap.width) * corpusDepth;
                        int tbase = ti * targetDepth;
                        for (int j = Engine.FIRST_PIXELEL_INDEX; j < indices.colorEndBip; j++) {
                            targetPix[tbase + j] = corpusPix[cbase + j];
                        }
                        sourceXs[ti] = bestX;
                        sourceYs[ti] = bestY;
                    }
                }
            }

            hasValuePix[positionX + positionY * tw] = 1;
        }
        return repeatCountBetters;
    }
}
