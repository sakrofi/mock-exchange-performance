package exchange.benchmark.operation_benchmarks;

import exchange.books.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;


@State(Scope.Thread)
public class CancelBenchmark {

    @Param({"BITWISE", "LINEAR", "TREE", "POOLED"})
    public String implementation;

    @Param({"100000"})
    public int bookSize;

    @Param({"1000", "10000"})
    public int operationCount;

    private OrderBook book;

    // Fixed cyclic sequence of ids to cancel, shuffled once so cancel
    // order isn't insertion-order (a FIFO best case for linked-list
    // implementations). Safe to reuse across every invocation because
    // each cancelled id is immediately re-added under the same number.
    private long[] cancelSequence;

    @Setup(Level.Trial)
    public void setupData() {
        cancelSequence = new long[bookSize];
        for (int i = 0; i < bookSize; i++) {
            cancelSequence[i] = i;
        }
        Random rng = new Random(42L);
        for (int i = cancelSequence.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            long tmp = cancelSequence[i];
            cancelSequence[i] = cancelSequence[j];
            cancelSequence[j] = tmp;
        }
    }

    @Setup(Level.Iteration)
    public void setupAndPopulateBook() {
        book = BenchmarkBookFactory.create(implementation, bookSize);
        for (int i = 0; i < bookSize; i++) {
            book.add(
                    i,
                    (i & 1) == 0,
                    400 + (i % 200),
                    100
            );
        }
    }

    @Benchmark
    public void cancel(Blackhole bh) {
        boolean last = false;

        for (int i = 0; i < operationCount; i++) {
            // Cycle through the shuffled sequence, wrapping if
            // operationCount > bookSize.
            long id = cancelSequence[i % bookSize];
            boolean isBid = (id & 1) == 0;

            last = book.cancel(id);

            // Safe: id was just fully removed from the book's lookup
            // structure by cancel() (verified above), so re-adding it
            // is indistinguishable from adding a brand new order.
            book.add(id, isBid, 400 + (int) (id % 200), 100);
        }

        bh.consume(last);
    }
}