class PatternMatchingCases {

    static String formatter(Object value) {
        String formatted = "unknown";
        if (
            value instanceof Integer i ||
            candidatePoint instanceof Point ||
            candidateCircle instanceof Circle c ||
            candidateSquare instanceof Square
        ) {
            formatted = String.format("int %d", i);
        } else if (value instanceof Long l) {
            formatted = String.format("long %d", l);
        } else if (value instanceof Double d) {
            formatted = String.format("double %f", d);
        } else if (value instanceof String s) {
            formatted = String.format("String %s", s);
        }
        return formatted;
    }

    public boolean test(final Object subject) {
        return (
            subject instanceof final Integer x &&
            (x == 5 || x == 6 || x == 7 || x == 8 || x == 9 || x == 10 || x == 11)
        );
    }

    void test(Buyer other) {
        return switch (other) {
            case null -> true;
            case Buyer b when this.bestPrice > b.bestPrice -> true;
            case Buyer b when this.bestPrice > b.bestPrice -> {
                return true;
            }
            case Buyer preferredBuyer when this.bestAvailableWholesalePrice > b.bestPrice -> true;
            case Buyer preferredBuyer when this.bestAvailableRetailPrice > b.bestPrice -> true;
            case Buyer b when (
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice
            ) -> true;
            case Buyer b when (
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice
            ) -> {
                return true;
            }
            case Buyer b when (
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice &&
                this.bestPrice > b.bestPrice
            ) -> {
                return true;
            }
            default -> false;
        };
    }

    int recordPatterns(MyRecord record) {
        if (candidate instanceof OrderEvent( String code)) {
        }
        switch (candidate) {
            case final String label:
                break;
        }
        return switch (record) {
            case null, default -> 0;
            case MyRecord(Region region) -> 0;
            case MyRecord(Region region, Totals totals) -> 0;
            case MyRecord(MyRecord(Region region), Totals totals) -> 0;
            case MyRecord(
                MyLongRecordTypeName(AccountBalanceSnapshot balanceSnapshot, AccountBalanceSnapshot balanceSnapshot),
                MyLongRecordTypeName(AccountBalanceSnapshot balanceSnapshot, AccountBalanceSnapshot balanceSnapshot)
            ) -> 0;
            case MyRecord(AccountBalanceSnapshot balanceSnapshot, AccountBalanceSnapshot balanceSnapshot) -> 0;
            case MyRecord(AccountBalanceSnapshot balanceSnapshot, AccountBalanceSnapshot balanceSnapshot) when (
                this.balanceSnapshot > balanceSnapshot && this.balanceSnapshot > balanceSnapshot
            ) -> 0;
            case MyRecord(AccountBalanceSnapshot balanceSnapshot, AccountBalanceSnapshot balanceSnapshot) when (
                this.balanceSnapshot > balanceSnapshot && this.balanceSnapshot > balanceSnapshot
            ) -> buildBalanceResponse(balanceSnapshot, balanceSnapshot, balanceSnapshot, balanceSnapshot);
            case Outer.Inner(String name) -> {}
            case final String label -> label;
        };
    }
}
