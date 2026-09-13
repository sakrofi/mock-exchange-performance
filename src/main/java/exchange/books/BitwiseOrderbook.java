package exchange.books;

import exchange.carriers.MatcherFlyweightOrderRef;
import org.agrona.collections.Long2LongHashMap;

import java.util.Arrays;

public class BitwiseOrderbook implements OrderBook {
    public final long minPrice;
    public final long maxPrice;
    public final int maxOrders;
    public final int maxPriceLevels;
    private final int requiredLevels;

    // Parallel Arrays (Order Data) - Memory contiguous
    private final long[] orderPrices;
    private final long[] orderQuantities;
    private final boolean[] orderIsBid;
    private final int[] nextOrder; // Reused as intrusive free list pointer when slot is free
    private final int[] prevOrder;
    private final long[] slotToId;

    // Price Level Linked Lists (Int pointers)
    private final int[] askHeadLevel;
    private final int[] askTailLevel;
    private final int[] bidHeadLevel;
    private final int[] bidTailLevel;

    // Hierarchical Bitmaps (L0 -> L1)
    private final long[] askBitMapL0;
    private final long[] askBitMapL1;
    private final long[] bidBitMapL0;
    private final long[] bidBitMapL1;



    public final Long2LongHashMap idToSlotMap;
    public final int EMPTY_SLOT = -1;
    public final int MISSING_VALUE = -1;

    // Intrusive Free List Head Pointer
    private int freeSlotHead = EMPTY_SLOT;

    public int nextSlot = 0;
    public int totalBidsCount = 0;
    public int totalAsksCount = 0;

    public BitwiseOrderbook(long minPrice, long maxPrice, int maxOrders) {
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.maxOrders = maxOrders;

        long requiredLevelsLong = maxPrice - minPrice + 1;
        if (requiredLevelsLong <= 0 || requiredLevelsLong > (1 << 30)) {
            throw new IllegalArgumentException(
                    "Price range invalid or too large: minPrice=" + minPrice + ", maxPrice=" + maxPrice);
        }
        this.requiredLevels = (int) requiredLevelsLong;
        this.maxPriceLevels = findNextPowerOfTwo(requiredLevels);

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


        // Calculate raw L0 words needed
        int l0WordsRaw = (maxPriceLevels + 63) >>> 6;

        // Pad L0 words up to the nearest multiple of 64 so any L1 bit (0..63) is safe
        int l0WordsPadded = (l0WordsRaw + 63) & ~63;

        // L1 words required to cover l0WordsPadded
        int l1Words = (l0WordsPadded + 63) >>> 6;

        this.askBitMapL0 = new long[l0WordsPadded];
        this.askBitMapL1 = new long[l1Words];
        this.bidBitMapL0 = new long[l0WordsPadded];
        this.bidBitMapL1 = new long[l1Words];




        // Round target capacity to next power of 2 as initial capacity
        int targetCapacity = (int) Math.ceil(maxOrders / 0.65f);
        int initialCapacity = 1 << (32 - Integer.numberOfLeadingZeros(targetCapacity - 1));
        this.idToSlotMap = new Long2LongHashMap(initialCapacity, 0.65f, MISSING_VALUE);


        Arrays.fill(askHeadLevel, EMPTY_SLOT);
        Arrays.fill(askTailLevel, EMPTY_SLOT);
        Arrays.fill(bidHeadLevel, EMPTY_SLOT);
        Arrays.fill(bidTailLevel, EMPTY_SLOT);
    }

