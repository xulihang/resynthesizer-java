package resynth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Command-line heal: image in (PNG, JPEG, or anything ImageIO reads), one PNG out.
 * No extra files, no GUI.
 *
 * <p>Usage: {@code java -jar resynth.jar <image> <mask> <out.png>
 * [patchSize] [maxProbeCount] [neighborWeight]}
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
        if (args.length < 3 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            usage();
            return 2;
        }
        if (args.length > 6) {
            usage();
            return 2;
        }

        String imagePath = args[0];
        String maskPath = args[1];
        String outPath = args[2];

        Params params = Params.defaults();
        try {
            if (args.length > 3) params.patchSize = Integer.parseInt(args[3]);
            if (args.length > 4) params.maxProbeCount = Integer.parseInt(args[4]);
            if (args.length > 5) params.neighborWeight = Float.parseFloat(args[5]);
        } catch (NumberFormatException e) {
            System.err.println("invalid number: " + e.getMessage());
            usage();
            return 2;
        }
        if (params.patchSize <= 0 || params.maxProbeCount <= 0 || params.neighborWeight < 0) {
            System.err.println("patchSize and maxProbeCount must be positive; neighborWeight must be >= 0");
            return 2;
        }

        try {
            byte[] imagePng = toPng(Files.readAllBytes(Paths.get(imagePath)), "image " + imagePath);
            byte[] maskPng = toPng(Files.readAllBytes(Paths.get(maskPath)), "mask " + maskPath);

            long t0 = System.nanoTime();
            byte[] healedPng = PngSynth.heal(imagePng, maskPng, params, null, null, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            File out = new File(outPath);
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create output directory " + parent);
            }
            Files.write(out.toPath(), healedPng);

            System.out.println("healed " + imagePath + " -> " + outPath
                    + " (" + healedPng.length + " bytes, " + ms + " ms)");
            return 0;
        } catch (PngSynth.SynthException e) {
            System.err.println("engine error: " + e.getMessage());
            return e.errorCode();
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    /** PNG bytes pass through; anything else ImageIO can decode is re-encoded as PNG. */
    private static byte[] toPng(byte[] bytes, String what) throws IOException {
        if (PngSynth.isPng(bytes)) return bytes;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IllegalArgumentException(what + " is not a supported image");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
        return out.toByteArray();
    }

    private static void usage() {
        System.err.println("Usage: java -jar resynth.jar <image> <mask> <out.png>"
                + " [patchSize] [maxProbeCount] [neighborWeight]");
        System.err.println("  image: PNG, JPEG or any other format ImageIO can read; output is always PNG.");
        System.err.println("  mask:  PNG recommended (JPEG artifacts can alter the selection);");
        System.err.println("         visible non-black mask pixels are healed.");
    }
}
