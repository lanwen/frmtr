class BinaryMid {

    boolean run(java.util.Set<Integer> s) {
        return s.removeIf(
            p -> p != -1
                    // keep this comment
                    && p > 0
        );
    }
}
