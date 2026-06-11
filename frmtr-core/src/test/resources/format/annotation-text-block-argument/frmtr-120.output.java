package sample;

interface LedgerRows {
    @Select(
        """
        SELECT item_id, item_state, updated_at
        FROM ledger_items
        WHERE owner_id = :ownerId
        ORDER BY item_id
        """
    )
    Flow<ItemRow> readByOwner(String ownerId);
}

@interface Select {
    String value();
}
