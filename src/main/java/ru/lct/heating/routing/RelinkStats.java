package ru.lct.heating.routing;

import java.util.concurrent.atomic.LongAdder;

/**
 * R3: счётчики и тайминги фазы relink для диагностики ({@code grid.json}).
 * Инкрементируется параллельно по деревьям, поэтому {@link LongAdder}.
 */
public class RelinkStats {

    private final LongAdder candidateNodes = new LongAdder();
    private final LongAdder candidateEdges = new LongAdder();
    private final LongAdder tpoints = new LongAdder();
    private final LongAdder validSegmentCalls = new LongAdder();
    private final LongAdder validSegmentNanos = new LongAdder();
    private final LongAdder rebuildCalls = new LongAdder();
    private final LongAdder rebuildNanos = new LongAdder();
    private final LongAdder movesAccepted = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder indexBuildNanos = new LongAdder();
    private final LongAdder kSpecialCalls = new LongAdder();
    private final LongAdder kSpecialNanos = new LongAdder();
    private final LongAdder lengthUpsized = new LongAdder();
    private final LongAdder lengthDnNanos = new LongAdder();

    public void addCandidateNodes(long value) {
        candidateNodes.add(value);
    }

    public void addCandidateEdges(long value) {
        candidateEdges.add(value);
    }

    public void addTpoints(long value) {
        tpoints.add(value);
    }

    public void addValidSegment(long nanos) {
        validSegmentCalls.increment();
        validSegmentNanos.add(nanos);
    }

    public void addRebuild(long nanos) {
        rebuildCalls.increment();
        rebuildNanos.add(nanos);
    }

    public void addMoveAccepted() {
        movesAccepted.increment();
    }

    public void addTotal(long nanos) {
        totalNanos.add(nanos);
    }

    public void addIndexBuild(long nanos) {
        indexBuildNanos.add(nanos);
    }

    public void addKSpecial(long nanos) {
        kSpecialCalls.increment();
        kSpecialNanos.add(nanos);
    }

    public void addLengthUpsized() {
        lengthUpsized.increment();
    }

    public void addLengthDn(long nanos) {
        lengthDnNanos.add(nanos);
    }

    public long getCandidateNodes() {
        return candidateNodes.sum();
    }

    public long getCandidateEdges() {
        return candidateEdges.sum();
    }

    public long getTpoints() {
        return tpoints.sum();
    }

    public long getValidSegmentCalls() {
        return validSegmentCalls.sum();
    }

    public long getValidSegmentMs() {
        return validSegmentNanos.sum() / 1_000_000L;
    }

    public long getRebuildCalls() {
        return rebuildCalls.sum();
    }

    public long getRebuildMs() {
        return rebuildNanos.sum() / 1_000_000L;
    }

    public long getMovesAccepted() {
        return movesAccepted.sum();
    }

    public long getTotalMs() {
        return totalNanos.sum() / 1_000_000L;
    }

    public long getIndexBuildMs() {
        return indexBuildNanos.sum() / 1_000_000L;
    }

    public long getKSpecialCalls() {
        return kSpecialCalls.sum();
    }

    public long getKSpecialMs() {
        return kSpecialNanos.sum() / 1_000_000L;
    }

    public long getLengthUpsizedEdges() {
        return lengthUpsized.sum();
    }

    public long getLengthMs() {
        return lengthDnNanos.sum() / 1_000_000L;
    }
}
