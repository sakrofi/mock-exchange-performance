package exchange.carriers;


@FunctionalInterface
public interface OrderConsumer {
    void accept(long orderID, Side side, long price, long quantity);
}
