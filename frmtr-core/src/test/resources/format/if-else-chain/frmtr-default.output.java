public class If {

    public void simpleIf(boolean one) {
        if (one) {
            System.out.println("one");
        }
    }

    public void ifElse(boolean one) {
        if (one) {
            System.out.println("one");
        } else {
            System.out.println("not one");
        }
    }

    public boolean shortIfElse(boolean one) {
        return one ? true : false;
    }

    public void ifElseIfElse(boolean one, boolean two) {
        if (one) {
            System.out.println("one");
        } else if (two) {
            System.out.println("two");
        } else {
            System.out.println("not one or two");
        }
    }

    public void ifElseIfElseIfElse(boolean one, boolean two, boolean three) {
        if (one) {
            System.out.println("one");
        } else if (two) {
            System.out.println("two");
        } else if (three) {
            System.out.println("three");
        } else {
            System.out.println("not one, two, or three");
        }
    }

    void longIfElseChain() {
        if (firstCondition) {
            // 1
        } else if (secondCondition) {
            // 2
        } else if (thirdCondition) {
            // 3
        } else if (fourthCondition) {
            // 4
        } else if (fifthCondition) {
            // 5
        } else if (sixthCondition) {
            // 6
        } else if (seventhCondition) {
            // 7
        } else if (eighthCondition) {
            // 8
        } else if (ninthCondition) {
            // 9
        } else if (tenthCondition) {
            // 10
        } else if (eleventhCondition) {
            // 11
        } else if (twelfthCondition) {
            // 12
        } else if (thirteenthCondition) {
            // 13
        } else if (fourteenthCondition) {
            // 14
        } else if (fifteenthCondition) {
            // 15
        } else if (sixteenthCondition) {
            // 16
        } else if (seventeenthCondition) {
            // 17
        } else if (eighteenthCondition) {
            // 18
        } else if (nineteenthCondition) {
            // 19
        } else if (twentiethCondition) {
            // 20
        }
    }
}
