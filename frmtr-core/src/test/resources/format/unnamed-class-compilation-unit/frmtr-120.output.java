import com.example.greetings.GreetingMessage;
import com.example.greetings.GreetingSource;

class GreetingSource {

    static String greetings() {
        return "Hello world!";
    }
}

interface GreetingContract {
    default String greetings() {
        return "Hello world!";
    }
}

String greeting() {
    return "Hello, World!";
}

void main() {
    System.out.println(GreetingSource.greetings());
}
