package exchange.benchmark.operation_benchmarks;

import exchange.books.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;


@State(Scope.Thread)
public class PollBestBenchmark {

    @Param({"BITWISE", "LINEAR", "TREE", "POOLED"})
    public String implementation;

    @Param({"10000"})
    public int bookSize;

    @Param({"10000"})
    public int operationCount;

    @Param({"256", "512","2048", "4096"})
    public int priceRangeWidth;

    private static final long MIN_PRICE = 1;
    private static final int CLUSTER_WIDTH = 128;


    private OrderBook book;
    private boolean[] pollSides;
    private long[] reAddPrices;

    @Setup(Level.Trial)
    public void setupData() {
        pollSides = new boolean[operationCount];
        for (int i = 0; i < operationCount; i++) {
            pollSides[i] = (i & 1) == 0;
        }

        long midOffset = (priceRangeWidth - CLUSTER_WIDTH) / 2;
        reAddPrices = new long[operationCount];
        for (int i = 0; i < operationCount; i++) {
            reAddPrices[i] = MIN_PRICE + midOffset + (i % CLUSTER_WIDTH);
        }
    }

    @Setup(Level.Iteration)
    public void setupAndPopulateBook() {
        long maxPrice = MIN_PRICE + priceRangeWidth - 1;
        book = BenchmarkBookFactory.createWithRange(
                implementation, bookSize, MIN_PRICE, maxPrice
        );

        long midOffset = (priceRangeWidth - CLUSTER_WIDTH) / 2;
        for (int i = 0; i < bookSize; i++) {
            book.add(
                    i,
                    (i & 1) == 0,
                    MIN_PRICE + midOffset + (i % CLUSTER_WIDTH),
                    100
            );
        }
    }

    @Benchmark
    public void pollBestSteadyState(Blackhole bh) {
        for (int i = 0; i < operationCount; i++) {
            boolean isBid = pollSides[i];
            long polledId = book.pollBest(isBid);
            bh.consume(polledId);

            if (polledId != -1L) {
                // Reuse the exact id just removed by pollBest
                // avoids needing a separate id-generation cursor.
                book.add(polledId, isBid, reAddPrices[i], 100);
            }
        }
    }
}