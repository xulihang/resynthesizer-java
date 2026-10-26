package resynth;

/**
 * SimpleAPI: one image + mask. Mirrors imageSynth.c and adaptSimple.h.
 *
 * The image is both target/context and corpus. The mask selects the target;
 * its inverse selects the corpus. Results are written back into the image.
 */
public final class ImageSynth {
    public enum Format { RGB, RGBA, Gray, GrayA }

    /** A raw image/ mask: interleaved bytes, rows possibly padded (rowBytes). */
    public static final class ImageBuffer {
        public final byte[] data;
        public final int width;
        public final int height;
        public final int rowBytes;

        public ImageBuffer(byte[] data, int width, int height, int rowBytes) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.rowBytes = rowBytes;
        }
    }

    private ImageSynth() {}

    public static int countPixelelsPerPixelForFormat(Format format) {
        switch (format) {
            case RGB: return 3;
            case RGBA: return 4;
            case Gray: return 1;
            case GrayA: return 2;
            default: return 0;
        }
    }

    static FormatIndices prepareIndices(Format format) {
        switch (format) {
            case RGB: return FormatIndices.prepare(3, 0, false, false, false);
            case RGBA: return FormatIndices.prepare(3, 0, true, true, false);
            case Gray: return FormatIndices.prepare(1, 0, false, false, false);
            case GrayA: return FormatIndices.prepare(1, 0, true, true, false);
            default: throw new IllegalArgumentException("invalid image format");
        }
    }

    /** Copy SOME pixelels of each pixel into a pixmap, optionally offset within destination pixel. */
    private static Map adaptImage(ImageBuffer image, int offset, int pixelelCount) {
        Map pixmap = Map.newPixmap(image.width, image.height, pixelelCount + offset);
        int destPixel = offset;
        int srcPixelStride = pixelelCount;
        int destPixelStride = pixelelCount + offset;
        for (int row = 0; row < image.height; row++) {
            int srcPixel = row * image.rowBytes;
            for (int col = 0; col < image.width; col++) {
                for (int pixelel = 0; pixelel < pixelelCount; pixelel++) {
                    pixmap.pix[destPixel + pixelel] = image.data[srcPixel + pixelel];
                }
                srcPixel += srcPixelStride;
                destPixel += destPixelStride;
            }
        }
        return pixmap;
    }

    /** Reverse of adaptImage: copy from the engine pixmap back into the row-padded image. */
    private static void antiAdaptImage(ImageBuffer imageBuffer, Map pixmap, int offset, int pixelelCount) {
        int srcPixel = offset;
        int srcPixelStride = pixelelCount + offset;
        int destPixelStride = pixelelCount;
        for (int row = 0; row < imageBuffer.height; row++) {
            int destPixel = row * imageBuffer.rowBytes;
            for (int col = 0; col < imageBuffer.width; col++) {
                for (int pixelel = 0; pixelel < pixelelCount; pixelel++) {
                    imageBuffer.data[destPixel + pixelel] = pixmap.pix[srcPixel + pixelel];
                }
                srcPixel += srcPixelStride;
                destPixel += destPixelStride;
            }
        }
    }

    public static int imageSynth(ImageBuffer imageBuffer, ImageBuffer mask, Format imageFormat,
                                 Params parameters, Engine.ProgressCallback progressCallback,
                                 Object contextInfo, CancelFlag cancelFlag) {
        if (imageBuffer.width != mask.width || imageBuffer.height != mask.height) {
            return Engine.ERROR_IMAGE_MASK_MISMATCH;
        }
        if (parameters == null) parameters = Params.defaults();
        if (cancelFlag == null) cancelFlag = new CancelFlag();

        FormatIndices formatIndices;
        try {
            formatIndices = prepareIndices(imageFormat);
        } catch (IllegalArgumentException e) {
            return Engine.ERROR_INVALID_IMAGE_FORMAT;
        }

        int pixelelPerPixel = countPixelelsPerPixelForFormat(imageFormat);

        // Target: image + mask interleaved.
        Map targetMap = adaptImage(imageBuffer, Engine.FIRST_PIXELEL_INDEX, pixelelPerPixel);
        Map targetMaskMap = adaptImage(mask, 0, 1);
        Map.interleaveMask(targetMap, targetMaskMap);

        // Corpus: image + inverted mask interleaved.
        Map corpusMap = adaptImage(imageBuffer, Engine.FIRST_PIXELEL_INDEX, pixelelPerPixel);
        Map corpusMaskMap = adaptImage(mask, 0, 1);
        corpusMaskMap.invertBytemap();
        Map.interleaveMask(corpusMap, corpusMaskMap);

        int error = Engine.engine(parameters, formatIndices, targetMap, corpusMap,
                progressCallback, contextInfo, cancelFlag);

        if (error == 0 && !cancelFlag.isCancelled()) {
            antiAdaptImage(imageBuffer, targetMap, 1, pixelelPerPixel);
        }
        return error;
    }
}
