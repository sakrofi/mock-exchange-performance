package exchange.books;

import exchange.carriers.MatcherFlyweightOrderRef;
import org.agrona.collections.Long2LongHashMap;

import java.util.Arrays;

public class LinearSearchOrderBook implements OrderBook {

    public final long minPrice;
    public final long maxPrice;
    public final int maxOrders;
    public final int maxPriceLevels;
    private final int requiredLevels;

    // Parallel Arrays for Order Slot Storage
    private final long[] orderPrices;
    private final long[] orderQuantities;
    private final boolean[] orderIsBid;
    private final int[] nextOrder; // Reused as intrusive free list pointer when slot is free
    private final int[] prevOrder;
    private final long[] slotToId;

    // Price Level Linked Lists (Separated Bids and Asks)
    private final int[] askHeadLevel;
    private final int[] askTailLevel;
    private final int[] bidHeadLevel;
    private final int[] bidTailLevel;

    public static final int EMPTY_SLOT = -1;
    public static final long MISSING_VALUE = -1L;

    // Intrusive Free List Head Pointer
    private int freeSlotHead = EMPTY_SLOT;

    public final Long2LongHashMap idToSlotMap;
    private int nextSlot = 0;

    private int totalBidsCount = 0;
    private int totalAsksCount = 0;

    public LinearSearchOrderBook(long minPrice, long maxPrice, int maxOrders) {
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.maxOrders = maxOrders;

        long requiredLevelsLong = maxPrice - minPrice + 1;
        if (requiredLevelsLong <= 10) {
            throw new IllegalArgumentException("Price range is too small: " + requiredLevelsLong);
        }
        if (requiredLevelsLong > (1 << 30)) {
            throw new IllegalArgumentException(
                    "Price range invalid or too large: minPrice=" + minPrice + ", maxPrice=" + maxPrice);
        }
        this.requiredLevels = (int) requiredLevelsLong;
        this.maxPriceLevels = requiredLevels;

        this.orderPrices = new long[maxOrders];
        this.orderQuantities = new long[maxOrders];
        this.orderIsBid = new boolean[maxOrders];
        this.nextOrder = new int[maxOrders];
        this.prevOrder = new int[maxOrders];
        this.slotToId = new long[maxOrders];

        this.askHeadLevel = new int[maxPriceLevels];
        this.askTailLevel = new int[maxPriceLevels];
        this.bidHeadLevel = new int[maxPriceLevels];
        this.bidTailLevel = new int[maxPriceLevels];

        // Round target capacity to next power of 2 as initial capacity
        int targetCapacity = (int) Math.ceil(maxOrders / 0.65f);
        int initialCapacity = 1 << (32 - Integer.numberOfLeadingZeros(targetCapacity - 1));
        this.idToSlotMap = new Long2LongHashMap(initialCapacity, 0.65f, MISSING_VALUE);
        Arrays.fill(askHeadLevel, EMPTY_SLOT);
        Arrays.fill(askTailLevel, EMPTY_SLOT);
        Arrays.fill(bidHeadLevel, EMPTY_SLOT);
        Arrays.fill(bidTailLevel, EMPTY_SLOT);
    }

    // ------------------------------------------------------------------------
    // MUTATOR LOGIC
    // ------------------------------------------------------------------------

    @Override
    public boolean add(long id, boolean isBid, long price, long quantity) {
        if (idToSlotMap.get(id) != MISSING_VALUE) {
            throw new IllegalStateException("Duplicate order ID: " + id);
        }

        // Intrusive Allocation: Pop from free list if available; otherwise bump nextSlot
        int slot;
        if (freeSlotHead != EMPTY_SLOT) {
            slot = freeSlotHead;
            freeSlotHead = nextOrder[slot];
        } else if (nextSlot < maxOrders) {
            slot = nextSlot++;
        } else {
            throw new IllegalStateException("Order capacity exhausted.");
        }

        idToSlotMap.put(id, slot);
        slotToId[slot] = id;

        int pIdx = (int) (price - minPrice);
        if (pIdx < 0 || pIdx >= requiredLevels) {
            throw new IllegalArgumentException("Price out of range: " + price);
        }

        orderPrices[slot] = price;
        orderQuantities[slot] = quantity;
        orderIsBid[slot] = isBid;

        if (isBid) {
            totalBidsCount++;
            addToBidLevel(pIdx, slot);
        } else {
            totalAsksCount++;
            addToAskLevel(pIdx, slot);
        }
        return true;
    }

