class StageWrapperSample {

    static class NoOpWrapper implements StageWrapper {

        @Override
        public <KIn, VIn, KOut, VOut> WrappedStageSupplier<KIn, VIn, KOut, VOut> wrapStageSupplier(
                final String stageName,
                final StageSupplier<KIn, VIn, KOut, VOut> stageSupplier
        ) {
            return () -> (Stage<KIn, VIn, KOut, VOut>) record -> {
                // intentionally ignored
            };
        }
    }

    static Supplier<Runnable> control() {
        return () -> (Runnable) () -> {
            // keep this block multi-line
            invoke();
        };
    }
}
