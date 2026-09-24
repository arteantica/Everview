package dev.everview.client;

/** Render-thread wall time; background tasks and client-tick integration are separate. */
public final class EverviewFrameProfiler {
    public static long visibility, residency, ownership, commands, upload, integration, submission;
    public static long prepareTotal, drawTotal, hud;
    public static volatile long selectionWorker, ownershipWorker, packingWorker, generationIntegration;
    public static int ownershipBuilds, deferredUploads;
    private static final double[] totalHistory = new double[240];
    private static int cursor, samples;

    private EverviewFrameProfiler() {}

    public static void begin() {
        visibility = residency = ownership = commands = upload = integration = submission = 0;
        prepareTotal = drawTotal = 0;
        ownershipBuilds = deferredUploads = 0;
    }

    public static void finishDraw(long start) {
        drawTotal = System.nanoTime() - start;
        totalHistory[cursor++ % totalHistory.length] = ms(prepareTotal + drawTotal);
        samples = Math.min(samples + 1, totalHistory.length);
    }

    public static double meanMs() {
        double total = 0;
        for (int i = 0; i < samples; i++) total += totalHistory[i];
        return samples == 0 ? 0 : total / samples;
    }

    public static double ms(long nanos) { return nanos / 1_000_000.0; }
}
