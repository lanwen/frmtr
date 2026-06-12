public class ComplexCommentBlocks {

    int retryCount = 0;

    public void processCoordinates(int attemptCount) {
        try {
            // Empty Statement
        } /*catch*/ catch (EmptyStackException stackFailure) {
            throw new RuntimeException(stackFailure);
        } /*multi-catch*/ catch (
            /*1*/ FirstException
            | /*2*/ SecondException
            | /*3*/ ThirdException combinedFailure
        ) {
            throw /*throw an exception*/ new /*don't forget new when throwing exceptions*/ RuntimeException(
                combinedFailure
            );
        } /*is always executed no matter what*/ finally {
            System.out.println("That's all folks !");
        }
    }

    private void processCoordinates(/* axis x */ int axisX, /* axis y */ int axisY, /* axis z */ int axisZ) {
        if (axisX == 0 && axisY == 0 && axisZ == 0) throw new RuntimeException("X Y Z cannot be all 0");

        int /*variable name is of value var */ var = axisX + axisY + axisZ;
        if (/*true*/ var == 0) {
            System.out.println("The value is 0");
        } else /*false*/ {
            int[] values = {
                /*One*/ 1,
                /*Two */ 2,
                /*zero*/ 0,
                /*One again*/ 1,
                -1 /*Minus One*/,
                0,
                2,
            };

            // Label statement

            //foreach
            valueLoop: for (int number /* number is every value in values*/ : values) {
                /*switch*/ switch (number) {
                    //switch
                    case 1:
                        System.out.println("One ");
                        System.out.println("One ");

                        System.out.println("One ");
                        /*just a break*/ break;
                    case 2:
                        System.out.println("Two ");
                        break;
                    case 0:
                        System.out.println("Zero ");

                        continue /*labeled continued*/ valueLoop;
                    default /*def*/:
                        /*labeled break*/ break valueLoop;
                }
            }
        }
    }

    private synchronized void processCoordinates(int iterationLimit, int unusedStep /*overloading*/) {
        for (int i = 0; i < /*=*/ iterationLimit; i++) do /*dodododo*/ {
            //do whiles
            //asserting
            assert /*true*/ true == true;
            continue;
            break;
            /*dead code*/
            return;
        } /*at least one iteration !*/ while (false);
        synchronized (/*declares synchronizd statement*/ this) {
            while (/*infinite*/ true)
                /*stop the program*/ throw new RuntimeException();
        }
    }
}

//Additionnal enumeration
enum CardSuit {
    //The Heart and the Spade
    HEART, /*the heart*/
    SPADE, /*and the spade*/
}
