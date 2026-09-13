package exchange.sinks;

public class NullTradeSink implements TradeSink {


    @Override
    public void publishTrade(long bidId, long askId, long price, long execQty, long timestamp) {}

    @Override
    public void publishCancel(long orderId, long timestamp) {}

    @Override
    public void publishReject(long orderId, RejectReason reason, long timestamp) {}


    @Override
    public void publishModifyReduction(long orderId, long quantity, long timestamp) {}

}