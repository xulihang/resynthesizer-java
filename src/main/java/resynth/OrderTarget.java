package resynth;

/**
 * Order target points. Mirrors orderTarget.h and brushfire.h.
 * matchContextType chooses the ordering (0..8).
 */
public final class OrderTarget {
    public static final double IMAGE_SYNTH_BAND_FRACTION = 0.1;

    private OrderTarget() {}

    /** Returns 0 on success, or an error code for an out-of-range matchContextType. */
    public static int order(Params parameters, PointVector targetPoints, GRand prng) {
        switch (parameters.matchContextType) {
            case 0:
            case 1:
                random(targetPoints, prng);
                break;
            case 2:
                brushfire(targetPoints, prng, true);
                break;
            case 3:
                directional(targetPoints, prng, Kind.HORIZONTAL, true);
                break;
            case 4:
                directional(targetPoints, prng, Kind.VERTICAL, true);
                break;
            case 5:
                brushfire(targetPoints, prng, false);
                break;
            case 6:
                directional(targetPoints, prng, Kind.HORIZONTAL, false);
                break;
            case 7:
                directional(targetPoints, prng, Kind.VERTICAL, false);
                break;
            case 8:
                squeeze(targetPoints, prng);
                break;
            default:
                return Engine.ERROR_MATCH_CONTEXT_TYPE_RANGE;
        }
        return Engine.SUCCESS;
    }

    /** orderTargetPointsRandom: the original method. */
    public static void random(PointVector targetPoints, GRand prng) {
        for (int i = 0; i < targetPoints.len; i++) {
            int j = prng.intRange(0, targetPoints.len);
            targetPoints.swap(i, j);
        }
    }

    /** randomizeBandsTargetPoints. */
    public static void randomizeBands(PointVector targetPoints, GRand prng) {
        int last = targetPoints.len - 1;
        int halfBand = (int) (targetPoints.len * IMAGE_SYNTH_BAND_FRACTION);
        for (int i = 0; i <= last; i++) {
            int bandStart = Math.max(i - halfBand, 0);
            int bandEnd = Math.min(i + halfBand, last);
            int bandSize = bandEnd - bandStart;
            int j = bandStart + prng.intRange(0, bandSize);
            targetPoints.swap(i, j);
        }
    }

    private enum Kind { HORIZONTAL, VERTICAL }

    /** orderTargetPointsRandomDirectional. moreInward here means "increasing distance" flags. */
    private static void directional(PointVector tp, GRand prng, Kind kind, boolean inward) {
        int[] center = getCenter(tp);
        toOffsets(tp, center[0], center[1]);
        // sort ascending or descending distance, depending on compare function.
        sortByDistance(tp, kind, inward);
        fromOffsets(tp, center[0], center[1]);
        randomizeBands(tp, prng);
    }

    /** orderTargetPointsRandomBrushfire. */
    private static void brushfire(PointVector tp, GRand prng, boolean inward) {
        int[] center = getCenter(tp);
        toOffsets(tp, center[0], center[1]);
        SortElementStruct[] sortArray = targetPointsToSortArray(tp);
        java.util.Arrays.sort(sortArray, (a, b) -> compareProportion(a, b, inward));
        targetPointsFromSortArray(tp, sortArray);
        fromOffsets(tp, center[0], center[1]);
        randomizeBands(tp, prng);
    }

    /** orderTargetPointsRandomSqueeze. */
    private static void squeeze(PointVector tp, GRand prng) {
        int n = tp.len;
        int[] center = getCenter(tp);
        toOffsets(tp, center[0], center[1]);
        SortElementStruct[] sortArray = targetPointsToSortArray(tp);
        // sorted ascending on distance from center (moreInward in C means this order)
        java.util.Arrays.sort(sortArray, (a, b) -> compareProportion(a, b, false));

        PointVector targetTemp = tp.copy();

        int frontIndex = 0;
        int backIndex = n - 1;
        int i = 0;
        while (true) {
            tp.set(i, targetTemp.x(frontIndex), targetTemp.y(frontIndex));
            i++;
            frontIndex++;
            if (i >= n) break;
            tp.set(i, targetTemp.x(backIndex), targetTemp.y(backIndex));
            i++;
            backIndex--;
            if (i >= n) break;
        }

        targetPointsFromSortArray(tp, sortArray);
        fromOffsets(tp, center[0], center[1]);
        randomizeBands(tp, prng);
    }

    // ------------------------------------------------------------------

