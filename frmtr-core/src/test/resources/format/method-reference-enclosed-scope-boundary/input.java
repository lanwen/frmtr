public class NotificationRouter {

    void routeWhenAnyChannelStaysWithinTheLineWidth() {
        Runnable dispatchWhenAnyChannelIsReady = (primaryNotificationChannelIsReady || secondaryChannelIsReadyNow)::run;
    }

    void routeWhenAnyChannelOverflowsTheLineWidth() {
        Runnable dispatchWhenAnyChannel = (primaryNotificationDeliveryChannelIsCurrentlyReadyNow || secondaryNotificationDeliveryChannelIsReadyNow)::run;
    }
}
