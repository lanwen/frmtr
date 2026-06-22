class Sample {

    String both(boolean cond, String thenValue, String elseValue) {
        return cond
            ? // leading then
              thenValue // trailing then
            : elseValue;
    }

    String nested(boolean a, boolean c, String b, String d, String e) {
        String selected = a
            ? b
            : c
                ? // before inner ternary
                  d // inner then
                : e;
        return selected;
    }
}
