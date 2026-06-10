class TryResourceLayoutSample {

    void compact(Resources resources) {
        try (Resource left = resources.left(); Resource right = resources.right()) {
            use(left, right);
        }
    }

    void multiline(Resources resources) {
        try (
            Resource left = resources.left();
            Resource right = resources.right()
        ) {
            use(left, right);
        }
    }
}
