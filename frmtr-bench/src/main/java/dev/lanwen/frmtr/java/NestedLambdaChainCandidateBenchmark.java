package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.Frmtr;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Guards against exponential re-entry into expression-lambda body candidate building: each level below is a distinct
 * nested {@code flatMap} chain (not the same node re-ranked), so this tracks the real fan-out cost the
 * {@code ExpressionLambdaArgumentLayout} memo collapses, not the already-covered same-node re-ranking case.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class NestedLambdaChainCandidateBenchmark {

    /** Six nested {@code flatMap} hops, each a distinct receiver/chain shape mirroring a reactive fulfillment pipeline. */
    private static final String NESTED_SIX_LEVELS = """
            package dev.example.fulfillment;
            class OrderFulfillmentPipeline {
                private OrderRepository orderRepository;
                private InventoryService inventoryService;
                private PaymentGateway paymentGateway;
                private ShippingService shippingService;
                private NotificationService customerNotificationService;
                private AuditService fulfillmentAuditService;
                Mono<FulfillmentResult> fulfillOrder(String orderReference) {
                    return orderRepository.findByReference(orderReference).flatMap(pendingOrder1 ->
                        inventoryService.reserveStockFor(pendingOrder1).confirmStockReservation().validateWarehouseAvailability().flatMap(reservedStock2 ->
                            paymentGateway.authorizeChargeFor(reservedStock2).capturePayment().recordSettlementLedgerEntry().flatMap(capturedPayment3 ->
                                shippingService.createShipmentFor(capturedPayment3).assignPreferredCarrierRoute().generateTrackingCode().flatMap(preparedShipment4 ->
                                    customerNotificationService.notifyDispatchToCustomer(preparedShipment4).awaitDeliveryConfirmationSignal().recordDeliveryReceipt().flatMap(deliveredShipment5 ->
                                        fulfillmentAuditService.recordFulfillmentOutcome(deliveredShipment5).finalizeComprehensiveAuditTrailAndArchiveAllSupportingEvidence().map(x ->
                                            new FulfillmentResult(deliveredShipment5, x)))))));
                }
            }
            """;

    /** One additional nested hop past {@link #NESTED_SIX_LEVELS}, sized to show the fix keeps growth roughly linear. */
    private static final String NESTED_SEVEN_LEVELS = """
            package dev.example.fulfillment;
            class OrderFulfillmentPipeline {
                private OrderRepository orderRepository;
                private InventoryService inventoryService;
                private PaymentGateway paymentGateway;
                private ShippingService shippingService;
                private NotificationService customerNotificationService;
                private AuditService fulfillmentAuditService;
                Mono<FulfillmentResult> fulfillOrder(String orderReference) {
                    return orderRepository.findByReference(orderReference).flatMap(pendingOrder1 ->
                        inventoryService.reserveStockFor(pendingOrder1).confirmStockReservation().validateWarehouseAvailability().flatMap(reservedStock2 ->
                            paymentGateway.authorizeChargeFor(reservedStock2).capturePayment().recordSettlementLedgerEntry().flatMap(capturedPayment3 ->
                                shippingService.createShipmentFor(capturedPayment3).assignPreferredCarrierRoute().generateTrackingCode().flatMap(preparedShipment4 ->
                                    customerNotificationService.notifyDispatchToCustomer(preparedShipment4).awaitDeliveryConfirmationSignal().recordDeliveryReceipt().flatMap(deliveredShipment5 ->
                                        fulfillmentAuditService.recordFulfillmentOutcome(deliveredShipment5).finalizeComprehensiveAuditTrailAndArchiveAllSupportingEvidence().flatMap(orderReference6 ->
                                            orderRepository.findByReference(orderReference6).map(x ->
                                                new FulfillmentResult(orderReference6, x))))))));
                }
            }
            """;

    @Benchmark
    public String formatNestedSixLevels() {
        return Frmtr.format(NESTED_SIX_LEVELS);
    }

    @Benchmark
    public String formatNestedSevenLevels() {
        return Frmtr.format(NESTED_SEVEN_LEVELS);
    }
}
