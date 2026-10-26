package resynth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Heal using an arbitrary grayscale mask (mask != 0 selects the target).
 * Produces:
 *   <out>            the healed image
 *   <out>-before.png input + red outline of the target region
 *   <out>-after.png  healed image + red outline
 *   <out>-compare.png before | after side by side
 *
 * Usage: MainMask <image.png> <mask.png> <out.png> [patchSize] [maxProbeCount] [neighborWeight]
 */
public final class MainMask {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: MainMask <image.png> <mask.png> <out.png> [patchSize] [maxProbeCount]");
            System.exit(2);
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        BufferedImage maskImg = ImageIO.read(new File(args[1]));
        String outPath = args[2];
        int patchSize = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        int maxProbeCount = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        float neighborWeight = args.length > 5 ? Float.parseFloat(args[5]) : 0f;

        int width = src.getWidth(), height = src.getHeight();
        if (maskImg.getWidth() != width || maskImg.getHeight() != height) {
            System.err.println("mask and image sizes differ");
            System.exit(1);
        }

        // Convert RGBA once.
        byte[] rgba = new byte[width * height * 4];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            src.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int p = row[x];
                int o = (y * width + x) * 4;
                rgba[o] = (byte) (p >> 16);
                rgba[o + 1] = (byte) (p >> 8);
                rgba[o + 2] = (byte) p;
                rgba[o + 3] = (byte) (p >> 24);
            }
        }

        // Mask: any nonzero luminance selects the target.
        byte[] mask = new byte[width * height];
        int targetCount = 0;
        for (int y = 0; y < height; y++) {
            maskImg.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int p = row[x];
                int lum = (((p >> 16) & 0xff) * 299 + ((p >> 8) & 0xff) * 587 + (p & 0xff) * 114) / 1000;
                if (lum > 0) {
                    mask[y * width + x] = (byte) 0xFF;
                    targetCount++;
                }
            }
        }
        int corpusCount = width * height - targetCount;
        System.out.println("image " + width + "x" + height + ", target pixels " + targetCount
                + ", corpus pixels " + corpusCount);

        BufferedImage outlineBefore = drawOutline(toArgb(rgba, width, height), mask, width, height);

        Params params = Params.defaults();
        params.patchSize = patchSize;
        params.maxProbeCount = maxProbeCount;
        params.neighborWeight = neighborWeight;

        long t0 = System.nanoTime();
        int rc = ImageSynth.imageSynth(
                new ImageSynth.ImageBuffer(rgba, width, height, width * 4),
                new ImageSynth.ImageBuffer(mask, width, height, width),
                ImageSynth.Format.RGBA, params, null, null, new CancelFlag());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (rc != 0) {
            System.err.println("engine returned error " + rc);
            System.exit(rc);
        }
        System.out.println("patchSize=" + patchSize + ", maxProbeCount=" + maxProbeCount
                + ", neighborWeight=" + neighborWeight
                + ", threads=" + Refiner.THREAD_LIMIT + " -> " + ms + " ms");

        BufferedImage healed = toArgb(rgba, width, height);
        BufferedImage outlineAfter = drawOutline(healed, mask, width, height);

        String base = outPath.replaceFirst("\\.png$", "");
        ImageIO.write(healed, "png", new File(base + ".png"));
        ImageIO.write(outlineBefore, "png", new File(base + "-before.png"));
        ImageIO.write(outlineAfter, "png", new File(base + "-after.png"));
        ImageIO.write(sideBySide(outlineBefore, outlineAfter), "png", new File(base + "-compare.png"));
        System.out.println("wrote " + base + ".png, -before.png, -after.png, -compare.png");
    }

    private static BufferedImage toArgb(byte[] rgba, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = (y * width + x) * 4;
                row[x] = ((rgba[o + 3] & 0xff) << 24) | ((rgba[o] & 0xff) << 16)
                        | ((rgba[o + 1] & 0xff) << 8) | (rgba[o + 2] & 0xff);
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    /** Paint the boundary of the mask in red so the target region is visible in the output. */
    private static BufferedImage drawOutline(BufferedImage src, byte[] mask, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.setColor(new java.awt.Color(255, 0, 0, 220));
        g.setStroke(new java.awt.BasicStroke(1.5f));
        // horizontal edges
        for (int y = 0; y <= height; y++) {
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean edge = false;
                if (x < width) {
                    boolean above = y > 0 && mask[(y - 1) * width + x] != 0;
                    boolean below = y < height && mask[y * width + x] != 0;
                    edge = above != below;
                }
                if (edge) {
                    if (runStart < 0) runStart = x;
                } else if (runStart >= 0) {
                    g.drawLine(runStart, y, x - 1, y);
                    runStart = -1;
                }
            }
        }
        // vertical edges
        for (int x = 0; x <= width; x++) {
            int runStart = -1;
            for (int y = 0; y <= height; y++) {
                boolean edge = false;
                if (y < height) {
                    boolean left = x > 0 && mask[y * width + (x - 1)] != 0;
                    boolean right = x < width && mask[y * width + x] != 0;
                    edge = left != right;
                }
                if (edge) {
                    if (runStart < 0) runStart = y;
                } else if (runStart >= 0) {
                    g.drawLine(x, runStart, x, y - 1);
                    runStart = -1;
                }
            }
        }
        g.dispose();
        return out;
    }

    private static BufferedImage sideBySide(BufferedImage a, BufferedImage b) {
        int gap = 8;
        BufferedImage out = new BufferedImage(a.getWidth() + gap + b.getWidth(), a.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(a, 0, 0, null);
        g.drawImage(b, a.getWidth() + gap, 0, null);
        g.dispose();
        return out;
    }
}
