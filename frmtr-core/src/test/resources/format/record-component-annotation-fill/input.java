class PaymentRegistry {
    record TransactionEvent(
        @NotNull @Size(max = 32) String transactionId,
        @Valid @NotNull @Pattern(regexp = "[A-Z]{2,6}$", message = "code must match the country uppercase format exactly") String code,
        @NotNull @Constraint(groups = { CreateGroup.class, UpdateGroup.class }, message = "The payment amount must satisfy all validation constraints for the payment processing pipeline") BigDecimal amount
    ) {}
}
