class RetryClassifier {

    boolean shouldRetry(int errorCode) {
        return errorCode == 429 /* THROTTLED */ ||
            errorCode == 503 /* UNAVAILABLE */ ||
            errorCode == 504 /* GATEWAY_TIMEOUT */ ||
            errorCode == 599 /* NETWORK_TIMEOUT */;
    }

    boolean isFatal(int errorCode) {
        return errorCode == 400 /* BAD_REQUEST */ || errorCode == 401 /* UNAUTHORIZED */;
    }
}
