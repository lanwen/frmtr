class MessageRouter extends BaseRouter {
    MessageRouter(DispatchChannel dispatchChannel, AuditLogger auditLogger) {
        super(dispatchChannel, auditLogger, (messageId, deliveryAttempt) -> {
            auditLogger.record(messageId);
            return deliveryAttempt.isAcknowledged();
        });
    }
}
