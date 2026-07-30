package dev.example.fulfillment;

class RegionalFulfillmentDesk {

    class ZoneRoutingOffice {

        class CarrierAssignmentBooth {

            class ManifestReconciliationCell {

                class ExceptionEscalationQueue {

                    class FinalDispositionLedger {

                        boolean isEligibleForExpedite(
                                Shipment shipment,
                                Parcel parcel,
                                Carrier carrier
                        ) {
                            return shipment.status() == FulfillmentStatus.ACTIVE
                                && parcel.zone()
                                        .shouldExpedite(
                                            carrier.region()
                                        );
                        }
                    }
                }
            }
        }
    }
}
