public class EmptyStament {
    class EmptyStament2 {}

    public void emptyStatementWithoutComment() {
        ;
        ;
        ;
    }

    public void emptyStatementWithComment() {
        //EmptyStatement
        ;
        ;
    }

    public void simpleForWithEmptyStatement() {
        /*test*/

        for (; ; ) {
            ;
        }
        for (; ; ) {
            ;
        }
        for (; ; ) {
            ;
        }
        for (; ; ) {
            ;
        }
    }

    public void simpleForWithEmptyStatement() {
        for (; ; ) {
            ;
        }
        for (; ; ) {
            ;
        }
        for (; ; ) {
            ;
        }
        for (; ; ) {
            ;
        }
    }

    public void forEachWithEmptyStatement(List<String> list) {
        for (String str : list) {
            ;
        }
        for (String str : list) {
            ;
        }
        for (String str : list) {
            ;
        }
    }

    public void ifElseWithEmptyStatements() {
        if (test) {
            ;
        } else {
            System.out.println("one");
        }
        if (test) {
            System.out.println("two");
        } else {
            ;
        }
        if (test) {
            ;
        } else {
            ;
        }
    }

    public void ifElseWithEmptyStatementsWithComments() {
        if (test) {
            ;
        } else {
            System.out.println("one");
        }
        if (test) {
            ;
        } else {
            System.out.println("one");
        }
        if (test) {
            System.out.println("two");
        } else {
            ;
        }
        if (test) {
            System.out.println("two");
        } else {
            ;
        }
        if (test) {
            ;
        } else {
            ;
        }
        if (test) {
            ;
        } else {
            ;
        }
    }

    public void simpleWhileWithEmptyStatement(boolean one) {
        while (one) {
            ;
        }
        while (one) {
            ;
        }
        while (one) {
            ;
        }
    }

    public void doWhileWithEmptyStatement(boolean one) {
        do {
            ;
        } while (one);
        do {
            ;
        } while (one);
        do {
            ;
        } while (one);
    }
}

// Bug Fix: #356
public class Test {
    public TestField testField;

    @Override
    public void someMethod() {}
}
