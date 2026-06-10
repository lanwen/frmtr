class MethodCallInitializerOpenerSample {
    void configure(Receiver receiver) {
        var sample = receiver.call(Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
            return item.compute(alpha(), beta(), gamma(), delta());
        }));
        var commented = receiver.call(Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
            // keep nested comments from forcing an outer assignment break
            return item.compute(alpha(), beta(), gamma(), delta());
        }));
        var sampleNameWithEnoughLengthToForceTheReceiverCallOpenerOntoAContinuationLineWhenTheInitializerBreaks = receiver.call(Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
            return item.compute(alpha(), beta(), gamma(), delta());
        }));
    }
}