    static final class SortElementStruct {
        int x, y;
        float proportionToCenter;
    }

    private static void toOffsets(PointVector tp, int cx, int cy) {
        for (int i = 0; i < tp.len; i++) {
            tp.set(i, tp.x(i) - cx, tp.y(i) - cy);
        }
    }

    private static void fromOffsets(PointVector tp, int cx, int cy) {
        for (int i = 0; i < tp.len; i++) {
            tp.set(i, tp.x(i) + cx, tp.y(i) + cy);
        }
    }

    private static int[] getCenter(PointVector tp) {
        int ulx = Integer.MAX_VALUE, uly = Integer.MAX_VALUE, lrx = 0, lry = 0;
        for (int i = 0; i < tp.len; i++) {
            int x = tp.x(i), y = tp.y(i);
            if (x < ulx) ulx = x;
            if (y < uly) uly = y;
            if (x > lrx) lrx = x;
            if (y > lry) lry = y;
        }
        // C integer division truncates toward zero; coordinates here are non-negative.
        return new int[]{ (lrx - ulx) / 2 + ulx, (lry - uly) / 2 + uly };
    }

    /**
     * Sort on offsets. In C, to_sort_result(true) = -1 (a first), so ascending sort
     * with comparator that returns -1 when a is "less".
     * moreX = invert -> descending.
     */
    private static void sortByDistance(PointVector tp, Kind kind, boolean ascending) {
        // extract to boxed-free arrays for sorting
        Integer[] order = new Integer[tp.len];
        for (int i = 0; i < tp.len; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            long av, bv;
            if (kind == Kind.HORIZONTAL) {
                av = (long) tp.x(a) * tp.x(a);
                bv = (long) tp.x(b) * tp.x(b);
            } else {
                av = (long) tp.y(a) * tp.y(a);
                bv = (long) tp.y(b) * tp.y(b);
            }
            // C: to_sort_result(a<b) => -1 else 1. Java: use 0 for equal, invert by negation.
            int r = Long.compare(av, bv);
            return ascending ? r : -r;
        });
        // apply permutation
        PointVector out = new PointVector(tp.len);
        for (int i = 0; i < tp.len; i++) {
            int idx = order[i];
            out.add(tp.x(idx), tp.y(idx));
        }
        tp.data = out.data;
        tp.len = out.len;
    }

    private static SortElementStruct[] targetPointsToSortArray(PointVector tp) {
        int[] maxCartesianAlongRay = new int[401];
        // prepare_max_cartesian_along_ray
        for (int i = 0; i < tp.len; i++) {
            int x = tp.x(i), y = tp.y(i);
            int cartesian = x * x + y * y;
            int ray = grad(x, y);
            if (cartesian > maxCartesianAlongRay[ray]) maxCartesianAlongRay[ray] = cartesian;
        }
        SortElementStruct[] arr = new SortElementStruct[tp.len];
        for (int i = 0; i < tp.len; i++) {
            SortElementStruct s = new SortElementStruct();
            s.x = tp.x(i);
            s.y = tp.y(i);
            int ray = grad(s.x, s.y);
            // C: (gfloat)((a.y*a.y)+(a.x*a.x)) / max[ray]; float division, NaN if max is 0 (compares FALSE)
            float cartesian = (float) (s.y * s.y) + (float) (s.x * s.x);
            s.proportionToCenter = cartesian / (float) maxCartesianAlongRay[ray];
            arr[i] = s;
        }
        return arr;
    }

    private static void targetPointsFromSortArray(PointVector tp, SortElementStruct[] arr) {
        for (int i = 0; i < tp.len; i++) {
            tp.set(i, arr[i].x, arr[i].y);
        }
    }

    /** C: (guint)(atan2((gfloat)y, (gfloat)x) * 200 / G_PI + 200), using float G_PI. */
    private static int grad(int x, int y) {
        final double cPI = 3.1415926535897932384626433832795028841971693993751;
        float a = (float) y;
        float b = (float) x;
        double atan = Math.atan2(a, b);
        double v = atan * 200 / cPI + 200;
        // (guint) truncation of a non-negative value (atan2 in [-pi,pi] -> [0,400])
        return (int) v;
    }

    private static int compareProportion(SortElementStruct a, SortElementStruct b, boolean invert) {
        // C's to_sort_result never returns 0; Java's comparator must be consistent.
        int r = Float.compare(a.proportionToCenter, b.proportionToCenter);
        return invert ? -r : r;
    }
}
