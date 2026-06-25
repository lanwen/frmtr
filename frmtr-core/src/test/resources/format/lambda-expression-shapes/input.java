package sample;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

final class LambdaShapeSurvey {

    Supplier<String> zeroParam() {
        return () -> computeTheRatherLongDefaultGreetingMessageForTheCurrentlyAuthenticatedVisitorAccount();
    }

    Function<String, String> singleUnparenthesized() {
        return value -> value.trim();
    }

    Function<String, String> typedParam() {
        return (String value) -> value.strip();
    }

    BiFunction<Integer, Integer, Integer> blockBody() {
        return (left, right) -> {
            int sum = left + right;
            return sum;
        };
    }

    Function<Order, Receipt> overflowingExpressionBody() {
        return order -> receiptFactory.buildDetailedReceiptForCompletedOrder(order, order.lineItems(), order.totals());
    }
}
