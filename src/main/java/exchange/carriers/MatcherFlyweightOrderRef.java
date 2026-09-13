package exchange.carriers;

public class MatcherFlyweightOrderRef {
    public long id;
    public long price;
    public long qty;

    public void set(long id, long price, long qty) {
        this.id = id;
        this.price = price;
        this.qty = qty;
    }
}
