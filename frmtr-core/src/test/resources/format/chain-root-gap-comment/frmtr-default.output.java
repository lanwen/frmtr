class ChainRootGapComment {

    Pipeline pipeline;

    void methodCallRootSingleSelector() {
        pipeline = create()
                /* primary */ .seal();
    }

    void methodCallRootMultipleSelectors() {
        pipeline = create()
                /* primary */ .seal()
                .commit();
    }

    void nameRootSelectorGap() {
        pipeline = builder
                .create()
                /* primary */ .seal()
                .commit();
    }
}
