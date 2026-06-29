package demo;

import java.util.List;

class Demo {
    void ifBody(Node node, Throwable error) {
        if (canConnect(node, error))
            // re-establish the connection before retrying the operation
            initiateConnect(node, error);
    }

    void whileBody(int count) {
        while (count > 0)
            // walk the counter down to zero
            count--;
    }

    void doWhileBody(int count) {
        do
            // always run the body once before re-checking
            count--;
        while (count > 0);
    }

    void forBody(int count) {
        for (int index = 0; index < count; index++)
            // process one element per iteration
            doWork(index);
    }

    void forEachBody(List<String> values) {
        for (String value : values)
            // emit each value on its own line
            System.out.println(value);
    }
}
