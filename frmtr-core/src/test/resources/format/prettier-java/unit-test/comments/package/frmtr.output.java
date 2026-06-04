/*a*/
open module soat/*a*/./*b*/vending.machine.gui {
    /*a*/
    requires java.desktopa;
    /*b*/
    requires soat.vending.machine.model;
    requires transitive soat.core;
    /*a*/
    exports fr.soat.vending.machine.model to another, again, ano;

    /*a*/
    opens fr.soat.vending.machine.model to another, again, ano;

    /*a*/
    uses fr.soat.vendinga/*a*/./*b*/machine.services.DrinksService;
}
