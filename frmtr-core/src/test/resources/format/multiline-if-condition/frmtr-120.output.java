class MultilineIfConditionSample {

    boolean isOpenUnsupported(Throwable error) {
        var cause = error;
        while (cause != null) {
            if (
                cause instanceof StatusReply.ErrorMessage message &&
                StatusReplies.OPEN_UNAVAILABLE.equals(message.toString())
            ) {
                return true;
            }
            if (
                cause instanceof UnsupportedOperationException unsupported &&
                "open".equals(unsupported.getMessage())
            ) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
