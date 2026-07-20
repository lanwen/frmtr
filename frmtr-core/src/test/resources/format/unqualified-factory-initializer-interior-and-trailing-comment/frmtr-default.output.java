class ScheduledReportStatementFactory {

    private void initSelectAccountStatement() {
        Select accountSelection = composeProjection(
            reportTable,
            new String[] {
                accountKeyColumn,
                correlationIdColumn,
            }, // account
            // and
            // correlation
            // columns
            partitionColumns,
            partitionColumns.length - 1
        ); // narrowed to the active partition
    }
}
