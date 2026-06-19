public class Cast {

    void should_cast_with_single_element() {
        var convertedValue = (int) sourceValue;
        var convertedValue = (ProjectionMapper) sourceValue;
        var convertedValue = (ProjectionMapper) (sourceValue, value) -> sourceValue + value;
        var convertedValue = (VeryLongSerializableProjectionType) sourceValue;
    }

    void should_cast_with_additional_bounds() {
        consume((Readable & Auditable) candidate);
        consume((Readable & Auditable & Versioned) candidate);
        consume(
            (VeryLongReadableProjectionType
                    & VeryLongAuditableProjectionType
                    & VeryLongVersionedProjectionType
            ) candidate
        );
        consume(
            (VeryLongReadableProjectionType & VeryLongAuditableProjectionType & VeryLongVersionedProjectionType) (sourceValue, value) -> sourceValue + value
        );
    }

    void many_nested_casts() {
        (
            (Map) (
                (Map) (
                    (Map) (
                        (Map) (
                            (Map) (
                                (Map) (
                                    (Map) (
                                        (Map) (
                                            (Map) (
                                                (Map) (
                                                    (Map) (
                                                        (Map) (
                                                            (Map) (
                                                                (Map) ((Map) ((Map) map).get(1)).get(1)
                                                            ).get(1)
                                                        ).get(1)
                                                    ).get(1)
                                                ).get(1)
                                            ).get(1)
                                        ).get(1)
                                    ).get(1)
                                ).get(1)
                            ).get(1)
                        ).get(1)
                    ).get(1)
                ).get(1)
            ).get(1)
        ).get(1);
    }

    void intersectionCastExpression() {
        Object castResult1 = (Readable & Auditable) (C) o;
        Object castResult2 = (Readable & Auditable) ~0;
        Object castResult3 = (Readable & Auditable) switch (x) {
            default -> null;
        };
        Object castResult4 = (Readable & Auditable) !x;
        Object castResult5 = (Readable & Auditable) + x;
        Object castResult6 = (Readable & Auditable) - x;
    }
}
