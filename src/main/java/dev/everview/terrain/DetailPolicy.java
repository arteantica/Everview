package dev.everview.terrain;

/** Source resolution is a projected block size plus feature importance, never a LOD number.
 * This is a sampling target, not a proof about features below the sampled lattice. */
public final class DetailPolicy {
    private DetailPolicy(){}
    public static int spacing(double distance,double focalPixels,boolean shore,int relief) {
        double blockPixels=focalPixels/Math.max(1,distance);
        int base=blockPixels>=.23?1:blockPixels>=.115?2:4;
        if(shore || relief>=24)return Math.min(base,blockPixels>=.075?1:2);
        return base;
    }
    public static int quantum(double distance,double focalPixels,boolean shore) {
        return shore || focalPixels/Math.max(1,distance)>=.115?1:2;
    }
}
