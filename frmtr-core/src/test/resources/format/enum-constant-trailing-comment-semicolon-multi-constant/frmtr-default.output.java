enum Plan {
    TRIAL,
    PAID; // Only two plans for now

    boolean isPaid() {
        return this == PAID;
    }
}
