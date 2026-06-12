public class While {

    public void simpleWhile(boolean shouldContinue) {
        while (shouldContinue) {
            System.out.println("continue");
        }
    }

    public void doWhile(boolean shouldContinue) {
        do {
            System.out.println("continue");
        } while (shouldContinue);
    }
}
