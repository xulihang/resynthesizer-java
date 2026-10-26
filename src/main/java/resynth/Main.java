package resynth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Command-line heal demo: resynthesize a rectangular target region from its surrounding
 * context (the classic "heal selection" use), using only the JDK for image I/O.
 *
 * Usage: Main <in.png> <out.png> <x> <y> <w> <h> [patchSize] [maxProbeCount] [threads]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Usage: Main <in.png> <out.png> <x> <y> <w> <h> [patchSize] [maxProbeCount]");
            System.exit(2);
        }
        String inPath = args[0];
        String outPath = args[1];
        int x = Integer.parseInt(args[2]);
        int y = Integer.parseInt(args[3]);
        int w = Integer.parseInt(args[4]);
        int h = Integer.parseInt(args[5]);
        int patchSize = args.length > 6 ? Integer.parseInt(args[6]) : 30;
        int maxProbeCount = args.length > 7 ? Integer.parseInt(args[7]) : 200;

        BufferedImage src = ImageIO.read(new File(inPath));
        if (src == null) {
            System.err.println("Cannot read " + inPath);
            System.exit(1);
        }
        int width = src.getWidth();
        int height = src.getHeight();

        // Convert to RGBA bytes once. Never use getRGB() inside the engine.
        byte[] rgba = new byte[width * height * 4];
        int[] argb = new int[width];
        for (int row = 0; row < height; row++) {
            src.getRGB(0, row, width, 1, argb, 0, width);
            for (int col = 0; col < width; col++) {
                int p = argb[col];
                int o = (row * width + col) * 4;
                rgba[o] = (byte) (p >> 16);
                rgba[o + 1] = (byte) (p >> 8);
                rgba[o + 2] = (byte) p;
                rgba[o + 3] = (byte) (p >> 24);
            }
        }

        // Mask: 255 inside the rectangle (target), 0 outside (context/corpus).
        byte[] mask = new byte[width * height];
        for (int row = y; row < y + h; row++) {
            int xe = Math.min(x + w, width);
            for (int col = x; col < xe; col++) {
                mask[row * width + col] = (byte) 0xFF;
            }
        }

        Params params = Params.defaults();
        params.patchSize = patchSize;
        params.maxProbeCount = maxProbeCount;
        params.matchContextType = 1; // random order, use context

        ImageSynth.ImageBuffer image = new ImageSynth.ImageBuffer(rgba, width, height, width * 4);
        ImageSynth.ImageBuffer maskBuf = new ImageSynth.ImageBuffer(mask, width, height, width);

        // Progress only when running to a terminal-ish environment.
        Engine.ProgressCallback progress = (pct, ctx) -> System.out.print("\r  progress " + pct + "%    ");
        System.out.println("Resynthesizer (Java) heal: " + width + "x" + height
                + ", target " + w + "x" + h + " at (" + x + "," + y + ")"
                + ", patchSize=" + patchSize + ", maxProbeCount=" + maxProbeCount
                + ", gcThreads=" + Refiner.THREAD_LIMIT);

        long t0 = System.nanoTime();
        int rc = ImageSynth.imageSynth(image, maskBuf, ImageSynth.Format.RGBA, params,
                progress, null, new CancelFlag());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println();
        if (rc != 0) {
            System.err.println("engine returned error " + rc);
            System.exit(rc);
        }
        System.out.println("elapsed: " + ms + " ms");

        // Write back to ARGB BufferedImage.
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] outRow = new int[width];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int o = (row * width + col) * 4;
                int a = rgba[o + 3] & 0xff;
                int r = rgba[o] & 0xff;
                int g = rgba[o + 1] & 0xff;
                int b = rgba[o + 2] & 0xff;
                outRow[col] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setRGB(0, row, width, 1, outRow, 0, width);
        }
        ImageIO.write(out, "png", new File(outPath));
        System.out.println("wrote " + outPath);
    }
}
