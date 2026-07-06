class ObjectCreationNestedFanArgument {

    void constructorArgumentCarriesNestedChain(Admin admin) {
        BrokerDirectories directories = new BrokerDirectories(
            admin.describeLogDirs(IntStream.range(0, 4)
                    .boxed()
                    .toList()),
            0
        );
    }
}
