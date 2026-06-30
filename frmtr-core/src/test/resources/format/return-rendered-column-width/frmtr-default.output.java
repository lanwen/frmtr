class ReturnRenderedColumnWidth {

    String resolveFailureMode(ProcessingTarget target, String key) {
        switch (key) {
            case "eventProcessingFailureHandlingMode":
                return target.getConfiguration().getEventProcessingFailureHandlingMode();
        }
        return null;
    }

    String resolveFailureModeDirectly(ProcessingTarget target) {
        return target.getConfiguration().getEventProcessingFailureHandlingMode();
    }

    String describeFailureMode(ProcessingTarget target) {
        return target
                .getConfigurationRegistry()
                .resolveActiveProfileSelector()
                .formatEventProcessingFailureHandlingModeLabel();
    }
}
