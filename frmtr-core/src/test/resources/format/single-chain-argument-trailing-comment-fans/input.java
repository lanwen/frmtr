package demo;

class TaskAssignmentBuilder {
    void addMismatchedTask(List<StreamsGroupCurrentMemberAssignmentValue.TaskIds> activeTasks) {
        activeTasks.add(new StreamsGroupCurrentMemberAssignmentValue.TaskIds()
            .setSubtopologyId(SUBTOPOLOGY_1)
            .setPartitions(Arrays.asList(1, 2, 3))
            .setAssignmentEpochs(Arrays.asList(10, 11))); // Only 2 epochs for 3 partitions
    }
}
