open module com.example.vending.machine.gui {
    requires java.desktop;
    requires com.example.vending.machine.model;
    requires transitive com.example.core;
    exports com.example.vending.machine.model to com.example.admin, com.example.reporting, com.example.analytics;
    exports com.example.vending.machine.model.without.destination;

    exports com.example.vending.machine.model.it.should.be.breaking.but.only.a.part
        to com.example.admin, com.example.reporting, com.example.analytics;

    exports com.example.vending.machine.model
        to
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.mobile,
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.enterprise.retail.fulfillment.reporting.connector;

    opens com.example.vending.machine.model.without.destination;

    opens com.example.vending.machine.model to com.example.admin, com.example.reporting, com.example.analytics;

    opens com.example.vending.machine.model.it.should.be.breaking.but.only.a.part
        to com.example.admin, com.example.reporting, com.example.analytics;

    opens com.example.vending.machine.model
        to
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.admin,
            com.example.reporting,
            com.example.enterprise.retail.fulfillment.reporting.connector;

    uses com.example.vending.machine.services.DrinkService;

    provides com.example.vending.machine.model.PaymentTerminal
        with com.example.admin, com.example.reporting, com.example.analytics;

    provides com.example.vending.machine.model.it.should.be.breaking.but.only.a.part
        with com.example.admin, com.example.reporting, com.example.analytics;

    provides com.example.vending.machine.model.PaymentTerminal
        with
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.admin,
            com.example.reporting,
            com.example.analytics,
            com.example.admin,
            com.example.reporting,
            com.example.enterprise.retail.fulfillment.reporting.connector;
}
