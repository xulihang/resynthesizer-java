package resynth;

/**
 * Parameters of the inner image synthesis engine.
 * Mirrors TImageSynthParameters (lib/engineParams.h).
 */
public final class Params {
    public boolean tileHorizontally;
    public boolean tileVertically;

    /** 0 = random order, don't use context; 1 = random order, use context; 2..8 = ordered modes. */
    public int matchContextType = 1;

    public float mapWeight = 0.5f;
    /** AKA autism; a parameter of the cauchy PDF for pixel difference weighting. */
    public float sensitivityToOutliers = 0.117f;
    /** Count of patch elements. */
    public int patchSize = 30;
    /** Max random probes per target pixel per pass. */
    public int maxProbeCount = 200;
    /** Base seed; the engine derives one PRNG seed per (pass, thread). Change for varied results. */
    public long randomSeed = 1198472L;

    /*
     * Java-only extensions (not in the C engine). Both 0 reproduces the C engine exactly.
     */
    /** Gaussian sigma (pixels) weighting patch elements by distance from the patch centre. 0 = off. */
    public float neighborWeight = 0f;

    public static Params defaults() {
        return new Params();
    }
}
