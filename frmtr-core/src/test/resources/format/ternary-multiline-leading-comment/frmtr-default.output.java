package fixtures;

class TernaryMultilineLeadingComment {

    java.util.Optional<String> questionLeading(boolean empty) {
        return !empty
            // first line of question block
            // second line should not be dropped
            // third line should not be dropped
            ? java.util.Optional.of("a")
            : java.util.Optional.empty();
    }

    java.util.Optional<String> colonLeading(boolean empty) {
        return !empty
            ? java.util.Optional.of("a")
            // first line of colon block
            // second colon line should survive
            // third colon line should survive
            : java.util.Optional.empty();
    }
}
