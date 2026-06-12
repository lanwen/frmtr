interface MemberSpacing {
    int ALPHA = 1;

    int BETA = 2;
    int GAMMA = 3;
    Item first();

    Item second();

    Item compactOne();
    Item compactTwo();
}

final class FieldSpacing {
    private static final int LIMIT = 10;
    private String one;

    private String two;
    private String three;
    Item compute() {
        return new Item();
    }
    Item describe() {
        return compute();
    }
}

final class Item {}


public class BlankLines {

  public int firstCount = 1;
  public int secondCount = 2;



  public int thirdCount = 3;
  public int fourthCount = 4;

  // Bug Fix: https://github.com/jhipster/prettier-java/issues/368
  private String primaryField;
  private String secondaryField;
  @Nullable private String fieldRequiringSpacing;
  private String tertiaryField;
  private String quaternaryField;

  private String sourceName;
  private String destinationName;
  private @Nullable String inlineNullableField;
  private String ownerName;
  private String reviewerName;

  public int retryCount = 4;
  public Constructors() {
    this(true);
    System.out.println("empty constructor");
  }
  public void shouldAddLineBefore() {
    System.out.println("Should add empty line before method");
  }




  public void shouldAddOnlyOneLineBefore() {
    System.out.println("Should add only one empty line between the two methods");
  }
  private Config config;


  public void shouldAlsoAddOnlyOneLineBefore() {
    System.out.println("Should add only one empty line between the two class statement");
  }

  public void shouldHandleBlankLinesInBlock() {
    int firstCount = 1;
    int secondCount = 2;



    int thirdCount = 3;
    int fourthCount = 4;

    int retryCount = 4;
    // Add a line before comment
    int batchSize = 4;
    for (int attempt=0; attempt<3;attempt++);

  }

}

interface BlankLinesInInterfaces {
    // Bug Fix: https://github.com/jhipster/prettier-java/issues/368
    String primaryField;
    String secondaryField;
    @Nullable String fieldRequiringSpacing;
    String tertiaryField;
    String quaternaryField;

    private @Nullable String resolveOwner();
    private @Nullable static String resolveOwner();
    private @Nullable String resolveOwner();
    private @Nullable String resolveOwner();
    @Nullable
    private static String resolveOwner();
    private @Nullable String resolveOwner();
    private static String resolveOwner();
    @Nullable String resolveOwner();
}
