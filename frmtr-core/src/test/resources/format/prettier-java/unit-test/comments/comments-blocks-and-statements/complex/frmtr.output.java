public class PrettierTest {

    int x = 0;

    public void myFunction(int arg1) {
        try {
            ; // Empty Statement
        } catch (EmptyStackException e) {
            throw new RuntimeException(e);
        } catch (FirstException | /*2*/SecondException |/*3*/ ThirdException e2) {
            throw new RuntimeException(e2);
        } finally {
            System.out.println("That's all folks !");
        }
    }

    private void myFunction(int arg1, int arg2, int arg3) {
        if (arg1 == 0 && arg2 == 0 && arg == 3) throw new RuntimeException("X Y Z cannot be all 0");

        int var = arg1 + arg2 + arg3;
        if (/*true*/ var == 0) {
            System.out.println("The value is 0");
        } else /*false*/
        {
            int[] arr = {
                1,
                2,
                0,
                1,
                -1,
                0,
                2,
            };

            // Label statement

            //
            loop: //foreach
            for (int num : arr) {
                /*switch*/
                switch (num) {
                    case 1:
                        System.out.println("One ");
                        System.out.println("One ");
                        System.out.println("One ");
                        /*just a break*/
                        break;
                    case 2:
                        System.out.println("Two ");
                        break;
                    case 0:
                        System.out.println("Zero ");
                        continue loop;
                    default:
                        /*labeled break*/
                        break loop;
                }
            }
        }
    }

    private synchronized void myFunction(int arg1, int arg2) {
        for (int i = 0; i < arg1; i++)
            do /*dodododo*/
            {
                //do whiles
                //asserting
                assert/*true*/ true == true;
                continue;
                break;
                return;
            } while (/*at least one iteration !*/ false);
        synchronized (/*declares synchronizd statement*/ this) {
            while (/*infinite*/ true)
                /*stop the program*/ throw new RuntimeException();
        }
    }
}

//Additionnal enumeration
enum Cards {
    //The Heart and the Spade
    HEART,
    /*the heart*/
    SPADES /*and the spade*/,
}
