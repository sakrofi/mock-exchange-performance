package exchange.benchmark.operation_benchmarks;

import exchange.books.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;


@State(Scope.Thread)
public class ModifyInplaceBenchmark {

    private static final long SHUFFLE_SEED = 43L;
    private static final long REDUCTION = 10;
    private static final long INITIAL_QUANTITY = 10_000_000L;

    @Param({"BITWISE", "LINEAR", "TREE", "POOLED"})
    public String implementation;

    @Param({"100000"})
    public int bookSize;

    @Param({"1000", "10000"})
    public int operationCount;

    private OrderBook book;

    private long[] modifyIds;

    @Setup(Level.Trial)
    public void setupData() {
        modifyIds = new long[operationCount];
        Random rng = new Random(SHUFFLE_SEED);
        for (int i = 0; i < operationCount; i++) {
            modifyIds[i] = rng.nextInt(bookSize);
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
                    INITIAL_QUANTITY
            );
        }
    }

    @Benchmark
    public void modify(Blackhole bh) {
        boolean last = false;
        for (long id : modifyIds) {
            last = book.modifyReduceQty(id, REDUCTION);
        }
        bh.consume(last);
    }
}
