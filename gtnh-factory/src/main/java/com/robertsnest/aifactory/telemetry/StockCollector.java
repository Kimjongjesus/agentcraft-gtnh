package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Keeps the {@code cap} largest stocks seen during one traversal, in bounded
 * memory, and records what the traversal covered.
 *
 * <p>
 * The old reader cut the list in upstream iteration order and then sorted the
 * prefix, so the largest stock in the network could be missing from a
 * "largest stocks" list. This keeps a min-heap of size {@code cap} and admits
 * an entry only if it beats the current smallest — an exact top-K over
 * everything the traversal budget allowed it to see. When the budget stops the
 * traversal early, the result is top-K of a prefix and the coverage says so.
 *
 * <p>
 * Pure Java so it is testable without AE2.
 */
public final class StockCollector {

    private static final Comparator<FactorySnapshot.ItemStock> SMALLEST_FIRST = new Comparator<FactorySnapshot.ItemStock>() {

        @Override
        public int compare(FactorySnapshot.ItemStock a, FactorySnapshot.ItemStock b) {
            int byQty = Long.compare(a.quantity(), b.quantity());
            return byQty != 0 ? byQty
                : b.itemId()
                    .compareTo(a.itemId());
        }
    };

    private final int cap;
    private final WorkBudget budget;
    private final PriorityQueue<FactorySnapshot.ItemStock> heap;
    private long attempted;
    private long succeeded;
    private long errors;
    private long distinct;
    private boolean budgetExhausted;

    public StockCollector(int cap, WorkBudget budget) {
        if (cap < 1) {
            throw new IllegalArgumentException("cap must be positive");
        }
        if (budget == null) {
            throw new IllegalArgumentException("budget is required");
        }
        this.cap = cap;
        this.budget = budget;
        this.heap = new PriorityQueue<FactorySnapshot.ItemStock>(cap + 1, SMALLEST_FIRST);
    }

    /**
     * Ask permission to inspect one more upstream entry.
     *
     * @return false when the traversal must stop; the coverage will record it
     */
    public boolean admit() {
        if (budgetExhausted) {
            return false;
        }
        if (!budget.tryConsume()) {
            budgetExhausted = true;
            return false;
        }
        attempted++;
        return true;
    }

    /** Record an entry that could not be described. */
    public void error() {
        errors++;
    }

    /** Offer a described entry. Kept only if it is among the largest so far. */
    public void offer(FactorySnapshot.ItemStock stock) {
        if (stock == null) {
            errors++;
            return;
        }
        succeeded++;
        distinct++;
        if (heap.size() < cap) {
            heap.add(stock);
            return;
        }
        FactorySnapshot.ItemStock smallest = heap.peek();
        if (smallest != null && SMALLEST_FIRST.compare(stock, smallest) > 0) {
            heap.poll();
            heap.add(stock);
        }
    }

    /** Largest first; ID tiebreak keeps repeat reads of an unchanged network equal. */
    public List<FactorySnapshot.ItemStock> result() {
        List<FactorySnapshot.ItemStock> out = new ArrayList<FactorySnapshot.ItemStock>(heap);
        Collections.sort(out, Collections.reverseOrder(SMALLEST_FIRST));
        return out;
    }

    public Coverage coverage() {
        return Coverage.builder()
            .status(Coverage.Status.OK)
            .attempted(attempted)
            .succeeded(succeeded)
            .errors(errors)
            .returned(heap.size())
            .distinctSeen(distinct)
            .truncated(distinct > heap.size())
            .budgetExhausted(budgetExhausted)
            .elapsedMillis(budget.elapsedMillis())
            .reason(budgetExhausted ? budget.exhaustedReason() : null)
            .settle()
            .build();
    }
}
