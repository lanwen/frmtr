package demo;

class BranchSelector {

    int chooseResult(boolean prefersPrimary) {
        if (prefersPrimary)
            return computePrimaryResultValueForTheSelectedConfiguration(prefersPrimary);
        else
            return computeAlternativeFallbackResultValueForConfiguration(prefersPrimary);
    }

    int shortChoice(boolean flag) {
        if (flag) return 1; else return 2;
    }
}
