package exchange.matcher;

import exchange.carriers.EventType;

public interface Matcher {


    public void execute(long orderId, boolean isBid, long price, long quantity,
                        EventType event, long timestamp);
    public void executeNew(long orderId, boolean isBid, long price, long quantity, long timestamp);
    }