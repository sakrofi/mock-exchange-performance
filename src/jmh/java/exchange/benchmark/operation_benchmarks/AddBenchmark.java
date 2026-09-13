package exchange.benchmark.operation_benchmarks;


import exchange.books.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures cost of add() into an empty book at a given book capacity.
 */
@State(Scope.Thread)
public class AddBenchmark {

    @Param({"BITWISE", "LINEAR", "TREE", "POOLED"})
    public String implementation;

    @Param({"1000000"})
    public int operationCount;

    private OrderBook book;

    private long[] orderIds;
    private long[] prices;
    private long[] quantities;
    private boolean[] sides;

    @Setup(Level.Trial)
    public void setupData() {

        orderIds = new long[operationCount];
        prices = new long[operationCount];
        quantities = new long[operationCount];
        sides = new boolean[operationCount];

        for (int i = 0; i < operationCount; i++) {
            orderIds[i] = i;
            prices[i] = 400 + (i % 200);
            quantities[i] = 100;
            sides[i] = (i & 1) == 0;
        }
    }

    // Expensive allocation is done per iteration
    @Setup(Level.Iteration)
    public void setupBook() {
        book = BenchmarkBookFactory.create(implementation, operationCount);
    }


    @Setup(Level.Invocation)
    public void resetBook() {
        book.clear();
    }

    @Benchmark
    @OperationsPerInvocation(1000000) // matches parameter count
    public void add(Blackhole bh) {
        boolean last = false;
        for (int i = 0; i < operationCount; i++) {
            last = book.add(
                    orderIds[i],
                    sides[i],
                    prices[i],
                    quantities[i]
            );
        }
        bh.consume(last);
    }
}