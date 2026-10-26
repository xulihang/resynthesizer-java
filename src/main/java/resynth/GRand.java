package resynth;

/**
 * Port of GLib's GRand (mersenne-twister algorithm), so the sequence of random
 * numbers - and therefore the output image - matches the C engine bit for bit.
 *
 * GLib changed the default generator to MT19937 in 2.32; this reproduces that
 * generator including its 32-bit fallback path, which is what a 32-bit build
 * of the C engine uses.
 */
public final class GRand {
    private static final int MT_N = 624;
    private static final int MT_M = 397;
    private static final long MATRIX_A = 0x9908b0dfL;
    private static final long UPPER_MASK = 0x80000000L;
    private static final long LOWER_MASK = 0x7fffffffL;

    /** State words; only low 32 bits are used. */
    private final long[] mt = new long[MT_N];
    private int mti = MT_N + 1;

    public GRand(int seed) {
        init(seed);
    }

    /** glib's g_rand_init (mt19937.c). */
    private void init(int seed) {
        mt[0] = seed;
        for (mti = 1; mti < MT_N; mti++) {
            long prev = mt[mti - 1];
            long tmp = 1812433253L * (prev ^ (prev >>> 30)) + mti;
            mt[mti] = tmp & 0xffffffffL;
        }
    }

    /** glib's g_rand_gen (mt19937.c), 32-bit build path. */
    /** Raw 32-bit output (guint), exposed for tests. */
    int gen() {
        if (mti >= MT_N) {
            int kk;
            for (kk = 0; kk < MT_N - MT_M; kk++) {
                long y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + MT_M] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? MATRIX_A : 0);
            }
            for (; kk < MT_N - 1; kk++) {
                long y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + (MT_M - MT_N)] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? MATRIX_A : 0);
            }
            {
                long y = (mt[MT_N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
                mt[MT_N - 1] = mt[MT_M - 1] ^ (y >>> 1) ^ ((y & 0x1) != 0 ? MATRIX_A : 0);
            }
            mti = 0;
        }
        long y = mt[mti++];
        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680L;
        y ^= (y << 15) & 0xefc60000L;
        y ^= (y >>> 18);
        return (int) y; // interpreted as guint32 by callers
    }

    /**
     * g_rand_int_range(begin, end) -> uniform uint in [begin, end).
     * The result is cast to int the same way the C code treats it (as guint).
     */
    public int intRange(int begin, int end) {
        long range = (end & 0xffffffffL) - (begin & 0xffffffffL);
        long offset = gen() & 0xffffffffL;
        // g_rand_double_range(begin, end) = (guint)(begin + offset/MAX * range)
        double d = (double) offset / 4294967296.0;
        return (int) ((begin & 0xffffffffL) + (long) (d * (double) range));
    }
}
