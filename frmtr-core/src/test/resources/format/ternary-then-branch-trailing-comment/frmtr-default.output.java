class Sample {

    int singleLineThen(boolean flag, int base, int offset, int fallback) {
        int n = flag
            ? base + offset // tuned
            : fallback;
        return n;
    }

    int multiLineThen(
            boolean flag,
            int firstOperandValue,
            int secondOperandValue,
            int thirdOperandValue,
            int fallback
    ) {
        int total = flag
            ? firstOperandValue + secondOperandValue + thirdOperandValue // tail
            : fallback;
        return total;
    }

    int noComments(boolean flag, int firstOperandValue, int secondOperandValue, int thirdOperandValue, int fallback) {
        int total = flag ? firstOperandValue + secondOperandValue + thirdOperandValue : fallback;
        return total;
    }
}
