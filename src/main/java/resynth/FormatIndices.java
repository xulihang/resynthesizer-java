package resynth;

/** Indices of pixelels within an internal pixel. Mirrors TFormatIndices. */
public final class FormatIndices {
    public int colorEndBip;
    public int alphaBip;
    public int mapStartBip;
    public int mapEndBip;
    public int imgMatchBpp;
    public int mapMatchBpp;
    public int totalBpp;
    public boolean isAlphaTarget;
    public boolean isAlphaSource;

    /**
     * Mirrors prepareImageFormatIndices. bpp is bytes per pixel, bip is byte index in pixel.
     * Layout: [0] mask, [1,colorEndBip) color, optional alpha, [mapStartBip, mapEndBip) map.
     */
    public static FormatIndices prepare(int countColorTarget, int countColorMap,
                                        boolean isAlphaTarget, boolean isAlphaSource, boolean isMap) {
        FormatIndices indices = new FormatIndices();
        indices.imgMatchBpp = countColorTarget;
        int colorEndBip = 1 + indices.imgMatchBpp;
        indices.colorEndBip = colorEndBip;

        if (isAlphaTarget || isAlphaSource) {
            indices.alphaBip = colorEndBip;
            indices.mapStartBip = 1 + colorEndBip;
        } else {
            indices.mapStartBip = colorEndBip;
        }

        indices.mapMatchBpp = isMap ? countColorMap : 0;
        indices.mapEndBip = indices.mapStartBip + indices.mapMatchBpp;
        indices.totalBpp = indices.mapEndBip;
        indices.isAlphaTarget = isAlphaTarget;
        indices.isAlphaSource = isAlphaSource;
        return indices;
    }
}