    @Override
    public boolean cancel(long id) {
        long slotLong = idToSlotMap.get(id);
        if (slotLong == MISSING_VALUE) return false;
        cancelSlot((int) slotLong);
        return true;
    }

    private void cancelSlot(int slot) {
        if (slot == EMPTY_SLOT) return;

        boolean isBid = orderIsBid[slot];
        int pIdx = (int) (orderPrices[slot] - minPrice);

        if (isBid) {
            removeFromBidLevel(pIdx, slot);
            totalBidsCount--;
        } else {
            removeFromAskLevel(pIdx, slot);
            totalAsksCount--;
        }

        // Defensive cleanup
        orderPrices[slot] = 0;
        prevOrder[slot] = EMPTY_SLOT; // Prevents stale "ghost" references in memory dumps

        idToSlotMap.remove(slotToId[slot]);

        // Push to intrusive free list
        nextOrder[slot] = freeSlotHead;
        freeSlotHead = slot;
    }


    @Override
    public long pollBest(boolean isBid) {
        int bestIdx = isBid ? findHighestBidIndex() : findLowestAskIndex();
        if (bestIdx == -1) return -1L;

        int headSlot = isBid ? bidHeadLevel[bestIdx] : askHeadLevel[bestIdx];
        if (headSlot == EMPTY_SLOT) return -1L;

        long polledId = slotToId[headSlot];
        cancelSlot(headSlot);
        return polledId;
    }

    @Override
    public boolean reduceBestQty(boolean isBid, long quantityToReduce) {
        if (quantityToReduce <= 0) return false;

        int bestPriceIdx = isBid ? findHighestBidIndex() : findLowestAskIndex();
        if (bestPriceIdx == -1) return false;

        int slot = isBid ? bidHeadLevel[bestPriceIdx] : askHeadLevel[bestPriceIdx];
        if (slot == EMPTY_SLOT) return false;

        if (quantityToReduce > orderQuantities[slot]) {
            return false;
        }
        if (quantityToReduce == orderQuantities[slot]) {
            cancelSlot(slot);
            return true;
        }

        orderQuantities[slot] -= quantityToReduce;
        return true;
    }

    @Override
    public boolean modifyReduceQty(long id, long quantityToReduce) {
        long slotLong = idToSlotMap.get(id);
        if (slotLong == MISSING_VALUE) return false;
        int slot = (int) slotLong;

        if (quantityToReduce <= 0 || quantityToReduce > orderQuantities[slot]) {
            return false;
        }
        if (quantityToReduce == orderQuantities[slot]) {
            cancelSlot(slot);
            return true;
        }

        orderQuantities[slot] -= quantityToReduce;
        return true;
    }



    public void clear() {
        this.nextSlot = 0;
        this.freeSlotHead = EMPTY_SLOT;
        this.totalBidsCount = 0;
        this.totalAsksCount = 0;

        // Reset all price level pointers (heads and tails)
        Arrays.fill(askHeadLevel, EMPTY_SLOT);
        Arrays.fill(askTailLevel, EMPTY_SLOT);
        Arrays.fill(bidHeadLevel, EMPTY_SLOT);
        Arrays.fill(bidTailLevel, EMPTY_SLOT);

        // Reset slot lookup map
        idToSlotMap.clear();
    }

    // ------------------------------------------------------------------------
    // INSPECTIONS (Matcher Flyweight Support)
    // ------------------------------------------------------------------------

    @Override
    public boolean inspectBest(boolean isBid, MatcherFlyweightOrderRef outRef) {
        return isBid ? inspectBestBid(outRef) : inspectBestAsk(outRef);
    }

    @Override
    public boolean inspectBestBid(MatcherFlyweightOrderRef outRef) {
        int bestPriceIdx = findHighestBidIndex();
        if (bestPriceIdx == -1) return false;

        int slot = bidHeadLevel[bestPriceIdx];
        if (slot == EMPTY_SLOT) return false;

        outRef.id = slotToId[slot];
        outRef.price = orderPrices[slot];
        outRef.qty = orderQuantities[slot];
        return true;
    }

