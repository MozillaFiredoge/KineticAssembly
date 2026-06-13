package com.firedoge.kineticassembly.minecraft.assembly;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.firedoge.kineticassembly.KineticAssembly;

public final class AssemblyProfiler {
    private static final int MAX_REPORT_SECTIONS = 40;
    private static final Map<String, Sample> SAMPLES = new LinkedHashMap<>();
    private static boolean enabled;
    private static int ticksRemaining;
    private static int ticksSampled;
    private static long windowStartedNanos;
    private static long collisionTargetCalls;
    private static long forcedCollisionTargetCalls;
    private static long collisionTargetCandidates;
    private static long collisionTargets;
    private static long collisionTargetBlocks;
    private static long localBlockQueries;
    private static long localBlockQueryCells;
    private static long localBlockQueryCandidates;
    private static long localBlockQueryResults;
    private static long collisionPasses;
    private static long collisionPassTargets;
    private static long collisionPassBlocks;
    private static long collisionPassSubsteps;

    private AssemblyProfiler() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int startWindow(int ticks) {
        int safeTicks = Math.max(1, ticks);
        reset();
        enabled = true;
        ticksRemaining = safeTicks;
        windowStartedNanos = System.nanoTime();
        return safeTicks;
    }

    public static String stopWindow() {
        if (!enabled && ticksSampled == 0) {
            return "Assembly profiler is not running";
        }
        String summary = summary();
        enabled = false;
        ticksRemaining = 0;
        return summary;
    }

    public static String status() {
        if (!enabled && ticksSampled == 0) {
            return "Assembly profiler is idle";
        }
        return summary();
    }

    public static void beginTick() {
        if (!enabled) {
            return;
        }
        ticksSampled++;
    }

    public static void endTick() {
        if (!enabled) {
            return;
        }
        ticksRemaining--;
        if (ticksRemaining > 0) {
            return;
        }
        String summary = summary();
        enabled = false;
        ticksRemaining = 0;
        KineticAssembly.LOGGER.info(summary);
    }

    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void record(String section, long startedNanos) {
        if (!enabled || startedNanos == 0L) {
            return;
        }
        Objects.requireNonNull(section, "section");
        long elapsed = System.nanoTime() - startedNanos;
        SAMPLES.computeIfAbsent(section, Sample::new).add(elapsed);
    }

    public static void recordCollisionTargets(int candidates, int targets, int blocks, boolean forced) {
        if (!enabled) {
            return;
        }
        collisionTargetCalls++;
        if (forced) {
            forcedCollisionTargetCalls++;
        }
        collisionTargetCandidates += Math.max(0, candidates);
        collisionTargets += Math.max(0, targets);
        collisionTargetBlocks += Math.max(0, blocks);
    }

    public static void recordLocalBlockQuery(int cells, int candidates, int results) {
        if (!enabled) {
            return;
        }
        localBlockQueries++;
        localBlockQueryCells += Math.max(0, cells);
        localBlockQueryCandidates += Math.max(0, candidates);
        localBlockQueryResults += Math.max(0, results);
    }

    public static void recordCollisionPass(int targets, int blocks, int substeps) {
        if (!enabled) {
            return;
        }
        collisionPasses++;
        collisionPassTargets += Math.max(0, targets);
        collisionPassBlocks += Math.max(0, blocks);
        collisionPassSubsteps += Math.max(0, substeps);
    }

    private static void reset() {
        SAMPLES.clear();
        ticksRemaining = 0;
        ticksSampled = 0;
        windowStartedNanos = 0L;
        collisionTargetCalls = 0L;
        forcedCollisionTargetCalls = 0L;
        collisionTargetCandidates = 0L;
        collisionTargets = 0L;
        collisionTargetBlocks = 0L;
        localBlockQueries = 0L;
        localBlockQueryCells = 0L;
        localBlockQueryCandidates = 0L;
        localBlockQueryResults = 0L;
        collisionPasses = 0L;
        collisionPassTargets = 0L;
        collisionPassBlocks = 0L;
        collisionPassSubsteps = 0L;
    }

    private static String summary() {
        int sampled = Math.max(1, ticksSampled);
        double elapsedMillis = windowStartedNanos == 0L ? 0.0D : nanosToMillis(System.nanoTime() - windowStartedNanos);
        StringBuilder builder = new StringBuilder(1024);
        builder.append("[kinetic_assembly-assembly-profile] ticks=")
                .append(ticksSampled)
                .append(" remaining=")
                .append(Math.max(0, ticksRemaining))
                .append(" elapsedMs=")
                .append(format(elapsedMillis))
                .append('\n');

        builder.append("sections:");
        SAMPLES.values().stream()
                .sorted(Comparator.comparingLong(Sample::totalNanos).reversed())
                .limit(MAX_REPORT_SECTIONS)
                .forEach(sample -> builder.append('\n')
                        .append("  ")
                        .append(sample.name())
                        .append(" count=")
                        .append(sample.count())
                        .append(" totalMs=")
                        .append(format(nanosToMillis(sample.totalNanos())))
                        .append(" avgMs=")
                        .append(format(nanosToMillis(sample.totalNanos()) / Math.max(1L, sample.count())))
                        .append(" maxMs=")
                        .append(format(nanosToMillis(sample.maxNanos())))
                        .append(" perTickMs=")
                        .append(format(nanosToMillis(sample.totalNanos()) / sampled)));

        builder.append('\n')
                .append("collisionTargets calls=")
                .append(collisionTargetCalls)
                .append(" forced=")
                .append(forcedCollisionTargetCalls)
                .append(" avgCandidates=")
                .append(format(average(collisionTargetCandidates, collisionTargetCalls)))
                .append(" avgTargets=")
                .append(format(average(collisionTargets, collisionTargetCalls)))
                .append(" avgBlocks=")
                .append(format(average(collisionTargetBlocks, collisionTargetCalls)));

        builder.append('\n')
                .append("localBlockQueries calls=")
                .append(localBlockQueries)
                .append(" avgCells=")
                .append(format(average(localBlockQueryCells, localBlockQueries)))
                .append(" avgCandidates=")
                .append(format(average(localBlockQueryCandidates, localBlockQueries)))
                .append(" avgResults=")
                .append(format(average(localBlockQueryResults, localBlockQueries)));

        builder.append('\n')
                .append("collisionPasses calls=")
                .append(collisionPasses)
                .append(" avgTargets=")
                .append(format(average(collisionPassTargets, collisionPasses)))
                .append(" avgBlocks=")
                .append(format(average(collisionPassBlocks, collisionPasses)))
                .append(" avgSubsteps=")
                .append(format(average(collisionPassSubsteps, collisionPasses)));
        return builder.toString();
    }

    private static double average(long total, long count) {
        return count <= 0L ? 0.0D : (double) total / (double) count;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class Sample {
        private final String name;
        private long count;
        private long totalNanos;
        private long maxNanos;

        private Sample(String name) {
            this.name = name;
        }

        private void add(long nanos) {
            count++;
            totalNanos += Math.max(0L, nanos);
            maxNanos = Math.max(maxNanos, nanos);
        }

        private String name() {
            return name;
        }

        private long count() {
            return count;
        }

        private long totalNanos() {
            return totalNanos;
        }

        private long maxNanos() {
            return maxNanos;
        }
    }
}
