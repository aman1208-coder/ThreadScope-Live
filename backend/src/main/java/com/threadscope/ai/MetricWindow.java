package com.threadscope.ai;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * MetricWindow
 *
 * A fixed-capacity sliding window of double values used to detect
 * trends (rising, falling, stable) across consecutive metric snapshots.
 *
 * Used by the AI engine to distinguish transient spikes from sustained
 * conditions that warrant a real diagnosis.
 *
 * Example usage:
 *   window.add(latencyMs);
 *   boolean rising = window.isTrendingUp(0.2);  // 20% average increase
 */
public class MetricWindow {

    private final int capacity;
    private final Deque<Double> values;

    public MetricWindow(int capacity) {
        this.capacity = capacity;
        this.values   = new ArrayDeque<>(capacity);
    }

    public synchronized void add(double value) {
        if (values.size() >= capacity) values.pollFirst();
        values.addLast(value);
    }

    public synchronized int size()   { return values.size(); }
    public synchronized boolean isFull() { return values.size() == capacity; }

    public synchronized double latest() {
        return values.isEmpty() ? 0.0 : values.peekLast();
    }

    public synchronized double average() {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public synchronized double max() {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    public synchronized double min() {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
    }

    /**
     * Returns true if the average of the second half of the window is
     * at least (1 + thresholdFraction) times the average of the first half.
     * Requires at least 6 data points to avoid false positives.
     */
    public synchronized boolean isTrendingUp(double thresholdFraction) {
        if (values.size() < 6) return false;
        double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();
        int mid = arr.length / 2;
        // FIX: compute both halves in a single pass — O(N) not O(2N)
        double sum1 = 0, sum2 = 0;
        for (int i = 0; i < arr.length; i++) { if (i < mid) sum1 += arr[i]; else sum2 += arr[i]; }
        double firstHalf = sum1 / mid;
        double secondHalf = sum2 / (arr.length - mid);
        return firstHalf > 0 && secondHalf > firstHalf * (1.0 + thresholdFraction);
    }

    /**
     * Returns true if the average of the second half is at least
     * (1 + thresholdFraction) LESS than the first half.
     */
    public synchronized boolean isTrendingDown(double thresholdFraction) {
        if (values.size() < 6) return false;
        double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();
        int mid = arr.length / 2;
        double sum1 = 0, sum2 = 0;
        for (int i = 0; i < arr.length; i++) { if (i < mid) sum1 += arr[i]; else sum2 += arr[i]; }
        double firstHalf = sum1 / mid;
        double secondHalf = sum2 / (arr.length - mid);
        return secondHalf > 0 && firstHalf > secondHalf * (1.0 + thresholdFraction);
    }

    /**
     * Returns the fraction of values that exceed the given threshold.
     */
    public synchronized double fractionAbove(double threshold) {
        if (values.isEmpty()) return 0.0;
        long count = values.stream().filter(v -> v > threshold).count();
        return (double) count / values.size();
    }

    /**
     * Returns how consistently sustained the window is above a threshold.
     * 1.0 = all values above threshold; 0.0 = none above.
     */
    public synchronized double sustainedFraction(double threshold) {
        // Inlined — do not call fractionAbove() here to avoid redundant locking complexity
        if (values.isEmpty()) return 0.0;
        long count = values.stream().filter(v -> v > threshold).count();
        return (double) count / values.size();
    }

    /** Clamp a double confidence contribution to [0.0, 1.0] */
    public static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
