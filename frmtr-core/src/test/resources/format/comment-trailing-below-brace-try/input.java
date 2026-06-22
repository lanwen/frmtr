class Sample {
    void run() {
        try {
            first();
        }
        // trailing note for the try block
        catch (RuntimeException error) {
            second();
        }
        // trailing note for the catch block
        finally {
            third();
        }
        // trailing note for the finally block
    }
}
