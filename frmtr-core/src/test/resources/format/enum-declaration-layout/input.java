public enum Enum {

  SOME_ENUM, ANOTHER_ENUM, LAST_ENUM

}

public enum EnumWithExtraSemicolon {

  SOME_ENUM, ANOTHER_ENUM, LAST_ENUM;

}

public enum EnumWithExtraComma {

  SOME_ENUM, ANOTHER_ENUM, LAST_ENUM,

}

public enum EnumWithExtraCommaAndExtraSemicolon {

  SOME_ENUM, ANOTHER_ENUM, LAST_ENUM,;

}

public enum EnumWithExtraCommaAndComment {

  SOME_ENUM, ANOTHER_ENUM, LAST_ENUM/* comment */,

}

public enum EnumWithExtraSemicolonAndComment {

  SOME_ENUM, ANOTHER_ENUM, LAST_ENUM/* comment */;

}

public enum EnumWithManyValues {

  ONE_VALUE, TWO_VALUE, THREE_VALUE, FOUR_VALUE, FIVE_VALUE, SIX_VALUE, SEVEN_VALUE, EIGHT_VALUE, NINE_VALUE,
  TEN_VALUE

}

public enum EnumWithManyValuesWithExtraSemicolon {

  ONE_VALUE, TWO_VALUE, THREE_VALUE, FOUR_VALUE, FIVE_VALUE, SIX_VALUE, SEVEN_VALUE, EIGHT_VALUE, NINE_VALUE,
  TEN_VALUE;

}

public enum EnumWithManyValuesWithExtraComma {

  ONE_VALUE, TWO_VALUE, THREE_VALUE, FOUR_VALUE, FIVE_VALUE, SIX_VALUE, SEVEN_VALUE, EIGHT_VALUE, NINE_VALUE,
  TEN_VALUE,

}

public enum EnumWithManyValuesWithExtraCommaAndExtraSemicolon {

  ONE_VALUE, TWO_VALUE, THREE_VALUE, FOUR_VALUE, FIVE_VALUE, SIX_VALUE, SEVEN_VALUE, EIGHT_VALUE, NINE_VALUE,
  TEN_VALUE,;

}

public enum EnumWithExtraCommaAndEnumBodyDeclarations {

  ENABLED("active"), DISABLED("inactive"), ;

  public static final String legacyCode = "legacy";

}

public enum Enum {

  ENABLED("active"), DISABLED("inactive");

  public static final String legacyCode = "legacy";

  private final String value;

  public Enum(String value) {
    this.value = value;
  }

  public String toString() {
    return "status";
  }

}

class ClassWithEnum {

  public static enum ValidStatuses {

    FIRST, SECOND

  }

}

public enum OtherEnum {
  ONE, TWO,

  THREE,



  FOUR,
  /* Five */
  FIVE,

  /* Six */
  SIX


}

public enum EmptyEnum {
}

public enum EmptyEnumWithComment {
  // comment
}

enum FormatterNotes {
  FORMATTER_IS_AVAILABLE,
  // And I can't believe
  // it is free
  // why are people
  // so damn generous
  ;

  void printTest() {
    System.out.println("Hey there");
  }
}


enum FormatterNotes {
  FORMATTER_IS_AVAILABLE;
  // And I can't believe
  // it is free
  // why are people
  // so damn generous

  void printTest() {
    System.out.println("Hey there");
  }
}

enum MinimalState {
  ;

  /**/
  void reset() {}
}

enum DeploymentState implements LifecycleMarker {
  READY
}

enum DeploymentState implements LifecycleMarker, AuditMarker, RetryMarker, ArchiveMarker, DisplayMarker {
  READY
}

enum DeploymentState implements LifecycleMarker, AuditMarker, RetryMarker, ArchiveMarker, DisplayMarker {}

enum DeploymentState implements LifecycleMarker, AuditMarker, RetryMarker, ArchiveMarker, DisplayMarker, ExternalMarker {
  READY
}

enum DeploymentState implements LifecycleMarker, AuditMarker, RetryMarker, ArchiveMarker, DisplayMarker, ExternalMarker {}
