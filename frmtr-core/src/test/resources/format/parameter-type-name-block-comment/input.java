package sample;

class ParameterTypeNameBlockComment {

    int primitive(int /* keepInt */ value) {
        int doubled = value * 2;
        return doubled;
    }

    String reference(String /* keepRef */ name) {
        String trimmed = name.trim();
        return trimmed;
    }

    int both(String /* first */ left, int /* second */ right) {
        int length = left.length();
        return length + right;
    }
}
