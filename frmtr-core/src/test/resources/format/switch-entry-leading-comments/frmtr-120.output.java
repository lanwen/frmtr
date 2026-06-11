class SwitchEntryLeadingCommentsSample {

    void route(Decision decision) {
        switch (decision) {
            // keep first detail
            // keep second detail
            // keep third detail
            // keep final detail
            case ACCEPTED -> {
                record(decision);
            }
            default -> {
                ignore(decision);
            }
        }
    }
}
