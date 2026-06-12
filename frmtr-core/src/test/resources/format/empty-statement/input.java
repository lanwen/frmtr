public class EmptyStatementCases {

  class EmptyStatementBlock {
    ;
  }

  public void emptyStatementWithoutComment() {
    ;;;
  }

  public void emptyStatementWithComment() {
    ;;// Empty statement marker
  }

  public void simpleForWithEmptyStatement() {
    for (;;);

    for (;;)/* empty body marker */;

    for (;;);/* empty body marker */

    for (;;)/* empty body marker */;/* empty body marker */
  }

  public void simpleForWithEmptyStatement() {
    for (;;);

    for (;;)/* empty body marker */;

    for (;;);/* empty body marker */

    for (;;)/* empty body marker */;/* empty body marker */
  }

  public void forEachWithEmptyStatement(List<String> list) {
    for (String str : list);

    for (String str : list)/* empty body marker */;

    for (String str : list);/* empty body marker */
  }

  public void ifElseWithEmptyStatements() {
    if (condition); else {
      System.out.println("one");
    }

    if (condition) {
      System.out.println("two");
    } else;

    if (condition); else;
  }

  public void ifElseWithEmptyStatementsWithComments() {
    if (condition)/* empty body marker */; else {
      System.out.println("one");
    }

    if (condition);/* empty body marker */ else {
      System.out.println("one");
    }

    if (condition) {
      System.out.println("two");
    } else/* empty body marker */;

    if (condition) {
      System.out.println("two");
    } else;/* empty body marker */

    if (condition);/* empty body marker */ else;/* empty body marker */

    if (condition)/* empty body marker */; else/* empty body marker */;
  }

  public void simpleWhileWithEmptyStatement(boolean keepRunning) {
    while (keepRunning);

    while (keepRunning)/* empty body marker */;

    while (keepRunning);/* empty body marker */
  }

  public void doWhileWithEmptyStatement(boolean keepRunning) {
    do;while (keepRunning);
    do/* empty body marker */;while (keepRunning);
    do;/* empty body marker */ while (keepRunning);
  }

}

// Bug Fix: #356
public class EmptyStatementFieldRegression {
  public StatementField statementField;;

  @Override
  public void someMethod() {}
}
