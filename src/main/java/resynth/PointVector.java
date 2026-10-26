package resynth;

/** Flat vector of points (x,y interleaved). Replaces pointVector (GArray of Coordinates). */
public final class PointVector {
    public int[] data;
    public int len;

    public PointVector(int capacity) {
        if (capacity < 1) capacity = 1;
        data = new int[capacity * 2];
    }

    public void add(int x, int y) {
        if ((len + 1) * 2 > data.length) {
            data = java.util.Arrays.copyOf(data, data.length * 2);
        }
        data[len * 2] = x;
        data[len * 2 + 1] = y;
        len++;
    }

    public int x(int i) { return data[i * 2]; }
    public int y(int i) { return data[i * 2 + 1]; }

    public void set(int i, int x, int y) {
        data[i * 2] = x;
        data[i * 2 + 1] = y;
    }

    public void swap(int i, int j) {
        int ix = data[i * 2], iy = data[i * 2 + 1];
        data[i * 2] = data[j * 2];
        data[i * 2 + 1] = data[j * 2 + 1];
        data[j * 2] = ix;
        data[j * 2 + 1] = iy;
    }

    public PointVector copy() {
        PointVector v = new PointVector(Math.max(1, len));
        System.arraycopy(data, 0, v.data, 0, len * 2);
        v.len = len;
        return v;
    }
}
