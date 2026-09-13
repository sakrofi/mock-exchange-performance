package exchange.sinks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ListTradeSink implements TradeSink {

    // Common marker interface for all sink events
    public sealed interface SinkEvent permits TradeEvent, CancelEvent, ModifyReductionEvent, RejectEvent {
        long timestamp();
    }

    public record TradeEvent(long bidId, long askId, long price, long execQty, long timestamp) implements SinkEvent {}
    public record CancelEvent(long orderId, long timestamp) implements SinkEvent {}
    public record ModifyReductionEvent(long orderId, long quantity, long timestamp) implements SinkEvent {}
    public record RejectEvent(long orderId, RejectReason reason, long timestamp) implements SinkEvent {}

    // Chronological list of events
    private final List<SinkEvent> events = new ArrayList<>();

    private final List<TradeEvent> trades = new ArrayList<>();
    private final List<CancelEvent> cancels = new ArrayList<>();
    private final List<ModifyReductionEvent> modifies = new ArrayList<>();
    private final List<RejectEvent> rejects = new ArrayList<>();

    @Override
    public void publishTrade(long bidId, long askId, long price, long execQty, long timestamp) {
        TradeEvent event = new TradeEvent(bidId, askId, price, execQty, timestamp);
        events.add(event);
        trades.add(event);
    }

    @Override
    public void publishCancel(long orderId, long timestamp) {
        CancelEvent event = new CancelEvent(orderId, timestamp);
        events.add(event);
        cancels.add(event);
    }

    @Override
    public void publishModifyReduction(long orderId, long quantity, long timestamp) {
        ModifyReductionEvent event = new ModifyReductionEvent(orderId, quantity, timestamp);
        events.add(event);
        modifies.add(event);
    }

    @Override
    public void publishReject(long orderId, RejectReason reason, long timestamp) {
        RejectEvent event = new RejectEvent(orderId, reason, timestamp);
        events.add(event);
        rejects.add(event);
    }



    public List<SinkEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
    public List<TradeEvent> getTrades() {
        return Collections.unmodifiableList(trades);
    }
    public List<CancelEvent> getCancels() {
        return Collections.unmodifiableList(cancels);
    }
    public List<ModifyReductionEvent> getModifies() {
        return Collections.unmodifiableList(modifies);
    }
    public List<RejectEvent> getRejects() {
        return Collections.unmodifiableList(rejects);
    }

    public void clear() {
        events.clear();
        trades.clear();
        cancels.clear();
        modifies.clear();
        rejects.clear();
    }
}