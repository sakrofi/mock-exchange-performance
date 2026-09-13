package exchange.benchmark.operation_benchmarks;

import exchange.books.*;

final class BenchmarkBookFactory {

    private static final long DEFAULT_MIN_PRICE = 1;
    private static final long DEFAULT_MAX_PRICE = 1 << 16;

    private BenchmarkBookFactory() {
    }


    static OrderBook create(String implementation, int maxOrders) {
        return createWithRange(implementation, maxOrders, DEFAULT_MIN_PRICE, DEFAULT_MAX_PRICE);
    }


    static OrderBook createWithRange(
            String implementation, int maxOrders, long minPrice, long maxPrice) {

        return switch (implementation) {

            case "BITWISE" ->
                    new BitwiseOrderbook(minPrice, maxPrice, maxOrders);

            case "LINEAR" ->
                    new LinearSearchOrderBook(minPrice, maxPrice, maxOrders);

            case "TREE" ->
                    new RedBlackTreeOrderBook(maxOrders);

            case "POOLED" ->
                    new PooledRedTreeOrderBook(
                            maxOrders,
                            (int) (maxPrice - minPrice + 1)
                    );

            default ->
                    throw new IllegalArgumentException(
                            "Unknown implementation: " + implementation
                    );
        };
    }
}