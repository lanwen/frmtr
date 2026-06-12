public class For {

    public void simpleFor(String[] array) {
        for (int i = 0; i < array.length; i++) {
            System.out.println(array[i]);
        }
    }

    public void emptyFor(String[] array) {
        for (;;) {
            System.out.println(array[i]);
        }
    }

    public void forEach(List<String> list) {
        for (String item : list) {
            System.out.println(item);
        }
    }

    public void continueSimple() {
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                continue;
            }
            System.out.println(i);
        }
    }

    public void continueWithIdentifier() {
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                continue retryLoop;
            }
            System.out.println(i);
        }
    }

    void nested() {
        for (ResourceHandle<?> handle : resourceHandles)
            for (ResourceHandle<?> handle : resourceHandles)
                for (ResourceHandle<?> handle : resourceHandles) processHandle();
    }

    void noUpdate() {
        for (var keys = cache.entries.keys(); keys.hasMoreElements(); ) {}
    }

    void compoundUpdate(String[] pairs) {
        for (int i = 0; i < pairs.length - 1; i += 2) {
            use(pairs[i], pairs[i + 1]);
        }
    }
}
