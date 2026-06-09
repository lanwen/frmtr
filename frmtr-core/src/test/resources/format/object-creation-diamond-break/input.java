package sample;

final class DiamondBreak {
    private final Registry<Subject> registry = new Registry<>();
    private final Registry<Subject> registryWithArguments = new Registry<>(initialSubjects(), initialMode(), initialOwner());
}
