public class JumpStatementLeadingBlockComment {

    void scanQueue(boolean ready) {
        for (;;) {
            if (ready) {
                /*
                 * Matched the earliest pending entry.
                 * Stop scanning the queue now.
                 */
                break;
            } else {
                handleMiss();
            }
        }
    }

    void drainSession(boolean ready) {
        while (ready) {
            if (ready) {
                /*
                 * Cannot advance this entry yet.
                 * Leave it pending for the next pass.
                 */
                break;
            }
        }
    }

    void replayBatch(boolean ready) {
        outer: while (ready) {
            if (ready) {
                /*
                 * Abort the whole replay batch.
                 * A newer snapshot superseded it.
                 */
                break outer;
            } else {
                handleMiss();
            }
        }
    }

    void inlineNote(boolean ready) {
        while (ready) {
            /* short note */ break;
        }
    }

    boolean controlReturn(boolean ready) {
        if (ready) {
            /*
             * Done with the lookup.
             * Hand the result back to the caller.
             */
            return ready;
        } else {
            handleMiss();
        }
        return ready;
    }

    void controlContinue(boolean ready) {
        while (ready) {
            if (ready) {
                /*
                 * Skip this iteration entirely.
                 * The entry is not yet eligible.
                 */
                continue;
            } else {
                handleMiss();
            }
        }
    }

    void controlPlain(boolean ready) {
        if (ready) {
            /*
             * Record the matched entry.
             * The follow-up work happens later.
             */
            handleMiss();
        } else {
            handleMiss();
        }
    }

    void handleMiss() {}
}
