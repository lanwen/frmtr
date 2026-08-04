public class OrderEventClassifier {

    interface OrderEvent {}

    record PaymentAuthorized() implements OrderEvent {}

    record ShipmentDispatched() implements OrderEvent {}

    record CustomerCancellationRequested() implements OrderEvent {}

    record RefundSettlementCompleted() implements OrderEvent {}

    record ChargebackDisputeOpened() implements OrderEvent {}

    OrderStatus classify(OrderEvent event) {
        return switch (event) {
            case PaymentAuthorized _, ShipmentDispatched _, CustomerCancellationRequested _, RefundSettlementCompleted _,
                    ChargebackDisputeOpened _ -> {
                yield OrderStatus.of(
                    "an unbreakable status literal token that clearly runs past one hundred and twenty columns wide here now"
                );
            }
            default -> OrderStatus.ignored();
        };
    }

    record OrderStatus(String label) {
        static OrderStatus of(String label) {
            return new OrderStatus(label);
        }

        static OrderStatus ignored() {
            return new OrderStatus("ignored");
        }
    }
}
