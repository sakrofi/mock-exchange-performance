package exchange.tests;

import exchange.books.*;
import exchange.carriers.MatcherFlyweightOrderRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookIsolatedTest {

    record BookCase(String name, Supplier<OrderBook> factory) {
        @Override public String toString() { return name; }
    }

    static Stream<BookCase> bookImplementations() {
        return Stream.of(
                new BookCase("Bitwise", () -> new BitwiseOrderbook(1, 1000, 10_000)),
                new BookCase("LinearArray", () -> new LinearSearchOrderBook(1, 1000, 10_000)),
                new BookCase("RedBlack", RedBlackTreeOrderBook::new),
                new BookCase("PooledRedTree", () -> new PooledRedTreeOrderBook(100, 1000))
        );
    }

    // ------------------------------------------------------------------------
    // ADD + INSPECT
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void addedOrderIsInspectable(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10);

        assertTrue(book.inspectOrder(1L, ref));
        assertEquals(1L, ref.id);
        assertEquals(150, ref.price);
        assertEquals(10, ref.qty);
        assertEquals(1, book.bidSize());
        assertEquals(0, book.askSize());
        assertFalse(book.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void inspectOrderReturnsFalseForUnknownId(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        assertFalse(book.inspectOrder(999L, ref));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void bestBidAndAskTrackCorrectSides(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 100, 5);   // bid
        book.add(2L, true, 105, 5);   // better bid
        book.add(3L, false, 200, 5);  // ask
        book.add(4L, false, 195, 5);  // better ask

        assertTrue(book.inspectBestBid(ref));
        assertEquals(2L, ref.id);
        assertEquals(105, ref.price);

        assertTrue(book.inspectBestAsk(ref));
        assertEquals(4L, ref.id);
        assertEquals(195, ref.price);
    }

    // ------------------------------------------------------------------------
    // CANCEL
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void cancelRemovesOrderFromInspection(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10);
        assertTrue(book.cancel(1L));

        assertFalse(book.inspectOrder(1L, ref));
        assertEquals(0, book.bidSize());
        assertTrue(book.isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void cancelOfUnknownIdReturnsFalse(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        assertFalse(book.cancel(1L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void doubleCancelReturnsFalseSecondTime(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        book.add(1L, true, 150, 10);

        assertTrue(book.cancel(1L));
        assertFalse(book.cancel(1L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void cancelOfNonHeadOrderLeavesHeadIntact(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10); // head
        book.add(2L, true, 150, 10); // behind it, same price level

        assertTrue(book.cancel(2L));

        assertTrue(book.inspectBestBid(ref));
        assertEquals(1L, ref.id); // order 1 must still be the visible best
    }


    // ------------------------------------------------------------------------
    // REDUCE QTY (in-place modify)
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void reduceQtyDecreaseSucceedsInPlace(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10);
        assertTrue(book.modifyReduceQty(1L, 6)); // reduce quantity by 6

        assertTrue(book.inspectOrder(1L, ref));
        assertEquals(4, ref.qty); // 10 - 6 = 4 remaining
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void reduceQtyInvalidAmountIsRejected(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        book.add(1L, true, 150, 10);

        assertFalse(book.modifyReduceQty(1L, -5)); // negative reduction (attempted increase) rejected
        assertFalse(book.modifyReduceQty(1L, 0));  // zero reduction rejected
        assertFalse(book.modifyReduceQty(1L, 15)); // reduction > current quantity rejected (must cancel instead)
        assertTrue(book.modifyReduceQty(1L, 10));// cancels order returns true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void reduceQtyPreservesQueuePriority(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10); //
        book.add(2L, true, 150, 10); //

        assertTrue(book.modifyReduceQty(1L, 7));

        assertTrue(book.inspectBestBid(ref));
        assertEquals(1L, ref.id);
        assertEquals(3, ref.qty);
    }

    // ------------------------------------------------------------------------
    // POLL BEST
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void pollBestRemovesHeadAndAdvancesToNext(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10);
        book.add(2L, true, 140, 10);

        book.pollBest(true);

        assertTrue(book.inspectBestBid(ref));
        assertEquals(2L, ref.id);
        assertEquals(1, book.bidSize());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void pollBestOnEmptySideIsNoOp(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        assertDoesNotThrow(() -> book.pollBest(true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void pollBestOnlyAffectsRequestedSide(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10);  // bid
        book.add(2L, false, 200, 10); // ask

        book.pollBest(true); // poll bid side only

        assertFalse(book.inspectBestBid(ref)); // bid gone
        assertTrue(book.inspectBestAsk(ref));   // ask untouched
        assertEquals(2L, ref.id);
    }

    // ------------------------------------------------------------------------
    // REDUCE BEST QTY (fill simulation without a matcher)
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void reduceBestQtyPartialLeavesOrderResting(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, false, 150, 10); // ask
        book.reduceBestQty(false, 4);

        assertTrue(book.inspectBestAsk(ref));
        assertEquals(6, ref.qty);
        assertEquals(1, book.askSize());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void reduceBestQtyFullyConsumingRemovesOrder(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();

        book.add(1L, false, 150, 10);
        book.reduceBestQty(false, 10);

        assertEquals(0, book.askSize());
        assertTrue(book.isEmpty());
    }

    // ------------------------------------------------------------------------
    // BID/ASK ISOLATION
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void bidAndAskAtSamePriceLevelDoNotInterfere(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();
        MatcherFlyweightOrderRef ref = new MatcherFlyweightOrderRef();

        book.add(1L, true, 150, 10);  // bid at 150
        book.add(2L, false, 150, 5);  // ask at 150

        assertTrue(book.inspectBestBid(ref));
        assertEquals(1L, ref.id);
        assertEquals(10, ref.qty);

        assertTrue(book.inspectBestAsk(ref));
        assertEquals(2L, ref.id);
        assertEquals(5, ref.qty);

        assertTrue(book.cancel(1L));

        assertFalse(book.inspectBestBid(ref));  // bid side now empty
        assertTrue(book.inspectBestAsk(ref));   // ask untouched
        assertEquals(2L, ref.id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bookImplementations")
    void clearRemovesAllOrders(BookCase bookCase) {
        OrderBook book = bookCase.factory().get();

        book.add(1L, true, 150, 10);
        book.add(2L, false, 160, 10);

        book.clear();

        assertTrue(book.isEmpty());
        assertEquals(0, book.bidSize());
        assertEquals(0, book.askSize());
        assertFalse(book.cancel(1L));
        assertFalse(book.cancel(2L));
    }

}