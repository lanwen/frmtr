class MethodCallBinaryArgumentSample {

    static class RouteConversionFailure extends RuntimeException {

        RouteConversionFailure(Object value, Class<?> requiredType, Throwable cause) {
            super(
                "Failed to adapt route payload of type '"
                    + TypeNames.describe(value)
                    + "'"
                    + (requiredType != null ? " to target type '" + TypeNames.name(requiredType) + "'" : "")
                    + (cause != null ? "; " + cause.getMessage() : ""),
                cause
            );
        }
    }

    void configure(OpaqueContainer<?> container) {
        container.withCommand("run")
                .withEnv(
                    "JAVA_TOOL_OPTIONS",
                    "-Dalpha.beta.gamma.trustStore=/var/lib/example/client-truststore.p12 "
                        + "-Dalpha.beta.gamma.trustStorePassword=changeit "
                        + "-Dalpha.beta.gamma.trustStoreType=PKCS12"
                );
    }

    void compareScriptBlocks() {
        List<String> expected = Arrays.asList(
            "CREATE ROUTINE arrange_items() BEGIN\n"
                + "\n"
                + "    BEGIN\n"
                + "      SELECT *\n"
                + "      FROM shipment_queue;\n"
                + "      SELECT 1\n"
                + "      FROM control_dual;\n"
                + "    END;\n"
                + "\n"
                + "    BEGIN\n"
                + "      select * from shipment_queue;\n"
                + "    END;\n"
                + "\n"
                + "    -- comments stay in the generated body\n"
                + "\n"
                + "    /* including block\n"
                + "       comments\n"
                + "     */\n"
                + "\n"
                + "    select \"or if BEGIN appears inside a literal?\";\n"
                + "\n"
                + "  END"
        );
        List<String> single = Collections.singletonList(
            "BEGIN\n"
                + "    scan_loop: LOOP\n"
                + "        FETCH row_cursor;\n"
                + "        IF item_missing THEN LEAVE scan_loop; END IF;\n"
                + "        continue_processing;\n"
                + "    END LOOP;\n"
                + "END"
        );
    }

    interface Patterns {
        Pattern ROUTE_MATCHING_PATTERN = Pattern.compile(
            "pkg:demo:"
                + "(?<categoryType>[a-z0-9]+)"
                + "(:(?<releaseTag>[^:]+))?"
                + "://"
                + "(?<clusterHostValue>[^?]+)"
                + "(?<queryParameters>\\?.*)?"
        );

        Pattern WORKER_MATCHING_PATTERN = Pattern.compile(
            "pkg:demo:"
                + "(?<categoryType>[a-z]+)"
                + "(:(?<releaseTag>(?!thin).+))?:thin:(//)?"
                + "("
                + "(?<operator>[^:"
                + "?^/]+)/(?<secret>[^?^/]+)"
                + ")?"
                + "@"
                + "(?<clusterHostValue>[^?]+)"
                + "(?<queryParameters>\\?.*)?"
        );
    }

    void queryRows(QueryStatement statement) throws Exception {
        try (QueryResult resultSet = statement.executeQuery(
                ""
                    + "SELECT item_key, element_value "
                    + "FROM warehouse.small.item "
                    + "JOIN memory.default.table_with_array twa ON item_key = twa.id "
                    + "LEFT JOIN UNNEST(sample_array) a(element_value) ON true "
                    + "ORDER BY element_value OFFSET 1 FETCH NEXT 3 ROWS WITH TIES "
        )) {
            resultSet.next();
        }
    }
}
