package resynth;

/**
 * 2-D array of type, where type has size depth.
 * Mirrors Map (lib/map.h), but backed by a flat primitive array instead of a GArray.
 *
 * pixelel data (depth-indexed) lives in {@link #pix}; int maps in {@link #ints};
 * coordinate maps in {@link #xs}/{@link #ys}.
 */
public final class Map {
    public final int width;
    public final int height;
    public final int depth;

    /** Depth-interleaved bytes (Pixelel). Null for int/coord maps. */
    public final byte[] pix;
    /** int map data. Null unless it is an int map. */
    public final int[] ints;
    /** coordinate map data. Null unless it is a coord map. */
    public final int[] xs;
    public final int[] ys;

    private Map(int width, int height, int depth, byte[] pix, int[] ints, int[] xs, int[] ys) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.pix = pix;
        this.ints = ints;
        this.xs = xs;
        this.ys = ys;
    }

    public static Map newPixmap(int width, int height, int depth) {
        return new Map(width, height, depth, new byte[width * height * depth], null, null, null);
    }

    public static Map newBytemap(int width, int height) {
        return newPixmap(width, height, 1);
    }

    public static Map newIntmap(int width, int height) {
        return new Map(width, height, 4, null, new int[width * height], null, null);
    }

    public static Map newCoordmap(int width, int height) {
        int size = width * height;
        return new Map(width, height, 8, null, null, new int[size], new int[size]);
    }

    public static Map copyPixmap(Map other) {
        byte[] data = other.pix.clone();
        return new Map(other.width, other.height, other.depth, data, null, null, null);
    }

    public int pixelBase(int x, int y) {
        return (x + y * width) * depth;
    }

    // ---- bytemap helpers ----

    public void setBytemap(int value) {
        java.util.Arrays.fill(pix, (byte) value);
    }

    public void invertBytemap() {
        for (int i = 0; i < pix.length; i++) {
            pix[i] = (byte) ~pix[i];
        }
    }

    public static void interleaveMask(Map pixmap, Map mask) {
        int size = pixmap.height * pixmap.width;
        int pd = pixmap.depth;
        for (int i = 0; i < size; i++) {
            pixmap.pix[i * pd] = mask.pix[i];
        }
    }
}
