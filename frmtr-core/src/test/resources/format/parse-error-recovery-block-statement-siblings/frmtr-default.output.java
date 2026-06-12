class RecoveryBlockStatements {

    void method() {
        beforeAudit(1);
        var broken = ; // keep raw
        afterAudit(2);
    }
}
