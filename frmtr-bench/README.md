# frmtr-bench

JMH microbenchmarks for frmtr's formatter hot paths. Dev-only: depends on `:frmtr-core`, produces no runtime artifact,
and is not published. Benchmarks live in the same package as the code they measure (`dev.lanwen.frmtr.java`) so they can
exercise package-private helpers directly, against the real implementation rather than a copy.

## Running

```bash
# Run every benchmark
./gradlew :frmtr-bench:jmh

# Filter by name and add JMH flags via -PjmhArgs (space-separated)
./gradlew :frmtr-bench:jmh -PjmhArgs="RawSource -prof gc"
```

`-prof gc` reports `gc.alloc.rate.norm` (bytes allocated per operation) — the most reliable signal here, since it is
deterministic across runs while wall-clock timing carries JMH/JVM noise. Use it to compare a prototype against the
current baseline before/after a change.

## What is covered

- `RawSourceNormalizationBenchmark` — the raw-source whitespace helpers (`stripTrailingHorizontalWhitespace`,
  `normalizeWhitespace`) that JFR sampling flagged as the top formatter-owned CPU/allocation seam.

Add a benchmark class per hot path under investigation. Keep inputs realistic and sized to expose the behavior being
measured (e.g. line count for stripping, literal/comment mix for normalization).

## Interpreting results against the whole CLI

A microbenchmark isolates one method; it does not prove a user-visible speedup. Pair it with a read-only macro run
(`frmtr-cli --check` over a large external corpus) and an `allocation-profiling=maximum` JFR pass before claiming the
pipeline got faster. See `docs/proposals/performance-followups-from-jfr.md` for the measurement discipline.
