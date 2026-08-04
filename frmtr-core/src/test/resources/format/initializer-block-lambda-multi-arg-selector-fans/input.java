class GradlePluginSample {
    void registerTasks(Project workspace) {
        TaskProvider<FormatterTask> format = workspace.getActions().register("formatSource", FormatterTask.class, action -> {
            action.configure(config);
        });
        TaskProvider<FormatterTask> bundleFormat = workspace.getActions().register("bundleFormatSource", FormatterTask.class, action -> {
            action.configure(bundleConfig);
            action.setGroup("formatting");
        });
    }
}
