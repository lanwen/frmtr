package fixtures.switching;

class CaseBodyOrphanComments {

    String classify(int code) {
        switch (code) {
            case 1:
                int north = 1;
                // standalone note detached from the next statement by a blank
                // line below it so it is the switch entry's orphan trivia

                return "north-" + north;
            case 2:
                int south = 2;
                // standalone note with a blank line above but not below
                return "south-" + south;
            case 3:
                // leading note that opens the case body before any statement
                int east = 3;
                return "east-" + east;
            case 4:
                int west = 4;
                // plain inter-statement note with no surrounding blank lines
                return "west-" + west;
            default:
                return "unknown";
        }
    }
}
