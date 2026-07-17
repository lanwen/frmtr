class BinaryEmptyCallOperandSample {

    void revokeIdleTaskWhenPartitionMatches(Assignment assignment, String threadName, TaskInfo taskWithData) {
        if (
            taskWithData != null
            && taskWithData.partition() == assignment.groupAssignment()
                    .get(threadName)
                    .partitions()
                    .get(0)
                    .partition()
        ) {
            markRevoked();
        }
    }
}
