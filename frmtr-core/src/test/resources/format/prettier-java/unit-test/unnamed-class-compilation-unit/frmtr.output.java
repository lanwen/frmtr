import com.toto.titi.Test;
import com.toto.titi.Toast;

final class $COMPACT_CLASS {

    class TestClass {

        static String greetings() {
            return "Hello world!";
        }
    }

    interface TestInterface {

        default String greetings() {
            return "Hello world!";
        }
    }

    String greeting() {
        return "Hello, World!";
    }

    void main() {
        System.out.println(Test.greeting());
    }
}
