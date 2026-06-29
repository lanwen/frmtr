package fixtures.elsebodyleadingcomments;

class ElseBodyLeadingComments {

    int oneLeadingComment(boolean b) {
        if (b) {
            return 1;
        }
        // sole note leading the braceless else body
        else return 2;
    }

    int twoLeadingComments(boolean b) {
        if (b) {
            return 1;
        }
        // first note leading the braceless else body
        // second note leading the braceless else body
        else return 2;
    }
}
