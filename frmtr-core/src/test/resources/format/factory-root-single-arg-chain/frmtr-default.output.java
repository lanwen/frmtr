class FactoryRootSingleArgumentChain {

    void optionalOfChainInStatementCondition(TopicDescription description, int partition, int replicaId) {
        actualLeader.set(
            Optional.of(
                description.partitions()
                        .get(partition)
                        .leader()
            )
                    .map(Node::id)
                    .orElse(-1)
        );
    }

    void arraysStreamChainInitializer(LogSegment activeSegment) {
        List<File> indexFilesOnDiskBeforeDelete = Arrays.stream(
            activeSegment.log()
                    .file()
                    .getParentFile()
                    .listFiles()
        )
                .filter(candidate -> candidate.getName().endsWith("index"))
                .toList();
    }
}
