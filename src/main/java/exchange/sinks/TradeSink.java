package exchange.sinks;

public interface TradeSink {
    void publishTrade(long bidId, long askId, long price, long execQty, long timestamp);
    void publishCancel(long orderId, long timestamp);
    void publishReject(long orderId, RejectReason reason, long timestamp);
    void publishModifyReduction(long orderId, long quantity, long timestamp);
}