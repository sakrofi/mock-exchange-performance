package exchange.carriers;


public final class RestingOrder {

    public enum Status {
        ACTIVE,
        CANCELLED,
        COMPLETE
    }

    private long id;
    private long price;
    private long remainingQuantity;
    private boolean isBid;
    private Status status;

    public RestingOrder() {
        this.status = Status.ACTIVE;
    }

    public RestingOrder(long id, boolean isBid, long price, long quantity) {
        reset(id, isBid, price, quantity);
    }

    public void reset(long id, boolean isBid, long price, long quantity) {
        this.id = id;
        this.isBid = isBid;
        this.price = price;
        this.remainingQuantity = quantity;
        this.status = Status.ACTIVE;
    }

    // called before returning to pool to prevent stale data
    public void clear() {
        this.id = 0;
        this.price = 0;
        this.remainingQuantity = 0;
        this.isBid = false;
        this.status = Status.CANCELLED;
    }

    public long getId() { return id; }
    public long getPrice() { return price; }
    public long getRemainingQuantity() { return remainingQuantity; }
    public boolean isBid() { return isBid; }
    public Status getStatus() { return status; }

    public void setRemainingQuantity(long remainingQuantity) { this.remainingQuantity = remainingQuantity; }
    public void setStatus(Status status) { this.status = status; }
}