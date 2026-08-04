public class OrderEventRouter {

    interface OrderEvent {}

    record PaymentAuthorized() implements OrderEvent {}

    record ShipmentDispatched() implements OrderEvent {}

    record CustomerCancellationRequested() implements OrderEvent {}

    record RefundSettlementCompleted() implements OrderEvent {}

    record ChargebackDisputeOpened() implements OrderEvent {}

    record InventoryReserved() implements OrderEvent {}

    record FraudReviewRequested() implements OrderEvent {}

    record BackorderThresholdExceeded() implements OrderEvent {}

    record SupplierRestockConfirmed() implements OrderEvent {}

    record WarehouseTransferInitiated() implements OrderEvent {}

    OrderStatus route(OrderEvent event) {
        return switch (event) {
            case PaymentAuthorized _, ShipmentDispatched _, CustomerCancellationRequested _, RefundSettlementCompleted _,
                    ChargebackDisputeOpened _ -> {
                yield OrderStatus.progressed();
            }
            case InventoryReserved _, FraudReviewRequested _, BackorderThresholdExceeded _, SupplierRestockConfirmed _,
                    WarehouseTransferInitiated _ -> OrderStatus.unchanged();
            default -> OrderStatus.ignored();
        };
    }

    record OrderStatus(String label) {
        static OrderStatus progressed() {
            return new OrderStatus("progressed");
        }

        static OrderStatus unchanged() {
            return new OrderStatus("unchanged");
        }

        static OrderStatus ignored() {
            return new OrderStatus("ignored");
        }
    }
}
