package dev.lanwen.frmtr.fixtures;

import java.io.IOException;
import java.sql.SQLException;

class CommentedSignatureThrowsClause {

    void single(String /* payload */ message) throws IOException {
      throw new IOException(message);
    }

    int multiple(int /* index */ slot) throws IOException, SQLException {
      return readAt(slot);
    }

    void noThrows(String /* payload */ message) {
      sink(message);
    }

    int readAt(int slot) {
        return slot;
    }

    void sink(String message) {}
}
