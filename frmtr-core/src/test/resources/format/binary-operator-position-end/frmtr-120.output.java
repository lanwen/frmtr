public class BinaryOperations {

    public void binaryOperation() {
        int alpha = (left) << right;
        boolean beta = (left) < right;
    }

    @Annotation(
        "This operation with two very long string should break" +
            "This operation with two very long string should break" +
            "in a very nice way"
    )
    public String binaryOperationThatShouldBreak() {
        System.out.println(
            "This operation with two very long string should break" +
                "This operation with two very long string should break" +
                "in a very nice way"
        );
        return (
            "This operation with two very long string should break" +
            "This operation with two very long string should break" +
            "in a very nice way"
        );
    }

    @Annotation("This operation should" + "not break")
    public String binaryOperationThatShouldNotBreak() {
        System.out.println("This operation should" + "not break");
        return "This operation should" + "not break";
    }

    public boolean binaryOperationWithComments() {
        boolean a =
            one ||
            two >> 1 || // one
            // two
            // three
            // five
            // four
            three;

        boolean b =
            one ||
            two >> 1 || // one
            // two
            // three
            three;

        boolean c =
            one ||
            two >> 1 || // one
            // two
            // three
            three;

        return a || b || c;
    }

    public void method() {
        new ProfileRequest(
            profileId,
            accountId,
            "customer profile payload with long details",
            "more details"
        ).submit(10);
        submitProfile(profileId, accountId, "some very long customer activity notes", "more details").submit(10);
    }

    public void binaryExpressionWithCast() {
        double availabilityRate = (double) successfulCount / (successfulCount + failureCount);
        availabilityRate = (double) successfulCount / (successfulCount + failureCount);
    }

    void declarationVsAssignment() {
        var lineLengthInAssignmentMoreThanPrintWidth =
            "1234567890" +
            "1234567890" +
            "1234567890" +
            "1234567890" +
            "1234567890" +
            "1234567890";
        lineLengthInAssignmentMoreThanPrintWidth =
            "1234567890" +
            "1234567890" +
            "1234567890" +
            "1234567890" +
            "1234567890" +
            "1234567890";

        accumulator += leftOperand + rightOperand + carryOperand + bonusOperand + deltaOperand + scaleOperand;
        accumulator %= leftOperand + rightOperand + carryOperand + bonusOperand + deltaOperand + scaleOperand;
        accumulator <<= leftOperand + rightOperand + carryOperand + bonusOperand + deltaOperand + scaleOperand;
        accumulator &= leftOperand + rightOperand + carryOperand + bonusOperand + deltaOperand + scaleOperand;

        var decisionValue = primaryReady || backupReady ? approvedScore + reviewScore : deniedScore + retryScore;
        decisionValue = primaryReady || backupReady ? approvedScore + reviewScore : deniedScore + retryScore;

        var methodResult = RequestCalculator.combine(requestValue, fallbackValue, cachedValue, derivedValue);
        methodResult = RequestCalculator.combine(requestValue, fallbackValue, cachedValue, derivedValue);

        var adjustedResult = RequestCalculator.combine(requestValue, fallbackValue, cachedValue, derivedValue) + 0;
        adjustedResult = RequestCalculator.combine(requestValue, fallbackValue, cachedValue, derivedValue) + 0;

        var constructedResult = new RequestCalculator(requestValue, fallbackValue, cachedValue, derivedValue);
        constructedResult = new RequestCalculator(requestValue, fallbackValue, cachedValue, derivedValue);
    }

    void parentheses() {
        var result = (a + b) >>> 1;
        var sizeIndex = ((index - 1) >>> level) & MASK;
        var from = offset > left ? 0 : (left - offset) >> level;
        var to = (right - offset) >> (level + 1);
        if (rawIndex < 1 << (list._level + SHIFT)) {
        }
        var res = size < SIZE ? 0 : ((size - 1) >>> SHIFT) << SHIFT;
        sign = (1 - 2 * b[3]) >> 7;
        exponent = ((b[3] << 1) & 0xff) | (b[2] >> (7 - 127));
        mantissa = (b[2] & (0x7f << 16)) | (b[1] << 8) | b[0];

        ignored = ((2 / 3) * 10) / 2 + 2;
        ignored = (2 * 3 * 10) / 2 + 2;
        var rotateX = (RANGE / rect.height) * refY - (RANGE / 2) * getXMultiplication(rect.width);
        var rotateY = (RANGE / rect.width) * refX - (RANGE / 2) * getYMultiplication(rect.width);

        ignored = (a % 10) - 5;
        ignored = a - (10 % 5);
        ignored = (a * b) % 10;
        ignored = (a % b) * 10;
        ignored = a % 10 > 5;
        ignored = a % 10 == 0;

        ignored = ((1 << 2) >>> 3) >> 4;
        ignored = ((1 >>> 2) >> 3) << 4;

        ignored = 1 << (2 + 3);
        ignored = 1 >> (2 - 3);
        ignored = 1 >>> (2 * 3);
        ignored = (1 / 2) << 3;
        ignored = (1 + 2) >> 3;
        ignored = (1 - 2) >>> 3;

        ignored = (x == y) == z;
        ignored = (x != y) == z;
        ignored = (x == y) != z;
        ignored = (x != y) != z;

        ignored = 1 & (2 == 3);

        if (
            (customerSubtotal + shippingFee == invoiceSubtotal + handlingFee &&
                discountTotal + taxEstimate == paymentTotal + refundTotal) ||
            requiresReview
        ) {
        }

        if (((((a * b + c) << d < e == f) & g) ^ h) | i && j || k && l | (m ^ (n & (o != p > q >> (r - s / t))))) {
        }

        if (
            (customerSubtotal + shippingFee == invoiceSubtotal + handlingFee && discountTotal + taxEstimate == paymentTotal + refundTotal) ||
            (warehouseStock + reservedStock == expectedStock + damagedStock && supplierCredit + carrierCredit == manualCredit + promoCredit) ||
            (currentBalance + pendingCharge == targetBalance + postedCharge && retainedBudget + plannedBudget == actualBudget + frozenBudget)
        ) {
        }
    }

    void instanceOf() {
        var hasSnapshot =
            hasSnapshot &&
            AccountStore.find(cachedCustomerRecordIdentifier) instanceof CustomerAccountSnapshot snapshot &&
            snapshot.isOpen();

        var snapshotMatch =
            AccountStore.find(cachedCustomerRecordIdentifier) instanceof CustomerAccountSnapshot snapshot &&
            snapshot.isOpen();

        ignored = e instanceof @Ann final E baz;
        ignored = f instanceof final @Ann E qux;
    }

    void unaryExpression() {
        int a = -x + y;
        int b = ~x & y;
        boolean c = !x && !y;
        int d = -(x + y);
        Object e = (int) -x;
    }
}
