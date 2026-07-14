public class ReportQuery {

    void loadActiveAccounts() {
        database.execute(
            // active accounts only
            """
            SELECT id, name
            FROM accounts
            WHERE active = true
            """
            // TODO: bind the active flag as a parameter
        );
    }

    void countPageViews() {
        database.execute(
            /* aggregate by day */
            """
            SELECT day, count(*)
            FROM page_views
            GROUP BY day
            """ // ordered chronologically
        );
    }
}
