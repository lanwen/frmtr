class NestedBinaryPrecedenceParentheses {

    int multiplicativeOverDivideRemainder(int firstFactor, int secondFactor, int divisorValue) {
        int dividedByProduct = firstFactor / secondFactor * divisorValue;
        int scaledByQuotient = firstFactor / secondFactor / divisorValue;
        int scaledByRemainder = firstFactor % secondFactor * divisorValue;
        return dividedByProduct + scaledByQuotient + scaledByRemainder;
    }

    int additiveOverRemainder(int baseValue, int dividendValue, int modulusValue) {
        int firstSum = baseValue + dividendValue % modulusValue;
        int firstDifference = baseValue - dividendValue % modulusValue;
        return firstSum + firstDifference;
    }

    int shiftOverArithmeticAndShift(int seedValue, int addendValue, int factorValue, int shiftAmount) {
        int shiftedSum = seedValue << addendValue + factorValue;
        int shiftedProduct = seedValue << addendValue * factorValue;
        int shiftedQuotient = seedValue >> addendValue / factorValue;
        int leftShiftThenShift = addendValue + factorValue << shiftAmount;
        int chainedShift = seedValue << addendValue >> shiftAmount;
        return shiftedSum + shiftedProduct + shiftedQuotient + leftShiftThenShift + chainedShift;
    }

    int bitwiseOverShiftRelationalEquality(int maskValue, int shiftedValue, int leftValue, int rightValue) {
        int shiftedAnd = maskValue & shiftedValue << leftValue >> rightValue;
        int relationalAnd = maskValue & leftValue < rightValue;
        int equalityOr = maskValue | leftValue == rightValue;
        int chainedEqualityOr = maskValue | leftValue == rightValue == shiftedValue;
        return shiftedAnd + relationalAnd + equalityOr + chainedEqualityOr;
    }

    int bitwiseOrXorOverAnd(int flagValue, int firstOperand, int secondOperand) {
        int orOverAnd = flagValue | firstOperand & secondOperand;
        int orOverXor = flagValue | firstOperand ^ secondOperand;
        int xorOverAnd = flagValue ^ firstOperand & secondOperand;
        return orOverAnd + orOverXor + xorOverAnd;
    }

    boolean equalityOverEquality(int probeValue, int questionValue, int referenceValue) {
        return probeValue == questionValue == referenceValue;
    }
}