    @Override
    public boolean inspectBestAsk(MatcherFlyweightOrderRef outRef) {
        int bestPriceIdx = findLowestAskIndex();
        if (bestPriceIdx == -1) return false;

        int slot = askHeadLevel[bestPriceIdx];
        if (slot == EMPTY_SLOT) return false;

        outRef.id = slotToId[slot];
        outRef.price = orderPrices[slot];
        outRef.qty = orderQuantities[slot];
        return true;
    }

    @Override
    public boolean inspectOrder(long id, MatcherFlyweightOrderRef outRef) {
        long slotLong = idToSlotMap.get(id);
        if (slotLong == MISSING_VALUE) return false;
        int slot = (int) slotLong;

        outRef.id = id;
        outRef.price = orderPrices[slot];
        outRef.qty = orderQuantities[slot];
        return true;
    }

    // ------------------------------------------------------------------------
    // BOOK METRICS
    // ------------------------------------------------------------------------

    @Override
    public boolean isEmpty() {
        return totalAsksCount == 0 && totalBidsCount == 0;
    }

    @Override
    public int bidSize() { return totalBidsCount; }

    @Override
    public int askSize() { return totalAsksCount; }

    // ------------------------------------------------------------------------
    // LINKED LIST HELPERS
    // ------------------------------------------------------------------------

    private void addToAskLevel(int pIdx, int slot) {
        if (askHeadLevel[pIdx] == EMPTY_SLOT) {
            askHeadLevel[pIdx] = slot;
            askTailLevel[pIdx] = slot;
            nextOrder[slot] = EMPTY_SLOT;
            prevOrder[slot] = EMPTY_SLOT;
        } else {
            int tail = askTailLevel[pIdx];
            nextOrder[tail] = slot;
            prevOrder[slot] = tail;
            nextOrder[slot] = EMPTY_SLOT;
            askTailLevel[pIdx] = slot;
        }
    }

    private void addToBidLevel(int pIdx, int slot) {
        if (bidHeadLevel[pIdx] == EMPTY_SLOT) {
            bidHeadLevel[pIdx] = slot;
            bidTailLevel[pIdx] = slot;
            nextOrder[slot] = EMPTY_SLOT;
            prevOrder[slot] = EMPTY_SLOT;
        } else {
            int tail = bidTailLevel[pIdx];
            nextOrder[tail] = slot;
            prevOrder[slot] = tail;
            nextOrder[slot] = EMPTY_SLOT;
            bidTailLevel[pIdx] = slot;
        }
    }

    private void removeFromAskLevel(int pIdx, int slot) {
        int prev = prevOrder[slot];
        int next = nextOrder[slot];

        if (prev != EMPTY_SLOT) nextOrder[prev] = next;
        else askHeadLevel[pIdx] = next;

        if (next != EMPTY_SLOT) prevOrder[next] = prev;
        else askTailLevel[pIdx] = prev;
    }

    private void removeFromBidLevel(int pIdx, int slot) {
        int prev = prevOrder[slot];
        int next = nextOrder[slot];

        if (prev != EMPTY_SLOT) nextOrder[prev] = next;
        else bidHeadLevel[pIdx] = next;

        if (next != EMPTY_SLOT) prevOrder[next] = prev;
        else bidTailLevel[pIdx] = prev;
    }

    // ------------------------------------------------------------------------
    // LINEAR SEARCH LOOKUPS (O(N) Scan Across Price Range)
    // ------------------------------------------------------------------------

    private int findLowestAskIndex() {
        if (totalAsksCount == 0) return -1;

        for (int i = 0; i < maxPriceLevels; i++) {
            if (askHeadLevel[i] != EMPTY_SLOT) {
                return i;
            }
        }
        return -1;
    }

    private int findHighestBidIndex() {
        if (totalBidsCount == 0) return -1;

        for (int i = maxPriceLevels - 1; i >= 0; i--) {
            if (bidHeadLevel[i] != EMPTY_SLOT) {
                return i;
            }
        }
        return -1;
    }
}