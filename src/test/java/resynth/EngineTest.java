package resynth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {

    /** The margin (context) around a rectangle must be untouched; the rectangle must be fully rewritten. */
    @Test
    void healRewritesTargetOnlyAndMatchesContextSurroundings() {
        int w = 160, h = 120;
        // Synthetic texture: smooth gradient plus a periodic pattern (easy to match).
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4;
                int r = (x * 2 + y) & 0xff;
                int g = ((x / 8 + y / 3) % 2 == 0) ? 200 : 100;
                int b = (y * 3) & 0xff;
                rgba[o] = (byte) r;
                rgba[o + 1] = (byte) g;
                rgba[o + 2] = (byte) b;
                rgba[o + 3] = (byte) 255;
            }
        }
        byte[] original = rgba.clone();
        byte[] mask = new byte[w * h];
        int rx = 60, ry = 40, rw = 40, rh = 30;
        for (int y = ry; y < ry + rh; y++)
            for (int x = rx; x < rx + rw; x++)
                mask[y * w + x] = (byte) 0xff;

        Params params = Params.defaults();
        params.patchSize = 16;
        params.maxProbeCount = 100;

        int rc = ImageSynth.imageSynth(
                new ImageSynth.ImageBuffer(rgba, w, h, w * 4),
                new ImageSynth.ImageBuffer(mask, w, h, w),
                ImageSynth.Format.RGBA, params, null, null, null);
        assertEquals(0, rc);

        int insideChanged = 0, outsideChanged = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 4;
                boolean changed = rgba[o] != original[o] || rgba[o + 1] != original[o + 1]
                        || rgba[o + 2] != original[o + 2];
                boolean inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh;
                if (inside && changed) insideChanged++;
                if (!inside && changed) outsideChanged++;
            }
        }
        assertEquals(0, outsideChanged, "context outside the target must not change");
        assertEquals(rw * rh, insideChanged, "every target pixel must be synthesized");

        // Alpha is not synthesized: still opaque everywhere.
        for (int i = 3; i < rgba.length; i += 4) assertEquals((byte) 255, rgba[i]);
    }

    @Test
    void emptyCorpusReturnsError() {
        int w = 32, h = 32;
        byte[] rgba = new byte[w * h * 4];
        byte[] mask = new byte[w * h];
        java.util.Arrays.fill(mask, (byte) 0xff); // everything is target
        int rc = ImageSynth.imageSynth(new ImageSynth.ImageBuffer(rgba, w, h, w * 4),
                new ImageSynth.ImageBuffer(mask, w, h, w), ImageSynth.Format.RGBA, null, null, null, null);
        assertEquals(Engine.ERROR_EMPTY_CORPUS, rc);
    }

    @Test
    void emptyTargetReturnsError() {
        int w = 32, h = 32;
        byte[] rgba = new byte[w * h * 4];
        byte[] mask = new byte[w * h]; // nothing is target
        int rc = ImageSynth.imageSynth(new ImageSynth.ImageBuffer(rgba, w, h, w * 4),
                new ImageSynth.ImageBuffer(mask, w, h, w), ImageSynth.Format.RGBA, null, null, null, null);
        assertEquals(Engine.ERROR_EMPTY_TARGET, rc);
    }

    @Test
    void patchTooLargeIsRejected() {
        Params p = Params.defaults();
        p.patchSize = Engine.IMAGE_SYNTH_MAX_NEIGHBORS + 1;
        Map t = Map.newPixmap(4, 4, 5);
        Map c = Map.newPixmap(4, 4, 5);
        FormatIndices fi = FormatIndices.prepare(3, 0, true, true, false);
        assertEquals(Engine.ERROR_PATCH_SIZE_EXCEEDED,
                Engine.engine(p, fi, t, c, null, null, new CancelFlag()));
    }

    @Test
    void metricTableIsSymmetricAndIncreasing() {
        Metrics m = new Metrics(0.117f, 0.5f);
        // Table holds differences -256..255; zero difference costs nothing.
        assertEquals(0, m.corpusTargetMetric[256]);
        // Symmetric around 0.
        for (int d = 1; d <= 255; d++) {
            assertEquals(m.corpusTargetMetric[256 + d], m.corpusTargetMetric[256 - d], "diff " + d);
        }
        // Strictly increasing with |difference| (cauchy penalty).
        for (int d = 1; d <= 255; d++) {
            assertTrue(m.corpusTargetMetric[256 + d] > m.corpusTargetMetric[256 + d - 1], "diff " + d);
        }
        // Top value fits in gushort (C: (gushort)value) and is close to MAX_WEIGHT.
        assertTrue(m.corpusTargetMetric[256 + 255] <= Metrics.MAX_WEIGHT);
    }

    @Test
    void grandIsRepeatableAndDeterministic() {
        GRand a = new GRand(1198472);
        GRand b = new GRand(1198472);
        for (int i = 0; i < 1000; i++) assertEquals(a.intRange(0, 500), b.intRange(0, 500));
        // First raw output must match standard MT19937 seeded with GLib's init algorithm
        // (verified against numpy.random.RandomState(1198472)).
        GRand c = new GRand(1198472);
        int first = c.gen();
        assertEquals(0x1e27422b, first);
    }
}