    private static int findNextPowerOfTwo(int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

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

        int priceIndex = (int) (price - minPrice);
        if (priceIndex < 0 || priceIndex >= requiredLevels) {
            throw new IllegalArgumentException("Price out of range: " + price);
        }

        orderPrices[slot] = price;
        orderQuantities[slot] = quantity;
        orderIsBid[slot] = isBid;

        if (isBid) {
            totalBidsCount++;
            addToBidLevel(priceIndex, slot);
        } else {
            totalAsksCount++;
            addToAskLevel(priceIndex, slot);
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
        prevOrder[slot] = EMPTY_SLOT;

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
    public boolean inspectOrder(long id, MatcherFlyweightOrderRef outRef) {
        long slotLong = idToSlotMap.get(id);
        if (slotLong == MISSING_VALUE) return false;
        int slot = (int) slotLong;

        outRef.id = id;
        outRef.price = orderPrices[slot];
        outRef.qty = orderQuantities[slot];
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

    public void clear() {
        this.nextSlot = 0;
        this.freeSlotHead = EMPTY_SLOT;
        this.totalBidsCount = 0;
        this.totalAsksCount = 0;

        Arrays.fill(askBitMapL0, 0L);
        Arrays.fill(askBitMapL1, 0L);
        Arrays.fill(bidBitMapL0, 0L);
        Arrays.fill(bidBitMapL1, 0L);

        Arrays.fill(askHeadLevel, EMPTY_SLOT);
        Arrays.fill(askTailLevel, EMPTY_SLOT);
        Arrays.fill(bidHeadLevel, EMPTY_SLOT);
        Arrays.fill(bidTailLevel, EMPTY_SLOT);

        idToSlotMap.clear();
    }

    @Override
    public boolean isEmpty() {
        return totalAsksCount == 0 && totalBidsCount == 0;
    }

    @Override
    public int bidSize() {
        return totalBidsCount;
    }

    @Override
    public int askSize() {
        return totalAsksCount;
    }

    private void addToAskLevel(int priceIndex, int slot) {
        if (askHeadLevel[priceIndex] == EMPTY_SLOT) {
            askHeadLevel[priceIndex] = slot;
            askTailLevel[priceIndex] = slot;
            nextOrder[slot] = EMPTY_SLOT;
            prevOrder[slot] = EMPTY_SLOT;
            markAskBitActive(priceIndex);
        } else {
            int tail = askTailLevel[priceIndex];
            nextOrder[tail] = slot;
            prevOrder[slot] = tail;
            nextOrder[slot] = EMPTY_SLOT;
            askTailLevel[priceIndex] = slot;
        }
    }

    private void addToBidLevel(int priceIndex, int slot) {
        if (bidHeadLevel[priceIndex] == EMPTY_SLOT) {
            bidHeadLevel[priceIndex] = slot;
            bidTailLevel[priceIndex] = slot;
            nextOrder[slot] = EMPTY_SLOT;
            prevOrder[slot] = EMPTY_SLOT;
            markBidBitActive(priceIndex);
        } else {
            int tail = bidTailLevel[priceIndex];
            nextOrder[tail] = slot;
            prevOrder[slot] = tail;
            nextOrder[slot] = EMPTY_SLOT;
            bidTailLevel[priceIndex] = slot;
        }
    }

    private void removeFromAskLevel(int priceIndex, int slot) {
        int prev = prevOrder[slot];
        int next = nextOrder[slot];

        if (prev != EMPTY_SLOT) nextOrder[prev] = next;
        else askHeadLevel[priceIndex] = next;

        if (next != EMPTY_SLOT) prevOrder[next] = prev;
        else askTailLevel[priceIndex] = prev;

        if (askHeadLevel[priceIndex] == EMPTY_SLOT) {
            markAskBitInactive(priceIndex);
        }
    }

    private void removeFromBidLevel(int priceIndex, int slot) {
        int prev = prevOrder[slot];
        int next = nextOrder[slot];

        if (prev != EMPTY_SLOT) nextOrder[prev] = next;
        else bidHeadLevel[priceIndex] = next;

        if (next != EMPTY_SLOT) prevOrder[next] = prev;
        else bidTailLevel[priceIndex] = prev;

        if (bidHeadLevel[priceIndex] == EMPTY_SLOT) {
            markBidBitInactive(priceIndex);
        }
    }

    private void markAskBitActive(int pIdx) {
        int wordL0 = pIdx >>> 6;
        askBitMapL0[wordL0] |= (1L << (pIdx & 63));
        int wordL1 = wordL0 >>> 6;
        askBitMapL1[wordL1] |= (1L << (wordL0 & 63));
    }

    private void markAskBitInactive(int pIdx) {
        int wordL0 = pIdx >>> 6;
        askBitMapL0[wordL0] &= ~(1L << (pIdx & 63));
        if (askBitMapL0[wordL0] == 0L) {
            int wordL1 = wordL0 >>> 6;
            askBitMapL1[wordL1] &= ~(1L << (wordL0 & 63));
        }
    }

    private void markBidBitActive(int pIdx) {
        int wordL0 = pIdx >>> 6;
        bidBitMapL0[wordL0] |= (1L << (pIdx & 63));
        int wordL1 = wordL0 >>> 6;
        bidBitMapL1[wordL1] |= (1L << (wordL0 & 63));
    }

    private void markBidBitInactive(int pIdx) {
        int wordL0 = pIdx >>> 6;
        bidBitMapL0[wordL0] &= ~(1L << (pIdx & 63));
        if (bidBitMapL0[wordL0] == 0L) {
            int wordL1 = wordL0 >>> 6;
            bidBitMapL1[wordL1] &= ~(1L << (wordL0 & 63));
        }
    }

    private int findLowestAskIndex() {
        for (int i = 0; i < askBitMapL1.length; i++) {
            if (askBitMapL1[i] != 0L) {
                int bitL1 = Long.numberOfTrailingZeros(askBitMapL1[i]);
                int wordL0 = (i << 6) | bitL1;
                int bitL0 = Long.numberOfTrailingZeros(askBitMapL0[wordL0]);
                return (wordL0 << 6) | bitL0;
            }
        }
        return -1;
    }

    private int findHighestBidIndex() {
        for (int i = bidBitMapL1.length - 1; i >= 0; i--) {
            if (bidBitMapL1[i] != 0L) {
                int bitL1 = 63 - Long.numberOfLeadingZeros(bidBitMapL1[i]);
                int wordL0 = (i << 6) | bitL1;
                int bitL0 = 63 - Long.numberOfLeadingZeros(bidBitMapL0[wordL0]);
                return (wordL0 << 6) | bitL0;
            }
        }
        return -1;
    }
}