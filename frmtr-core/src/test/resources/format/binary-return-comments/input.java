class BinaryReturnComments {
    boolean matches(Report report) {
        return (
            report.message().contains("inactive") ||
            // com.example.problem.DetailException:
            // Resource 'alpha-123' is still closing. Retry only after
            // the background operation reports a final terminal state.
            report.message().contains("closing")
        );
    }
}
