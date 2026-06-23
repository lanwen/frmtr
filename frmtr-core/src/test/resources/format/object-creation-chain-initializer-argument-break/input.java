class ObjectCreationChainInitializerArgumentBreak {

    public static final WaitProbe WAIT_FOR_SIGNAL = new LogMessageWaitProbe()
        .withRegEx(String.format(".*Service enabled on .*:%d\n", DEFAULT_SIGNAL_PORT));

    boolean runQuery(DataSource dataSource, String statement) {
        boolean matched = new RecordRunner(dataSource)
            .query(statement, results -> {
                int total = 0;
                while (results.next()) {
                    total = total + results.getInt("amount");
                }
                return total > 0;
            });
        return matched;
    }
}
