package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** In-memory FIFO store capped at {@link ShadowRankingConstants#EVALUATION_STORE_CAPACITY}. */
@Component
public class BoundedShadowEvaluationStore implements ShadowEvaluationStore {

    private final ConcurrentLinkedQueue<ShadowEvaluationRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    public BoundedShadowEvaluationStore() {
        this(ShadowRankingConstants.EVALUATION_STORE_CAPACITY);
    }

    BoundedShadowEvaluationStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    @Override
    public void add(ShadowEvaluationRecord record) {
        Objects.requireNonNull(record, "record");
        queue.offer(record);
        int current = size.incrementAndGet();
        while (current > capacity) {
            if (queue.poll() != null) {
                current = size.decrementAndGet();
            } else {
                break;
            }
        }
    }

    @Override
    public List<ShadowEvaluationRecord> snapshot() {
        return List.copyOf(new ArrayList<>(queue));
    }

    @Override
    public int size() {
        return size.get();
    }

    @Override
    public void clear() {
        queue.clear();
        size.set(0);
    }
}
