package exchange.books;

import exchange.carriers.MatcherFlyweightOrderRef;

public interface OrderBook {



    boolean add(long orderId, boolean isBid, long price, long quantity);
    boolean cancel(long orderId);
    boolean modifyReduceQty(long id, long quantityToReduce);
    long pollBest(boolean isBid);
    boolean reduceBestQty(boolean isBid, long quantityToReduce);
    void clear();

    // Flyweight inspection (zero-allocation)
    boolean inspectOrder(long id, MatcherFlyweightOrderRef outRef);
    boolean inspectBest(boolean isBid, MatcherFlyweightOrderRef outRef);
    boolean inspectBestBid(MatcherFlyweightOrderRef outRef);
    boolean inspectBestAsk(MatcherFlyweightOrderRef outRef);

    // Book metrics
    boolean isEmpty();
    int bidSize();
    int askSize();
}