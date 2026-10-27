package resynth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * PNG-in / PNG-out heal API. Both the image and the mask are PNG-encoded bytes; the healed
 * image is returned as PNG-encoded bytes. Nothing touches the file system.
 *
 * <p>Input must be PNG (the signature is checked). The mask selects the target: pixels that
 * are visible (alpha != 0) and not black (luminance > 0) are healed. Alpha in the image is
 * preserved and never synthesized. The output PNG has an alpha channel exactly when the
 * input image has one.
 */
public final class PngSynth {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private PngSynth() {}

    /** Engine failure; {@link #errorCode()} is one of the {@code Engine.ERROR_*} constants. */
    public static final class SynthException extends IOException {
        private final int errorCode;

        SynthException(int errorCode) {
            super(describe(errorCode) + " (error " + errorCode + ")");
            this.errorCode = errorCode;
        }

        public int errorCode() {
            return errorCode;
        }
    }

    /** Heal with default parameters. */
    public static byte[] heal(byte[] imagePng, byte[] maskPng) throws IOException {
        return heal(imagePng, maskPng, null, null, null, null);
    }

    /** Heal with the given parameters ({@code null} means {@link Params#defaults()}). */
    public static byte[] heal(byte[] imagePng, byte[] maskPng, Params parameters) throws IOException {
        return heal(imagePng, maskPng, parameters, null, null, null);
    }

    /** Heal with the two parameters that matter most; handy from reflection-based hosts (B4J). */
    public static byte[] heal(byte[] imagePng, byte[] maskPng, int patchSize, int maxProbeCount)
            throws IOException {
        Params params = Params.defaults();
        params.patchSize = patchSize;
        params.maxProbeCount = maxProbeCount;
        return heal(imagePng, maskPng, params, null, null, null);
    }

    /**
     * Heal the region selected by {@code maskPng} in {@code imagePng}.
     *
     * @throws IllegalArgumentException if either input is not PNG or the sizes differ
     * @throws SynthException           if the engine rejects the request
     * @throws IOException              if a PNG cannot be decoded or encoded
     */
    public static byte[] heal(byte[] imagePng, byte[] maskPng, Params parameters,
                              Engine.ProgressCallback progressCallback,
                              Object contextInfo, CancelFlag cancelFlag) throws IOException {
        BufferedImage image = decodePng(imagePng, "image");
        BufferedImage maskImage = decodePng(maskPng, "mask");
        if (maskImage.getWidth() != image.getWidth() || maskImage.getHeight() != image.getHeight()) {
            throw new IllegalArgumentException("mask size " + maskImage.getWidth() + "x"
                    + maskImage.getHeight() + " differs from image size "
                    + image.getWidth() + "x" + image.getHeight());
        }
        return healDecoded(image, toMask(maskImage), parameters, progressCallback, contextInfo, cancelFlag);
    }

    /** Heal a rectangle; the mask is generated internally. */
    public static byte[] healRect(byte[] imagePng, int x, int y, int w, int h) throws IOException {
        return healRect(imagePng, x, y, w, h, null, null, null, null);
    }

    /** Heal a rectangle with the given parameters ({@code null} means {@link Params#defaults()}). */
    public static byte[] healRect(byte[] imagePng, int x, int y, int w, int h, Params parameters,
                                  Engine.ProgressCallback progressCallback,
                                  Object contextInfo, CancelFlag cancelFlag) throws IOException {
        BufferedImage image = decodePng(imagePng, "image");
        int width = image.getWidth(), height = image.getHeight();
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        if (x0 >= x1 || y0 >= y1) {
            throw new IllegalArgumentException("rectangle is empty or outside the image");
        }
        byte[] mask = new byte[width * height];
        for (int row = y0; row < y1; row++) {
            Arrays.fill(mask, row * width + x0, row * width + x1, (byte) 0xFF);
        }
        return healDecoded(image, mask, parameters, progressCallback, contextInfo, cancelFlag);
    }

