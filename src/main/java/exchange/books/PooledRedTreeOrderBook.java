package exchange.books;

import exchange.carriers.MatcherFlyweightOrderRef;
import exchange.carriers.RestingOrder;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;

public class PooledRedTreeOrderBook implements OrderBook {

    private final TreeMap<Long, Deque<RestingOrder>> bidGroups = new TreeMap<>(Collections.reverseOrder());
    private final TreeMap<Long, Deque<RestingOrder>> askGroups = new TreeMap<>();
    private final Long2ObjectHashMap<RestingOrder> orderLookup;

    private final OrderBookPool pool;
    private int bidSize;
    private int askSize;

    public PooledRedTreeOrderBook(int maxOrders, int maxPriceLevels) {
        this.pool = new OrderBookPool(maxOrders, maxPriceLevels);

        int targetCapacity = (int) Math.ceil(maxOrders / 0.65f);
        int initialCapacity = 1 << (32 - Integer.numberOfLeadingZeros(targetCapacity - 1));
        this.orderLookup = new Long2ObjectHashMap<>(initialCapacity, 0.65f);
    }

    public PooledRedTreeOrderBook() {
        this(100_000, 2_000);
    }

    // ------------------------------------------------------------------------
    // STATE MUTATORS
    // ------------------------------------------------------------------------


    public int getPoolSize(){
        return pool.getTop();
    }


    @Override
    public boolean add(long orderId, boolean isBid, long price, long quantity) {
        if (price <= 0 || quantity <= 0 || orderLookup.containsKey(orderId)) {
            return false;
        }

        RestingOrder order = pool.acquireOrder(orderId, isBid, price, quantity);
        orderLookup.put(orderId, order);

        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        Deque<RestingOrder> level = groups.get(price);
        if (level == null) {
            level = pool.acquireDeque();
            groups.put(price, level);
        }
        level.addLast(order);

        if (isBid) { bidSize++; } else { askSize++; }
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
            if (isBid) { bidSize--; } else { askSize--; }
        }

        // Return the order object back to the pool
        pool.releaseOrder(order);
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
            return cancel(id);
        }

        order.setRemainingQuantity(currentQty - quantityToReduce);
        return true;
    }


    @Override
    public long pollBest(boolean isBid) {
        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        if (groups.isEmpty()) {
            return -1L;
        }

        Map.Entry<Long, Deque<RestingOrder>> firstEntry = groups.firstEntry();
        Deque<RestingOrder> level = firstEntry.getValue();

        RestingOrder order = level.pollFirst();
        if (order == null) {
            return -1L;
        }

        long orderId = order.getId();
        order.setStatus(RestingOrder.Status.COMPLETE);
        orderLookup.remove(orderId);
        pool.releaseOrder(order);

        if (isBid) { bidSize--; } else { askSize--; }

        if (level.isEmpty()) {
            releaseEmptyLevel(groups);
        }

        return orderId;
    }

    @Override
    public boolean reduceBestQty(boolean isBid, long quantityToReduce) {
        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        if (groups.isEmpty() || quantityToReduce <= 0) {
            return false;
        }

        Deque<RestingOrder> level = groups.firstEntry().getValue();
        RestingOrder topOrder = level.peekFirst();

        if (topOrder == null || quantityToReduce > topOrder.getRemainingQuantity()) {
            return false;
        }
        if (quantityToReduce == topOrder.getRemainingQuantity()) {
            cancel(topOrder.getId());
            return true;
        }

        topOrder.setRemainingQuantity(topOrder.getRemainingQuantity() - quantityToReduce);
        return true;
    }

    @Override
    public void clear() {
        bidGroups.clear();
        askGroups.clear();
        orderLookup.clear();
        bidSize = 0;
        askSize = 0;
        pool.clear();
    }

    private void releaseEmptyLevel(TreeMap<Long, Deque<RestingOrder>> groups) {
        Map.Entry<Long, Deque<RestingOrder>> entry = groups.pollFirstEntry();
        if (entry != null) {
            pool.releaseDeque(entry.getValue());
        }
    }

    private boolean removeFromTree(RestingOrder order, boolean isBid) {
        TreeMap<Long, Deque<RestingOrder>> groups = isBid ? bidGroups : askGroups;
        Deque<RestingOrder> level = groups.get(order.getPrice());
        if (level == null) return false;

        boolean removed = level.remove(order);
        if (removed && level.isEmpty()) {
            Deque<RestingOrder> emptyLevel = groups.remove(order.getPrice());
            if (emptyLevel != null) {
                pool.releaseDeque(emptyLevel);
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------------
    // STATE INSPECTIONS
    // ------------------------------------------------------------------------

    @Override
    public boolean inspectBest(boolean isBid, MatcherFlyweightOrderRef outRef) {
        return isBid ? inspectBestBid(outRef) : inspectBestAsk(outRef);
    }

    @Override
    public boolean inspectBestBid(MatcherFlyweightOrderRef outRef) {
        return inspectBestSide(bidGroups, outRef);
    }

    @Override
    public boolean inspectBestAsk(MatcherFlyweightOrderRef outRef) {
        return inspectBestSide(askGroups, outRef);
    }

    private boolean inspectBestSide(TreeMap<Long, Deque<RestingOrder>> groups, MatcherFlyweightOrderRef outRef) {
        if (groups.isEmpty()) return false;

        RestingOrder order = groups.firstEntry().getValue().peekFirst();
        if (order == null) return false;

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
}