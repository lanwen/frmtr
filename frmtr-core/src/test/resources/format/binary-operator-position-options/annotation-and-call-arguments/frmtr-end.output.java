class BinaryArgumentLayout {

  @Description(
    "This operation with two very long string should break" +
      "in a very nice way"
  )
  void annotate() {}

  void print() {
    System.out.println(
      "This operation with two very long string should break" +
        "in a very nice way"
    );
  }
}
