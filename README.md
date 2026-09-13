# Mock Exchange Performance

A Java 25 JMH performance study comparing four order-book architectures through isolated primitive-operation benchmarks.


---

## Architecture

The benchmark harness bypasses the feed and queue so that order-book performance can be measured independently:

```text
Synthetic benchmark state
        │
        ▼
   JMH Benchmark
        │
        ▼
    OrderBook
        │
   ┌────┼────┬─────────────┐
   ▼    ▼    ▼             ▼
 Linear  RB  Pooled RB   Bitmap
```

---
---

# Order Book Implementations

The project implements four order-book architectures behind a common interface. They deliberately represent different approaches to the same problem so that their performance characteristics can be compared experimentally.

## 1. Linear Array

The linear-array implementation is the deliberate baseline.

Orders are stored in primitive arrays, providing compact contiguous storage and avoiding object-heavy data structures. Operations that require searching through the active orders use linear traversal.

### Characteristics

- Primitive array storage
- Contiguous memory access
- Minimal object allocation
- O(n) search for operations requiring traversal
- Useful baseline for measuring the benefit of indexing


---

## 2. Red-Black Tree

The baseline tree implementation uses Java's `TreeMap` to maintain sorted price levels.

Each price level contains a FIFO deque of resting orders, preserving time priority within that level.

```text
TreeMap
├── Price Level
│    └── FIFO Orders
├── Price Level
│    └── FIFO Orders
└── ...
```

The sorted tree provides O(log n) operations on price levels while the deque provides FIFO behaviour within a price level.

### Trade-offs

The implementation is convenient and provides the expected sorted-map semantics, but it introduces costs associated with a general-purpose object-oriented structure:

- Tree nodes are pointer-linked rather than stored contiguously.
- `TreeMap<Long, ...>` uses boxed `Long` keys.
- Order objects and tree/deque structures contribute to heap allocation.
- Traversing a tree can involve less predictable memory access than primitive arrays.

The benchmark results show substantially higher allocation than the primitive-array and pooled implementations

---

## 3.  Pooled Red-Black Tree

The pooled implementation retains the tree-based price-level structure but reuses order objects through an object pool.

The goal is to determine how much of the baseline tree's allocation cost comes from repeatedly creating and discarding order objects.

This isolates an important optimisation independently from the underlying tree structure:

```text
TreeMap
   │
   ├── Price Levels
   │
   └── Pooled Order Objects
             ▲
             │
        Object Pool
```

Pooling substantially reduces allocation compared with the baseline `TreeMap` implementation, although it does not eliminate allocation associated with the surrounding tree structure.

---

## 4. Bitmap

The bitmap implementation replaces tree traversal for price-level discovery with a hierarchical bitmap over the supported price domain.

The bitmap tracks which price positions contain active orders. A hierarchical level allows groups of empty positions to be skipped rather than scanning the entire price range.

The structure is particularly useful when best-price discovery must operate over a bounded but potentially wide price domain.

The implementation uses primitive arrays for order storage and a primitive order-ID-to-slot mapping, keeping the hot data structures compact and avoiding the object allocation associated with the tree implementations.

---

# # Benchmark Methodology

The performance study uses **JMH** to measure individual order-book operations in isolation rather than replaying a mixed event stream.

This separates the cost of the underlying data structure from parser, replay, and workload-generation effects.

## Operations

The benchmark suite measures four primitive operations:

- `ADD` — insert a new resting order
- `CANCEL` — remove an order by ID
- `MODIFY IN PLACE` — reduce the quantity of an existing order without changing its price-level position
- `POLL BEST` — remove the best bid or ask

The same operation is benchmarked against all four order-book implementations.

## Benchmark State

Each benchmark establishes a controlled initial book state before measurement.

The benchmarks use fixed book sizes and deterministic setup data so that implementations are compared under equivalent conditions.

`POLL BEST` is measured in steady state: after an order is removed, the same order ID is re-added. This prevents the book from draining during the benchmark and keeps the operation under a consistent load.

For `POLL BEST`, the supported price-domain size is also varied to show how the implementations respond as the searchable price range changes.

## Measurements

Two benchmark passes are used:

**Pass 1 — Throughput and system behaviour**

- JMH throughput
- GC allocation
- Linux `perf` hardware counters


**Pass 2 — Latency distribution**
- JMH `SampleTime`
- sampled latency percentiles


The benchmark runs use CPU affinity on Linux to reduce variability caused by thread migration.

A fixed JMH configuration and deterministic benchmark state are used for reproducibility.

## What This Measures

The benchmarks are intended to answer specific data-structure questions:

- How expensive is inserting an order?

- How efficiently can an order be located and cancelled by ID?

- How much does an in-place quantity update depend on the underlying representation?

- How does best-price discovery behave as the supported price domain grows?


The results therefore describe the behaviour of these implementations under the tested conditions rather than establishing a universally optimal order-book design.---

---
# Results

The benchmarks compare the four order-book representations using isolated primitive operations:

- `ADD`
- `CANCEL`
- `MODIFY IN PLACE`
- `POLL BEST`

### Representative Results
| Operation            | Representative observation                                              |
| -------------------- | ----------------------------------------------------------------------- |
| **ADD**              | All implementations were approximately ~0.1 µs/op; no meaningful winner |
| **CANCEL**           | BITWISE and LINEAR were substantially faster than TREE and POOLED       |
| **MODIFY IN PLACE**  | Implementations were relatively close                                   |
| **POLL BEST @ 256**  | BITWISE ~457 µs, TREE/POOLED ~553 µs, LINEAR ~712 µs                    |
| **POLL BEST @ 4096** | BITWISE ~449 µs, TREE ~563 µs, POOLED ~576 µs, LINEAR ~4.53 ms          |

Full JMH output is retained in the benchmark result CSVs.

### Key Findings

**1. There is no universal winner.**
The relative performance depends on the operation and the characteristics of the price domain.

**2. Primitive indexed structures performed particularly well for ID-based cancellation.**
BITWISE and LINEAR were substantially cheaper than the tree-based implementations for `CANCEL` in the tested configurations.

**3. In-place modification showed relatively little architectural separation.**
Since the operation locates an existing order and updates its quantity without changing its price-level position, the underlying price-level representation has less influence on the result.

**4. Price-domain size strongly affected the linear implementation's `POLL BEST` cost.**
With a 10,000-order book, LINEAR increased from roughly **712 µs at 256 price levels** to **4.53 ms at 4096 price levels**. BITWISE remained around **450 µs**, while TREE remained aroud **560 µs**.

This reflects the implementation design: LINEAR searches the bounded price-level array directly, so widening the searchable range increases the amount of work required to locate the best price.

**5. Object pooling reduced allocation, but allocation was not directly equivalent to latency.**
The pooled tree reduced allocation compared with the ordinary tree implementation, but this did not consistently make it the lowest-latency implementation.


---

# Known Limitations

- Benchmarks use three JMH forks.
- Hardware-counter results are collected with Linux `perf` and should be treated as supporting measurements rather than standalone performance rankings.
- CPU pinning currently relies on Linux tooling.
- Workloads are synthetic and do not represent the complete distribution of real exchange order flow.

---

# How to Run

```bash
# Run correctness tests
# Run correctness tests
./gradlew test

# Run benchmarks without Linux perf
./gradlew runBenchmarks --args="standard"

# Run benchmarks with Linux perf hardware counters
./gradlew runBenchmarks --args="linux"
```

Note - The `linux` mode requires access to the Linux `perf` subsystem.