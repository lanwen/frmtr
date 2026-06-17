class ArrayInitializerLineCommentGaps {

    String[] sourceState() {
        return new String[] {
            "alpha",
            "beta", // keep beta route active
            "gamma",
            // "paused", // keep paused route documented
        };
    }

    String[] firstPassState() {
        return new String[] {
            // "disabled", // keep disabled route documented
            "delta",
            "epsilon",
        };
    }
}
