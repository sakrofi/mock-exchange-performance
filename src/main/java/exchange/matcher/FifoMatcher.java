package exchange.matcher;

import exchange.books.OrderBook;
import exchange.carriers.EventType;
import exchange.carriers.MatcherFlyweightOrderRef;
import exchange.sinks.RejectReason;
import exchange.sinks.TradeSink;

public class FifoMatcher implements Matcher {
    private final OrderBook book;
    private final TradeSink sink;
    private final MatcherFlyweightOrderRef tempOrderRef = new MatcherFlyweightOrderRef();

    public FifoMatcher(OrderBook book, TradeSink sink) {
        this.book = book;
        this.sink = sink;
    }

    @Override
    public void execute(long orderId, boolean isBid, long price, long quantity,
                        EventType event, long timestamp) {
        switch (event) {
            case NEW -> {
                executeNew(orderId, isBid, price, quantity, timestamp);
            }
            case CANCEL -> {
                boolean cancelSuccess = book.cancel(orderId);
                if (!cancelSuccess) {
                    sink.publishReject(orderId, RejectReason.CANCEL_FAILED, timestamp);
                } else {
                    sink.publishCancel(orderId, timestamp);
                }
            }
            case MODIFY_INPLACE -> {
                boolean modifySuccess = book.modifyReduceQty(orderId, quantity);
                if (!modifySuccess) {
                    sink.publishReject(orderId, RejectReason.MODIFY_FAILED, timestamp);
                } else {
                    sink.publishModifyReduction(orderId, quantity, timestamp);
                }
            }

        }
    }

    @Override
    public void executeNew(long orderId, boolean isBid, long price, long quantity, long timestamp) {
        // 1. Upfront input validation
        if (quantity <= 0 || price <= 0) {
            sink.publishReject(orderId, RejectReason.INVALID_PRICE_OR_QTY, timestamp);
            return;
        }

        long remainingQty = quantity;
        boolean oppositeSide = !isBid;

        // 2. Matching loop against opposite book
        while (remainingQty > 0) {
            if (!book.inspectBest(oppositeSide, tempOrderRef)) {
                break; // No resting liquidity
            }

            if (!crosses(isBid, price, tempOrderRef.price)) {
                break; // Limit price does not cross best resting price
            }

            long restingQty = tempOrderRef.qty;
            long executionQty = Math.min(remainingQty, restingQty);
            remainingQty -= executionQty;

            // Publish trade event to sink
            if (isBid) {
                sink.publishTrade(orderId, tempOrderRef.id, tempOrderRef.price, executionQty, timestamp);
            } else {
                sink.publishTrade(tempOrderRef.id, orderId, tempOrderRef.price, executionQty, timestamp);
            }

            // Update order book state
            if (executionQty == restingQty) {
                long id = book.pollBest(oppositeSide);
                if (id == -1L) {
                    throw new IllegalArgumentException("Failed to poll best of opposite order despite fully matched");
                }
            } else {
                boolean reducible = book.reduceBestQty(oppositeSide, executionQty);
                if (!reducible) {
                    throw new IllegalArgumentException("Failed to reduce quantity of opposing order despite partial match");
                }
                break; // remainingQty is 0
            }
        }

        // 3. Add unfilled quantity to book
        if (remainingQty > 0) {
            boolean added = book.add(orderId, isBid, price, remainingQty);
            if (!added) {
                sink.publishReject(orderId, RejectReason.DUPLICATE_ORDER_ID, timestamp);
            }
        }
    }

    private static boolean crosses(boolean incomingIsBid, long incomingPrice, long restingPrice) {
        return incomingIsBid ? incomingPrice >= restingPrice : incomingPrice <= restingPrice;
    }
}