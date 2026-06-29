package sample;

class DataSourceDescriptor {

    String describe() {
        return """
            datasource:
              name: %s
              url: %s
              user: %s
              min: %d
            """.formatted(getClass().getName(), dataSourceConnectionUrl, descriptorUserName, minimumPoolConnectionsAllowed);
    }
}
