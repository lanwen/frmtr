package dev.example.fulfillment;

import reactor.core.publisher.Mono;

class OrderFulfillmentPipeline {

    private OrderRepository orderRepository;

    private InventoryService inventoryService;

    private PaymentGateway paymentGateway;

    private ShippingService shippingService;

    private NotificationService customerNotificationService;

    private AuditService fulfillmentAuditService;

    Mono<FulfillmentResult> fulfillOrder(String orderReference) {
        return orderRepository.findByReference(orderReference)
                .flatMap(pendingOrder -> inventoryService.reserveStockFor(pendingOrder)
                        .confirmStockReservation()
                        .validateWarehouseAvailability()
                        .flatMap(reservedStock -> paymentGateway.authorizeChargeFor(reservedStock)
                                .capturePayment()
                                .recordSettlementLedgerEntry()
                                .flatMap(capturedPayment -> shippingService.createShipmentFor(capturedPayment)
                                        .assignPreferredCarrierRoute()
                                        .generateTrackingCode()
                                        .flatMap(preparedShipment -> customerNotificationService
                                                .notifyDispatchToCustomer(preparedShipment)
                                                .awaitDeliveryConfirmationSignal()
                                                .recordDeliveryReceipt()
                                                .flatMap(deliveredShipment -> fulfillmentAuditService
                                                    .recordFulfillmentOutcome(deliveredShipment)
                                                    .finalizeComprehensiveAuditTrailAndArchiveAllSupportingEvidence()
                                                    .map(persistedAuditEntry -> new FulfillmentResult(
                                                        deliveredShipment,
                                                        persistedAuditEntry
                                                    ))
                                                )
                                        )
                                )
                        )
                );
    }
}
