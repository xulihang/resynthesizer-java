package resynth;

/** Quantized lookup tables for the match metric. Mirrors matchWeighting.h. */
public final class Metrics {
    public static final int LIMIT_DOMAIN = 256;
    public static final int LIMIT_DOMAIN_MAP = LIMIT_DOMAIN + 1;
    /** G_MAXUSHORT */
    public static final int MAX_WEIGHT = 0xffff;
    public static final double MAP_MULTIPLIER = 4.0;

    /** Unvectorized table: 512 entries, indexed by 256 + signed difference. */
    public final int[] corpusTargetMetric = new int[512];
    /** Unvectorized map table: 512 entries. */
    public final int[] mapsMetric = new int[512];

    public Metrics(float cauchyParam, float mapWeightParam) {
        for (int i = -LIMIT_DOMAIN; i < LIMIT_DOMAIN; i++) {
            double value = proportionToNegLnCauchy(i, cauchyParam)
                    / proportionToNegLnCauchy(LIMIT_DOMAIN, cauchyParam) * MAX_WEIGHT;
            // (gushort)value: C double -> unsigned short truncation
            corpusTargetMetric[LIMIT_DOMAIN + i] = toGushort(value);
        }
        for (int i = -LIMIT_DOMAIN; i < LIMIT_DOMAIN; i++) {
            mapsMetric[LIMIT_DOMAIN + i] = (int) toGuint(i * i * mapWeightParam * MAP_MULTIPLIER);
        }
    }

    /** C: (gushort)double  -> truncate toward zero, then take low 16 bits. */
    private static int toGushort(double value) {
        return (int) toGuint(value) & 0xffff;
    }

    /** C: (guint)double  -> truncate toward zero, then take low 32 bits (as signed int). */
    private static long toGuint(double value) {
        long t = (long) value; // Java truncates toward zero like C, saturating
        return t & 0xffffffffL;
    }

    private static double proportionToNegLnCauchy(double x, float cauchyParam) {
        double denom = cauchyParam * LIMIT_DOMAIN;
        double r = x / denom;
        return Math.log(r * r + 1.0);
    }
}
