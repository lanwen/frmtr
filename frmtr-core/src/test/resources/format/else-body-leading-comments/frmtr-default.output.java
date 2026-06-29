package fixtures.elsebodyleadingcomments;

class ElseBodyLeadingComments {

    int oneLeadingComment(boolean b) {
        if (b) {
            return 1;
        } else
            // sole note leading the braceless else body
            return 2;
    }

    int twoLeadingComments(boolean b) {
        if (b) {
            return 1;
        } else
            // first note leading the braceless else body
            // second note leading the braceless else body
            return 2;
    }
}
