class DiscountCalculator {

    BigDecimal resolveDiscountRate(Order order) {
        BigDecimal discountRate = order.isLoyaltyMember() && order.hasReachedSpendThreshold()
            ? LOYALTY_RATE // reward long-term customers
            : STANDARD_RATE;
        return discountRate;
    }
}
