class Sample {

    void run() {
        try {
            first();
        } catch (RuntimeException error) {
            // trailing note for the try block
            second();
        } finally {
            // trailing note for the catch block
            third();
        }
        // trailing note for the finally block
    }
}
