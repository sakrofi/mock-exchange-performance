package exchange.books;

import exchange.carriers.RestingOrder;
import java.util.ArrayDeque;
import java.util.Deque;

public final class OrderBookPool {
    private final RestingOrder[] orderPool;
    private final int maxOrders;
    private int orderTop;

    private final Deque<RestingOrder>[] dequePool;
    private int dequeTop;
    private final int levelDepth;


    @SuppressWarnings("unchecked")
    public OrderBookPool(int maxOrders, int maxPriceLevels) {
        this.maxOrders = maxOrders;
        this.orderPool = new RestingOrder[maxOrders];
        for (int i = 0; i < maxOrders; i++) {
            this.orderPool[i] = new RestingOrder();
        }
        this.orderTop = maxOrders;

        this.dequePool = new Deque[maxPriceLevels];
        this.levelDepth = Math.max(64, maxOrders / Math.max(1, maxPriceLevels));
        for (int i = 0; i < maxPriceLevels; i++) {
            this.dequePool[i] = new ArrayDeque<>(this.levelDepth);
        }
        this.dequeTop = maxPriceLevels;
    }

    public void clear() {
        for (int i = 0; i < dequePool.length; i++) {
            if (dequePool[i] != null) {
                dequePool[i].clear();
            }
        }

        for (int i = 0; i < maxOrders; i++) {
            if (orderPool[i] != null) {
                orderPool[i].clear();
            }
        }

        this.orderTop = maxOrders;
        this.dequeTop = dequePool.length;
    }

    public RestingOrder acquireOrder(long id, boolean isBid, long price, long quantity) {
        if (orderTop <= 0) {
            return new RestingOrder(id, isBid, price, quantity);
        }
        RestingOrder order = orderPool[--orderTop];
        order.reset(id, isBid, price, quantity);
        return order;
    }

    public boolean releaseOrder(RestingOrder order) {
        if (order == null || orderTop >= orderPool.length) {
            return false;
        }
        order.clear();
        orderPool[orderTop++] = order;
        return true;
    }

    public Deque<RestingOrder> acquireDeque() {
        if (dequeTop <= 0) {
            return new ArrayDeque<>(this.levelDepth);
        }
        return dequePool[--dequeTop];
    }

    public boolean releaseDeque(Deque<RestingOrder> deque) {
        if (deque == null || dequeTop >= dequePool.length) {
            return false;
        }
        deque.clear();
        dequePool[dequeTop++] = deque;
        return true;
    }

    public int getTop() {
        return orderTop;
    }
}