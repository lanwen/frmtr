public class ChainLinkInterspersedBlockComment {

    public ConfigDef serverConfigs() {
        ConfigDef config = new ConfigDef()
            .define(BROKER_ID_CONFIG, INT)
            /** Documents the listeners link between the two define calls. */
            .define(LISTENERS_CONFIG, STRING)
            /* Documents the advertised listeners link. */
            .define(ADVERTISED_LISTENERS_CONFIG, STRING);
        return config;
    }
}
