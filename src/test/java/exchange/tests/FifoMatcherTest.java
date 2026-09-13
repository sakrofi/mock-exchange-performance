package exchange.tests;

import exchange.books.*;
import exchange.carriers.EventType;
import exchange.carriers.MatcherFlyweightOrderRef;
import exchange.matcher.FifoMatcher;
import exchange.sinks.RejectReason;
import exchange.sinks.TradeSink;
import exchange.books.BitwiseOrderbook;
import exchange.books.LinearSearchOrderBook;
import exchange.books.RedBlackTreeOrderBook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FifoMatcherTest {

    record BookCase(String name, Supplier<OrderBook> factory) {
        @Override public String toString() { return name; }
    }

    static Stream<BookCase> bookImplementations() {
        return Stream.of(
                new BookCase("Bitwise", () -> new BitwiseOrderbook(100, 1000, 10_000)),
                new BookCase("LinearArray", () -> new LinearSearchOrderBook(100, 1000, 10_000)),
                new BookCase("RedBlack", () -> new RedBlackTreeOrderBook(10_000)),
                new BookCase("PooledRedTree", () -> new PooledRedTreeOrderBook(100, 1000))
        );
    }

    private static class TradeRecord {
        long buyId, sellId, price, qty;
        TradeRecord(long buyId, long sellId, long price, long qty) {
            this.buyId = buyId; this.sellId = sellId; this.price = price; this.qty = qty;
        }
    }

    private static class MockTradeSink implements TradeSink {
        final List<TradeRecord> trades = new ArrayList<>();
        @Override
        public void publishTrade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
            trades.add(new TradeRecord(buyOrderId, sellOrderId, price, quantity));
        }

        @Override
        public void publishCancel(long orderId, long timestamp) {

        }

        @Override
        public void publishReject(long orderId, RejectReason reason, long timestamp) {

        }

        @Override
        public void publishModifyReduction(long orderId, long quantity, long timestamp) {

        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void testFullMatchOnCrossingOrder(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MockTradeSink tradeSink = new MockTradeSink();
        FifoMatcher matcher = new FifoMatcher(book, tradeSink);
        MatcherFlyweightOrderRef testRef = new MatcherFlyweightOrderRef();

        matcher.execute(100L, false, 150, 10, EventType.NEW, 1000L);
        matcher.execute(200L, true, 150, 10, EventType.NEW, 1001L);

        assertEquals(1, tradeSink.trades.size());
        TradeRecord trade = tradeSink.trades.getFirst();
        assertEquals(200L, trade.buyId);
        assertEquals(100L, trade.sellId);
        assertEquals(150, trade.price);
        assertEquals(10, trade.qty);
        assertTrue(book.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void testPartialMatchLeavesRemainderResting(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MockTradeSink tradeSink = new MockTradeSink();
        FifoMatcher matcher = new FifoMatcher(book, tradeSink);
        MatcherFlyweightOrderRef testRef = new MatcherFlyweightOrderRef();

        matcher.execute(100L, false, 150, 10, EventType.NEW, 1000L);
        matcher.execute(200L, true, 155, 15, EventType.NEW, 1001L);

        assertEquals(1, tradeSink.trades.size());
        assertEquals(10, tradeSink.trades.getFirst().qty);

        assertTrue(book.inspectBestBid(testRef));
        assertEquals(200L, testRef.id);
        assertEquals(155, testRef.price);
        assertEquals(5, testRef.qty);
        assertFalse(book.inspectBestAsk(testRef));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void testFIFOQueuePriorityMatching(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MockTradeSink tradeSink = new MockTradeSink();
        FifoMatcher matcher = new FifoMatcher(book, tradeSink);
        MatcherFlyweightOrderRef testRef = new MatcherFlyweightOrderRef();

        matcher.execute(101L, false, 150, 10, EventType.NEW, 1000L);
        matcher.execute(102L, false, 150, 10, EventType.NEW, 1001L);
        matcher.execute(200L, true, 150, 15, EventType.NEW, 1002L);

        assertEquals(2, tradeSink.trades.size());
        assertEquals(101L, tradeSink.trades.getFirst().sellId);
        assertEquals(102L, tradeSink.trades.get(1).sellId);

        assertTrue(book.inspectBestAsk(testRef));
        assertEquals(102L, testRef.id);

        assertEquals(5, testRef.qty);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void testCancel(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MockTradeSink tradeSink = new MockTradeSink();
        FifoMatcher matcher = new FifoMatcher(book, tradeSink);
        MatcherFlyweightOrderRef testRef = new MatcherFlyweightOrderRef();

        // Add two resting asks
        matcher.execute(
                101L, false, 150, 10,
                EventType.NEW, 1000L);

        matcher.execute(
                102L, false, 150, 10,
                EventType.NEW, 1001L);

        // Cancel the first order
        matcher.execute(
                101L, false, 0, 0,
                EventType.CANCEL, 1002L);

        // First order is gone
        assertFalse(book.inspectOrder(101L, testRef));

        // Second order remains
        assertTrue(book.inspectOrder(102L, testRef));
        assertEquals(102L, testRef.id);
        assertEquals(150, testRef.price);
        assertEquals(10, testRef.qty);

        assertEquals(0, tradeSink.trades.size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void testCancelUnknownOrder(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MockTradeSink tradeSink = new MockTradeSink();
        FifoMatcher matcher = new FifoMatcher(book, tradeSink);

        assertDoesNotThrow(() ->
                matcher.execute(
                        999L, false, 0, 0,
                        EventType.CANCEL, 1000L));

        assertTrue(book.isEmpty());
        assertTrue(tradeSink.trades.isEmpty());
    }


    // fix this modify one
    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void testModifyReducesQuantityInPlace(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MockTradeSink tradeSink = new MockTradeSink();
        FifoMatcher matcher = new FifoMatcher(book, tradeSink);
        MatcherFlyweightOrderRef testRef = new MatcherFlyweightOrderRef();

        // Two orders at the same price
        matcher.execute(
                101L, false, 150, 10,
                EventType.NEW, 1000L);

        matcher.execute(
                102L, false, 150, 10,
                EventType.NEW, 1001L);

        // Reduce first order from 10 -> 4
        matcher.execute(
                101L, false, 0, 6,
                EventType.MODIFY_INPLACE, 1002L);


        // First order is still there
        assertTrue(book.inspectOrder(101L, testRef));
        assertEquals(101L, testRef.id);
        assertEquals(150, testRef.price);
        assertEquals(4, testRef.qty);

        // FIFO priority is preserved
        assertTrue(book.inspectBestAsk(testRef));
        assertEquals(101L, testRef.id);
        assertEquals(4, testRef.qty);

        assertEquals(0, tradeSink.trades.size());
    }





}