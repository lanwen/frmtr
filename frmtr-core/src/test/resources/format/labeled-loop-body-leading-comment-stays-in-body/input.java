package parser;

class TokenScanner {
    int scan(char[] buffer, int limit) {
        MAIN_LOOP: while (hasRemaining()) {
            // work on the next markup run before flushing character data
            for (int index = 0; index < limit; index++) {
                if (buffer[index] == '<') {
                    return emitMarkup(index);
                }
            }
        }
        return END_OF_INPUT;
    }
}
