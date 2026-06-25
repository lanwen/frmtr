public class ChainInterspersedCommentEmptyArgSelector {

    private ConfigDef config;

    public void configure() {
        config = new ConfigDef()
                .define(BROKER_ID_CONFIG, INT)
                /** Javadoc link before the empty-argument build selector. */
                .util()
                .define(LISTENERS_CONFIG, STRING)
                /* Block link before the empty-argument build selector. */
                .build();
    }

    public ConfigDef control() {
        return new ConfigDef()
                .define(BROKER_ID_CONFIG, INT)
                /* Control: block link before a non-empty selector, already covered by PR #60. */
                .define(LISTENERS_CONFIG, STRING);
    }
}