    // ------------------------------------------------------------------

    private static byte[] healDecoded(BufferedImage image, byte[] mask, Params parameters,
                                      Engine.ProgressCallback progressCallback,
                                      Object contextInfo, CancelFlag cancelFlag) throws IOException {
        int width = image.getWidth(), height = image.getHeight();
        boolean alpha = image.getColorModel().hasAlpha();
        int channels = alpha ? 4 : 3;
        byte[] pixels = toPixels(image, channels);

        Params params = parameters != null ? parameters : Params.defaults();
        CancelFlag cancel = cancelFlag != null ? cancelFlag : new CancelFlag();

        int rc = ImageSynth.imageSynth(
                new ImageSynth.ImageBuffer(pixels, width, height, width * channels),
                new ImageSynth.ImageBuffer(mask, width, height, width),
                alpha ? ImageSynth.Format.RGBA : ImageSynth.Format.RGB,
                params, progressCallback, contextInfo, cancel);
        if (rc != 0) throw new SynthException(rc);

        return encodePng(fromPixels(pixels, width, height, alpha));
    }

    private static BufferedImage decodePng(byte[] png, String what) throws IOException {
        if (png == null || png.length < PNG_SIGNATURE.length) {
            throw new IllegalArgumentException(what + " is not a PNG");
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) throw new IllegalArgumentException(what + " is not a PNG");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("cannot decode " + what + " PNG");
        return image;
    }

    /** ARGB pixels -> packed engine buffer (RGB or RGBA, 1 byte per channel). */
    private static byte[] toPixels(BufferedImage image, int channels) {
        int width = image.getWidth(), height = image.getHeight();
        byte[] pixels = new byte[width * height * channels];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            int o = y * width * channels;
            for (int x = 0; x < width; x++) {
                int p = row[x];
                pixels[o] = (byte) (p >> 16);
                pixels[o + 1] = (byte) (p >> 8);
                pixels[o + 2] = (byte) p;
                if (channels == 4) pixels[o + 3] = (byte) (p >>> 24);
                o += channels;
            }
        }
        return pixels;
    }

    /** Packed engine buffer -> ARGB image. Alpha is attached only when the input had it. */
    private static BufferedImage fromPixels(byte[] pixels, int width, int height, boolean alpha) {
        int channels = alpha ? 4 : 3;
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int o = y * width * channels;
            for (int x = 0; x < width; x++) {
                int a = alpha ? pixels[o + 3] & 0xFF : 0xFF;
                row[x] = (a << 24) | ((pixels[o] & 0xFF) << 16)
                        | ((pixels[o + 1] & 0xFF) << 8) | (pixels[o + 2] & 0xFF);
                o += channels;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /** Visible, non-black mask pixels become 0xFF (target); everything else stays 0 (corpus). */
    private static byte[] toMask(BufferedImage maskImage) {
        int width = maskImage.getWidth(), height = maskImage.getHeight();
        byte[] mask = new byte[width * height];
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            maskImage.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int p = row[x];
                if ((p >>> 24) == 0) continue; // fully transparent pixels never select
                int lum = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                if (lum > 0) mask[y * width + x] = (byte) 0xFF;
            }
        }
        return mask;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer available");
        return out.toByteArray();
    }

    private static String describe(int code) {
        switch (code) {
            case Engine.ERROR_INVALID_IMAGE_FORMAT: return "invalid image format";
            case Engine.ERROR_IMAGE_MASK_MISMATCH: return "image and mask sizes differ";
            case Engine.ERROR_PATCH_SIZE_EXCEEDED: return "patch size exceeds the engine limit";
            case Engine.ERROR_MATCH_CONTEXT_TYPE_RANGE: return "match context type out of range";
            case Engine.ERROR_EMPTY_TARGET: return "mask selects no target pixels";
            case Engine.ERROR_EMPTY_CORPUS: return "mask leaves no corpus pixels";
            default: return "unknown engine error";
        }
    }
}
