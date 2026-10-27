package resynth;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PngSynthTest {

    private static final int W = 64, H = 48;
    private static final int RX = 24, RY = 16, RW = 20, RH = 16;

    private static Params fastParams() {
        Params params = Params.defaults();
        params.patchSize = 16;
        params.maxProbeCount = 100;
        return params;
    }

    /** Deterministic texture; the damaged rectangle is repainted black and must be fully rewritten. */
    @Test
    void healRewritesMaskedBytesAndKeepsOutside() throws Exception {
        BufferedImage clean = pattern();
        BufferedImage damaged = withBlackRect(clean);

        BufferedImage result = decode(PngSynth.heal(png(damaged), png(mask()), 16, 100));

        assertEquals(W, result.getWidth());
        assertEquals(H, result.getHeight());
        assertFalse(result.getColorModel().hasAlpha(), "opaque input must produce an opaque PNG");

        int insideChanged = 0, outsideChanged = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (inside(x, y)) {
                    if (result.getRGB(x, y) != damaged.getRGB(x, y)) insideChanged++;
                } else if (result.getRGB(x, y) != clean.getRGB(x, y)) {
                    outsideChanged++;
                }
            }
        }
        assertEquals(0, outsideChanged, "context outside the mask must not change");
        assertEquals(RW * RH, insideChanged, "every masked pixel must be synthesized");
    }

    @Test
    void preservesAlphaChannel() throws Exception {
        BufferedImage clean = withAlpha(pattern(), 180);
        BufferedImage damaged = withBlackRect(clean);

        BufferedImage result = decode(PngSynth.heal(png(damaged), png(mask()), fastParams()));

        assertTrue(result.getColorModel().hasAlpha(), "input with alpha must produce a PNG with alpha");
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                assertEquals(180, result.getRGB(x, y) >>> 24, "alpha must not be synthesized");
                if (!inside(x, y)) assertEquals(clean.getRGB(x, y), result.getRGB(x, y));
            }
        }
    }

    @Test
    void healRectUsesRectangleAsMask() throws Exception {
        BufferedImage clean = pattern();
        BufferedImage damaged = withBlackRect(clean);

        BufferedImage result = decode(PngSynth.healRect(png(damaged), RX, RY, RW, RH, fastParams(),
                null, null, null));

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (!inside(x, y)) assertEquals(clean.getRGB(x, y), result.getRGB(x, y));
            }
        }
        assertEquals(RW * RH, countDifferent(damaged, result));
    }

    @Test
    void rejectsNonPngInput() throws Exception {
        byte[] jpeg = encode(pattern(), "jpg");
        byte[] image = png(pattern());
        byte[] mask = png(mask());

        assertThrows(IllegalArgumentException.class, () -> PngSynth.heal(jpeg, mask));
        assertThrows(IllegalArgumentException.class, () -> PngSynth.heal(image, jpeg));
    }

    @Test
    void rejectsMaskSizeMismatch() throws Exception {
        BufferedImage smaller = new BufferedImage(W / 2, H / 2, BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class,
                () -> PngSynth.heal(png(pattern()), png(smaller)));
    }

    @Test
    void engineErrorsBecomeSynthException() throws Exception {
        BufferedImage allWhite = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        Arrays.fill(row, 0xFFFFFFFF);
        for (int y = 0; y < H; y++) allWhite.setRGB(0, y, W, 1, row, 0, W);

        PngSynth.SynthException e = assertThrows(PngSynth.SynthException.class,
                () -> PngSynth.heal(png(pattern()), png(allWhite)));
        assertEquals(Engine.ERROR_EMPTY_CORPUS, e.errorCode());
    }

    @Test
    void cliWritesOneOutputPng() throws Exception {
        Path dir = Files.createTempDirectory("resynth-cli");
        Path in = dir.resolve("in.png");
        Path maskPath = dir.resolve("mask.png");
        Path out = dir.resolve("out.png");
        BufferedImage clean = pattern();
        Files.write(in, png(withBlackRect(clean)));
        Files.write(maskPath, png(mask()));

        int rc = HealCli.run(new String[]{"--whole", in.toString(), maskPath.toString(),
                out.toString(), "16", "100"});

        assertEquals(0, rc);
        assertTrue(Files.exists(out), "CLI must write the output PNG");
        BufferedImage result = decode(Files.readAllBytes(out));
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (!inside(x, y)) assertEquals(clean.getRGB(x, y), result.getRGB(x, y));
    }

    @Test
    void cliAcceptsJpegImageAndAlwaysWritesPng() throws Exception {
        Path dir = Files.createTempDirectory("resynth-cli-jpg");
        Path in = dir.resolve("in.jpg");
        Path maskPath = dir.resolve("mask.png");
        Path out = dir.resolve("out.png");
        byte[] jpeg = encode(withBlackRect(pattern()), "jpg");
        BufferedImage decodedJpeg = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(decodedJpeg);
        Files.write(in, jpeg);
        Files.write(maskPath, png(mask()));

        int rc = HealCli.run(new String[]{in.toString(), maskPath.toString(), out.toString(), "16", "100"});

        assertEquals(0, rc);
        byte[] outBytes = Files.readAllBytes(out);
        assertTrue(PngSynth.isPng(outBytes), "output must always be PNG");
        BufferedImage result = decode(outBytes);
        int insideChanged = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (inside(x, y)) {
                    if (result.getRGB(x, y) != decodedJpeg.getRGB(x, y)) insideChanged++;
                } else {
                    assertEquals(decodedJpeg.getRGB(x, y), result.getRGB(x, y));
                }
            }
        }
        assertTrue(insideChanged > 0, "target must be healed");
    }

    @Test
    void cliRegionModeHealsDisconnectedTargets() throws Exception {
        Path dir = Files.createTempDirectory("resynth-cli-regions");
        Path in = dir.resolve("in.png");
        Path maskPath = dir.resolve("mask.png");
        Path out = dir.resolve("out.png");
        int[][] rects = {{8, 8, 12, 10}, {40, 28, 14, 12}};

        BufferedImage clean = pattern();
        BufferedImage damaged = copy(clean);
        for (int[] r : rects) {
            int[] black = new int[r[2]];
            Arrays.fill(black, 0xFF000000);
            for (int y = r[1]; y < r[1] + r[3]; y++) damaged.setRGB(r[0], y, r[2], 1, black, 0, r[2]);
        }
        BufferedImage maskImage = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] mrow = new int[W];
        Arrays.fill(mrow, 0xFF000000);
        for (int y = 0; y < H; y++) maskImage.setRGB(0, y, W, 1, mrow, 0, W);
        for (int[] r : rects) {
            int[] white = new int[r[2]];
            Arrays.fill(white, 0xFFFFFFFF);
            for (int y = r[1]; y < r[1] + r[3]; y++) maskImage.setRGB(r[0], y, r[2], 1, white, 0, r[2]);
        }
        Files.write(in, png(damaged));
        Files.write(maskPath, png(maskImage));

        int rc = HealCli.run(new String[]{"--regions", "--margin", "16", "--cell", "8",
                in.toString(), maskPath.toString(), out.toString(), "16", "100", "0", "2"});

        assertEquals(0, rc);
        BufferedImage result = decode(Files.readAllBytes(out));
        int changed = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean target = false;
                for (int[] r : rects) {
                    if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) {
                        target = true;
                        break;
                    }
                }
                if (target) {
                    if (result.getRGB(x, y) != damaged.getRGB(x, y)) changed++;
                } else {
                    assertEquals(clean.getRGB(x, y), result.getRGB(x, y));
                }
            }
        }
        assertEquals(12 * 10 + 14 * 12, changed, "every target pixel must be healed");
    }

    @Test
    void cliRejectsUnreadableImageAndWritesNothing() throws Exception {
        Path dir = Files.createTempDirectory("resynth-cli-bad");
        Path in = dir.resolve("in.dat");
        Path maskPath = dir.resolve("mask.png");
        Path out = dir.resolve("out.png");
        Files.write(in, new byte[]{1, 2, 3, 4});
        Files.write(maskPath, png(mask()));

        int rc = HealCli.run(new String[]{in.toString(), maskPath.toString(), out.toString()});

        assertEquals(1, rc);
        assertFalse(Files.exists(out));
    }

    // ------------------------------------------------------------------

    private static boolean inside(int x, int y) {
        return x >= RX && x < RX + RW && y >= RY && y < RY + RH;
    }

    private static BufferedImage pattern() {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int r = (x * 2 + y) & 0xFF;
                int g = ((x / 8 + y / 3) % 2 == 0) ? 200 : 100;
                int b = (y * 3) & 0xFF;
                row[x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, W, 1, row, 0, W);
        }
        return image;
    }

    private static BufferedImage withBlackRect(BufferedImage clean) {
        BufferedImage image = copy(clean);
        int[] row = new int[RW];
        Arrays.fill(row, clean.getRGB(0, 0) & 0xFF000000); // same alpha, black
        for (int y = RY; y < RY + RH; y++) image.setRGB(RX, y, RW, 1, row, 0, RW);
        return image;
    }

    private static BufferedImage mask() {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            Arrays.fill(row, 0xFF000000);
            if (y >= RY && y < RY + RH) {
                for (int x = RX; x < RX + RW; x++) row[x] = 0xFFFFFFFF;
            }
            image.setRGB(0, y, W, 1, row, 0, W);
        }
        return image;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage image = new BufferedImage(src.getWidth(), src.getHeight(),
                src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] row = new int[src.getWidth()];
        for (int y = 0; y < src.getHeight(); y++) {
            src.getRGB(0, y, src.getWidth(), 1, row, 0, src.getWidth());
            image.setRGB(0, y, src.getWidth(), 1, row, 0, src.getWidth());
        }
        return image;
    }

    private static BufferedImage withAlpha(BufferedImage src, int alpha) {
        int width = src.getWidth(), height = src.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            src.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                row[x] = (alpha << 24) | (row[x] & 0x00FFFFFF);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    private static int countDifferent(BufferedImage a, BufferedImage b) {
        int count = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y)) count++;
        return count;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        return encode(image, "png");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "no writer for " + format);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        return image;
    }
}
