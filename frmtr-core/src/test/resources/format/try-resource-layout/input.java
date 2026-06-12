class TryResourceLayoutSample {
    void compact(Resources resources) {
        try (Resource left = resources.left(); Resource right = resources.right()) {
            use(left, right);
        }
    }

    void multiline(Resources resources) {
        try (
            Resource left = resources.left();
            Resource right = resources.right()
        ) {
            use(left, right);
        }
    }
}


public class TryCatch {

  void tryFinally() {
    try {
      System.out.println("Try something");
    } finally {
      System.out.println("Finally do something");
    }
  }

  void tryCatch() {
    try {
      System.out.println("Try something");
    } catch (ArithmeticException e) {
      System.out.println("Warning: ArithmeticException");
    } catch (ArrayIndexOutOfBoundsException e) {
      System.out.println("Warning: ArrayIndexOutOfBoundsException");
    } catch (Exception e) {
      System.out.println("Warning: Some Other exception");
    }
  }

  void tryCatchFinally() {
    try {
      System.out.println("Try something");
    } catch (ArithmeticException e) {
      System.out.println("Warning: ArithmeticException");
    } catch (ArrayIndexOutOfBoundsException e) {
      System.out.println("Warning: ArrayIndexOutOfBoundsException");
    } catch (Exception e) {
      System.out.println("Warning: Some Other exception");
    } finally {
      System.out.println("Finally do something");
    }
  }

  void tryMultiCatchFinally() {
    try {
      System.out.println("Try something");
    } catch (ArithmeticException | ArrayIndexOutOfBoundsException e) {
      System.out.println("Warning: Not breaking multi exceptions");
    } catch (ArithmeticException | ArrayIndexOutOfBoundsException | SomeOtherException e) {
      System.out.println("Warning: Breaking multi exceptions");
    } finally {
      System.out.println("Finally do something");
    }
  }

  void resourceTry() {
    try (Resource resource = new Resource()) {
        return reader.readLine();
    } catch (ArithmeticException | ArrayIndexOutOfBoundsException e) {
      System.out.println("Warning: Not breaking multi exceptions");
    }
  }

  void multiResourceTry() {
    try (FirstResource firstResource = new FirstResource(); SecondResource secondResource = new SecondResource()) {
      return reader.readLine();
    } catch (ArithmeticException | ArrayIndexOutOfBoundsException e) {
      System.out.println("Warning: Not breaking multi exceptions");
    }
  }

  void multiResourceTryWithTrailingSemi() {
    try (
      FirstResource firstResource = new FirstResource();
      SecondResource secondResource = new SecondResource();
    ) {
      return reader.readLine();
    } catch (ArithmeticException | ArrayIndexOutOfBoundsException e) {
      System.out.println("Warning: Not breaking multi exceptions");
    }
  }

  void emptyBlocks() {
    try {} catch (Exception e) {}
    try (var resource = new Resource()) {} catch (Exception e) {}
    try {} finally {}
    try (var resource = new Resource()) {} finally {}
    try {} catch (Exception e) {} finally {}
    try (var resource = new Resource()) {} catch (Exception e) {} finally {}
    try {} catch (Exception e) {} catch (Exception e) {}
    try (var resource = new Resource()) {} catch (Exception e) {} catch (Exception e) {}
    try {} catch (Exception e) {} catch (Exception e) {} finally {}
    try (var resource = new Resource()) {} catch (Exception e) {} catch (Exception e) {} finally {}
  }

  void lineComments() {
    try {} // a
    finally {} // b

    try {} // a
    catch (Exception recoverable) {} // b
    catch (Exception fallback) {} // c
    finally {} // d

    try { // a1
      open;
    } // a2
    finally { // b1
      recover;
    } // b2

    try { // a1
      open;
    } // a2
    catch (Exception recoverable) { // b1
      recover;
    } // b2
    catch (Exception fallback) { // c1
      fallback;
    } // c2
    finally { // d1
      cleanup;
    } // d2
  }
}
