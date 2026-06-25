package sample;

final class InitializerOverflowCastAndArrayAccessChain {

    TargetDescriptor resolveActiveTarget() {
        TargetDescriptor resolvedCastTarget = (TargetDescriptor) connectionProvider.resolveActiveDescriptor(
            primarySessionToken
        );
        return resolvedCastTarget;
    }

    HandlerResult dispatchActiveHandler() {
        HandlerResult selectedHandlerResult = handlerDescriptorTable[activeHandlerIndex].dispatchToConfiguredHandler(
            activeSessionContext
        );
        return selectedHandlerResult;
    }
}
