package resynth;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Command-line heal: image in (PNG, JPEG, or anything ImageIO reads), one PNG out.
 * No extra files, no GUI.
 *
 * <p>Usage: {@code java -jar resynth.jar [--regions [--margin N] [--cell N]]
 * <image> <mask> <out.png> [patchSize] [maxProbeCount] [neighborWeight] [maxPasses]}
 *
 * <p>{@code --regions} heals clusters of mask pixels in separate local crops instead of
 * the whole image. This keeps the corpus local and is much faster on large images with
 * sparse masks; pixels outside the mask are still copied from the input unchanged.
 *
 * <p>Exit codes: 0 success, 2 usage / bad numbers, 1 unreadable input,
 * otherwise the {@code Engine.ERROR_*} code. The mask selects visible, non-black pixels.
 */
public final class HealCli {

    private HealCli() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs the CLI without exiting the JVM (used by tests). */
    static int run(String[] args) {
        boolean regions = false;
        int margin = -1;
        int cell = 32;
        int first = 0;
        try {
            while (first < args.length && args[first].startsWith("--")) {
                String a = args[first];
                if ("--regions".equals(a)) {
                    regions = true;
                    first++;
                } else if (a.startsWith("--margin=")) {
                    margin = Integer.parseInt(a.substring("--margin=".length()));
                    first++;
                } else if (a.startsWith("--cell=")) {
                    cell = Integer.parseInt(a.substring("--cell=".length()));
                    first++;
                } else if ("--margin".equals(a) || "--cell".equals(a)) {
                    if (first + 1 >= args.length) {
                        usage();
                        return 2;
                    }
                    int v = Integer.parseInt(args[first + 1]);
                    if ("--margin".equals(a)) margin = v;
                    else cell = v;
                    first += 2;
                } else {
                    usage();
                    return 2;
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("invalid number: " + e.getMessage());
            usage();
            return 2;
        }

        String[] rest = Arrays.copyOfRange(args, first, args.length);
        if (rest.length < 3 || rest.length > 7 || cell <= 0) {
            usage();
            return 2;
        }

        String imagePath = rest[0];
        String maskPath = rest[1];
        String outPath = rest[2];

        Params params = Params.defaults();
        try {
            if (rest.length > 3) params.patchSize = Integer.parseInt(rest[3]);
            if (rest.length > 4) params.maxProbeCount = Integer.parseInt(rest[4]);
            if (rest.length > 5) params.neighborWeight = Float.parseFloat(rest[5]);
            if (rest.length > 6) params.maxPasses = Integer.parseInt(rest[6]);
        } catch (NumberFormatException e) {
            System.err.println("invalid number: " + e.getMessage());
            usage();
            return 2;
        }
        if (params.patchSize <= 0 || params.maxProbeCount <= 0
                || params.neighborWeight < 0 || params.maxPasses < 1) {
            System.err.println("patchSize, maxProbeCount and maxPasses must be positive;"
                    + " neighborWeight must be >= 0");
            return 2;
        }
        if (margin < 0) margin = Math.max(32, 2 * params.patchSize);

        try {
            if (regions) {
                return runRegions(imagePath, maskPath, outPath, params, margin, cell);
            }
            return runWhole(imagePath, maskPath, outPath, params);
        } catch (PngSynth.SynthException e) {
            System.err.println("engine error: " + e.getMessage());
            return e.errorCode();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    private static int runWhole(String imagePath, String maskPath, String outPath, Params params)
            throws IOException {
        byte[] imagePng = toPng(Files.readAllBytes(Paths.get(imagePath)), "image " + imagePath);
        byte[] maskPng = toPng(Files.readAllBytes(Paths.get(maskPath)), "mask " + maskPath);

        long t0 = System.nanoTime();
        byte[] healedPng = PngSynth.heal(imagePng, maskPng, params, null, null, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        write(outPath, healedPng);
        System.out.println("healed " + imagePath + " -> " + outPath
                + " (" + healedPng.length + " bytes, " + ms + " ms)");
        return 0;
    }

    private static int runRegions(String imagePath, String maskPath, String outPath, Params params,
                                  int margin, int cell) throws IOException {
        BufferedImage image = read(Files.readAllBytes(Paths.get(imagePath)), "image " + imagePath);
        BufferedImage maskImage = read(Files.readAllBytes(Paths.get(maskPath)), "mask " + maskPath);
        int width = image.getWidth(), height = image.getHeight();
        if (maskImage.getWidth() != width || maskImage.getHeight() != height) {
            throw new IllegalArgumentException("mask size " + maskImage.getWidth() + "x"
                    + maskImage.getHeight() + " differs from image size " + width + "x" + height);
        }

        byte[] target = PngSynth.toMask(maskImage);
        int[][] boxes = cluster(target, width, height, cell, margin);
        if (boxes.length == 0) {
            throw new IllegalArgumentException("mask selects no target pixels");
        }

        BufferedImage out = new BufferedImage(width, height,
                image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        long t0 = System.nanoTime();
        long regionPixels = 0;
        for (int[] box : boxes) {
            int x0 = box[0], y0 = box[1], x1 = box[2], y1 = box[3];
            int rw = x1 - x0, rh = y1 - y0;
            regionPixels += (long) rw * rh;

            BufferedImage crop = copy(image.getSubimage(x0, y0, rw, rh));
            byte[] cropMask = new byte[rw * rh];
            for (int ry = 0; ry < rh; ry++) {
                System.arraycopy(target, (y0 + ry) * width + x0, cropMask, ry * rw, rw);
            }

            BufferedImage healed = PngSynth.healImage(crop, cropMask, params, null, null, null);
            composite(out, healed, target, width, x0, y0, rw, rh);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", bytes)) throw new IOException("no PNG writer available");
        byte[] png = bytes.toByteArray();
        write(outPath, png);

        System.out.println("healed " + imagePath + " -> " + outPath
                + " (" + png.length + " bytes, " + boxes.length + " regions, "
                + regionPixels + " region px, " + ms + " ms)");
        return 0;
    }

    /** Copy only masked pixels: everything outside the mask stays byte-identical to the input. */
    private static void composite(BufferedImage out, BufferedImage healed, byte[] target,
                                  int imageWidth, int x0, int y0, int rw, int rh) {
        int[] row = new int[rw];
        for (int ry = 0; ry < rh; ry++) {
            healed.getRGB(0, ry, rw, 1, row, 0, rw);
            int base = (y0 + ry) * imageWidth + x0;
            for (int rx = 0; rx < rw; rx++) {
                if (target[base + rx] != 0) out.setRGB(x0 + rx, y0 + ry, row[rx]);
            }
        }
    }

    /**
     * Cluster target pixels on a coarse grid (8-connected cells) and return the expanded
     * bounding boxes. The grid merges glyphs into words/lines without making the engine
     * run once per character.
     */
    static int[][] cluster(byte[] target, int width, int height, int cell, int margin) {
        int cols = (width + cell - 1) / cell;
        int rows = (height + cell - 1) / cell;
        boolean[] hit = new boolean[cols * rows];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            int cy = y / cell;
            for (int x = 0; x < width; x++) {
                if (target[base + x] != 0) hit[cy * cols + x / cell] = true;
            }
        }

        int[] comp = new int[cols * rows];
        Arrays.fill(comp, -1);
        int compCount = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int cy = 0; cy < rows; cy++) {
            for (int cx = 0; cx < cols; cx++) {
                int start = cy * cols + cx;
                if (!hit[start] || comp[start] >= 0) continue;
                comp[start] = compCount;
                queue.clear();
                queue.add(start);
                while (!queue.isEmpty()) {
                    int idx = queue.poll();
                    int y = idx / cols, x = idx % cols;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue;
                            int ni = ny * cols + nx;
                            if (hit[ni] && comp[ni] < 0) {
                                comp[ni] = compCount;
                                queue.add(ni);
                            }
                        }
                    }
                }
                compCount++;
            }
        }
        if (compCount == 0) return new int[0][];

        int[] minX = new int[compCount], minY = new int[compCount];
        int[] maxX = new int[compCount], maxY = new int[compCount];
        Arrays.fill(minX, width);
        Arrays.fill(minY, height);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
        for (int y = 0; y < height; y++) {
            int base = y * width;
            int cy = y / cell;
            for (int x = 0; x < width; x++) {
                if (target[base + x] == 0) continue;
                int c = comp[cy * cols + x / cell];
                if (x < minX[c]) minX[c] = x;
                if (x > maxX[c]) maxX[c] = x;
                if (y < minY[c]) minY[c] = y;
                if (y > maxY[c]) maxY[c] = y;
            }
        }

        ArrayList<int[]> boxes = new ArrayList<>();
        for (int c = 0; c < compCount; c++) {
            if (maxX[c] < 0) continue;
            boxes.add(new int[]{
                    Math.max(0, minX[c] - margin),
                    Math.max(0, minY[c] - margin),
                    Math.min(width, maxX[c] + 1 + margin),
                    Math.min(height, maxY[c] + 1 + margin)
            });
        }
        return boxes.toArray(new int[0][]);
    }

    private static BufferedImage read(byte[] bytes, String what) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IllegalArgumentException(what + " is not a supported image");
        return image;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] row = new int[src.getWidth()];
        for (int y = 0; y < src.getHeight(); y++) {
            src.getRGB(0, y, src.getWidth(), 1, row, 0, src.getWidth());
            out.setRGB(0, y, src.getWidth(), 1, row, 0, src.getWidth());
        }
        return out;
    }

    /** PNG bytes pass through; anything else ImageIO can decode is re-encoded as PNG. */
    private static byte[] toPng(byte[] bytes, String what) throws IOException {
        if (PngSynth.isPng(bytes)) return bytes;
        BufferedImage image = read(bytes, what);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
        return out.toByteArray();
    }

    private static void write(String path, byte[] bytes) throws IOException {
        File out = new File(path);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create output directory " + parent);
        }
        Files.write(out.toPath(), bytes);
    }

    private static void usage() {
        System.err.println("Usage: java -jar resynth.jar [--regions [--margin N] [--cell N]]"
                + " <image> <mask> <out.png>");
        System.err.println("       [patchSize] [maxProbeCount] [neighborWeight] [maxPasses]");
        System.err.println("  --regions: heal mask clusters in local crops (faster on large images)");
        System.err.println("  --margin:  context around each cluster (default max(32, 2*patchSize))");
        System.err.println("  --cell:    clustering grid in pixels (default 32)");
        System.err.println("  image: PNG, JPEG or any other format ImageIO can read; output is always PNG.");
        System.err.println("  mask:  PNG recommended (JPEG artifacts can alter the selection);");
        System.err.println("         visible non-black mask pixels are healed.");
        System.err.println("  speed: maxProbeCount=100, maxPasses=2 is much faster than 500/6;");
        System.err.println("         add -Dresynth.threads=N before -jar to use more than 8 threads.");
    }
}
