class WeightTotals {
    int summedFootprint() {
        return base + // header
               base + // header
               tail;
    }

    int branchedFootprint(boolean compact) {
        int chosen = compact ? base + // header
                               base + // tally
                               tail
                             : zero;
        return chosen;
    }

    int plainFootprint() {
        return base + base + tail;
    }
}
