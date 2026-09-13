package exchange.books;

import exchange.carriers.MatcherFlyweightOrderRef;
import exchange.carriers.RestingOrder;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.*;

public class RedBlackTreeOrderBook implements OrderBook {

    private final TreeMap<Long, Deque<RestingOrder>> bidGroups = new TreeMap<>(Collections.reverseOrder());
    private final TreeMap<Long, Deque<RestingOrder>> askGroups = new TreeMap<>();


    private final Long2ObjectHashMap<RestingOrder> orderLookup;
    private int bidSize;
    private int askSize;

    public RedBlackTreeOrderBook(int maxOrders) {

        int targetCapacity = (int) Math.ceil(maxOrders / 0.65f);
        int initialCapacity = 1 << (32 - Integer.numberOfLeadingZeros(targetCapacity - 1));
        this.orderLookup = new Long2ObjectHashMap<>(initialCapacity, 0.65f);
    }

    // ------------------------------------------------------------------------
    // STATE MUTATORS
    // ------------------------------------------------------------------------

    // sensible default for testing
    public RedBlackTreeOrderBook() {
        this(100_000);
    }


    @Override
    public boolean add(long orderId, boolean isBid, long price, long quantity) {
        if (price <= 0 || quantity <= 0 || orderLookup.containsKey(orderId)) {
            return false; // Reject duplicate or invalid order
        }

        RestingOrder order = new RestingOrder(orderId, isBid, price, quantity);
        orderLookup.put(orderId, order);

        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        Deque<RestingOrder> level = groups.get(price);

        if (level == null) {
            level = new ArrayDeque<>();
            groups.put(price, level);
        }
        level.addLast(order);
        if (isBid) {
            bidSize++;
        } else {
            askSize++; }
        return true;
    }


    @Override
    public boolean cancel(long id) {
        RestingOrder order = orderLookup.get(id);
        if (order == null || order.getStatus() != RestingOrder.Status.ACTIVE) {
            return false;
        }

        order.setStatus(RestingOrder.Status.CANCELLED);
        orderLookup.remove(id);

        boolean isBid = order.isBid();
        if (removeFromTree(order, isBid)) {
            if (isBid) {
                bidSize--;
            } else {
                askSize--;
            }
        }


        return true;
    }




    @Override
    public long pollBest(boolean isBid) {
        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        if (groups.isEmpty()) {
            return -1L; // Sentinel value indicating empty book
        }

        Map.Entry<Long, Deque<RestingOrder>> firstEntry = groups.firstEntry();
        Deque<RestingOrder> level = firstEntry.getValue();

        RestingOrder order = level.pollFirst();
        if (order == null) {
            return -1L;
        }

        order.setStatus(RestingOrder.Status.COMPLETE);
        orderLookup.remove(order.getId());

        if (isBid) { bidSize--; } else { askSize--; }

        if (level.isEmpty()) {
            groups.pollFirstEntry();
        }

        return order.getId(); // Returns the ID of the evicted order
    }

    @Override
    public boolean reduceBestQty(boolean isBid, long quantityToReduce) {
        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        if (groups.isEmpty() || quantityToReduce <= 0) {
            return false;
        }



        RestingOrder topOrder = groups.firstEntry().getValue().peekFirst();
        if (topOrder == null || quantityToReduce > topOrder.getRemainingQuantity()) {
            return false; // Cannot reduce by more than or equal to current qty
        }
        if (quantityToReduce == topOrder.getRemainingQuantity()) {
            cancel(topOrder.getId());
            return true;
        }


        topOrder.setRemainingQuantity(topOrder.getRemainingQuantity() - quantityToReduce);
        return true;
    }

    @Override
    public boolean modifyReduceQty(long id, long quantityToReduce) {
        RestingOrder order = orderLookup.get(id);
        if (order == null || order.getStatus() != RestingOrder.Status.ACTIVE) {
            return false;
        }
        long currentQty = order.getRemainingQuantity();
        if (quantityToReduce <= 0 || quantityToReduce > currentQty) {
            return false;
        }

        if (quantityToReduce == currentQty) {
            return cancel(id); // Cancel order if remaining qty reaches zero
        }

        order.setRemainingQuantity(currentQty - quantityToReduce);
        return true;
    }

    // used for cancel
    private boolean removeFromTree(RestingOrder order, boolean isBid) {
        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        Deque<RestingOrder> level = groups.get(order.getPrice());
        if (level == null) return false;

        boolean removed = level.remove(order); // O(n) within the level, but level is typically small
        if (removed && level.isEmpty()) {
            groups.remove(order.getPrice());
        }
        return removed;
    }


    // ------------------------------------------------------------------------
    // STATE INSPECTIONS (Matcher Flyweights & Callbacks)
    // ------------------------------------------------------------------------

    @Override
    public boolean inspectBest(boolean isBid, MatcherFlyweightOrderRef outRef) {
        return isBid ? inspectBestBid(outRef) : inspectBestAsk(outRef);
    }

    @Override
    public boolean inspectBestBid(MatcherFlyweightOrderRef outRef) {
        if (bidGroups.isEmpty()) return false;

        RestingOrder order = bidGroups.firstEntry().getValue().peekFirst();
        if (order == null || order.getStatus() != RestingOrder.Status.ACTIVE) {
            return false;
        }

        outRef.id = order.getId();
        outRef.price = order.getPrice();
        outRef.qty = order.getRemainingQuantity();
        return true;
    }

    @Override
    public boolean inspectBestAsk(MatcherFlyweightOrderRef outRef) {
        if (askGroups.isEmpty()) return false;

        RestingOrder order = askGroups.firstEntry().getValue().peekFirst();
        if (order == null || order.getStatus() != RestingOrder.Status.ACTIVE) {
            return false;
        }

        outRef.id = order.getId();
        outRef.price = order.getPrice();
        outRef.qty = order.getRemainingQuantity();
        return true;
    }

    @Override
    public boolean inspectOrder(long id, MatcherFlyweightOrderRef outRef) {
        RestingOrder order = orderLookup.get(id);
        if (order == null || order.getStatus() != RestingOrder.Status.ACTIVE) {
            return false;
        }

        outRef.id = order.getId();
        outRef.price = order.getPrice();
        outRef.qty = order.getRemainingQuantity();
        return true;
    }

    // ------------------------------------------------------------------------
    // BOOK METRICS
    // ------------------------------------------------------------------------

    @Override
    public boolean isEmpty() {
        return bidSize == 0 && askSize == 0;
    }

    @Override
    public int bidSize() {
        return bidSize;
    }

    @Override
    public int askSize() {
        return askSize;
    }

    @Override
    public void clear() {
        bidGroups.clear();
        askGroups.clear();
        orderLookup.clear();
        bidSize = 0;
        askSize = 0;
    }


}