/*a*/open/*b*/ /*a*/module/*b*/ inventory/*a*/./*b*/kiosk.ui {
    /*a*/requires/*a*/ java.desktop /*a*/;/*b*/
    requires inventory.kiosk.model;
    requires /*a*/transitive/*b*/ inventory.core;
    /*a*/ exports /*b*/ com.example.inventory.kiosk.model /*a*/to/*b*/ partner.module /*a*/,/*b*/ reporting.module /*c*/,/*d*/ audit.module /*a*/;/*b*/

    // opens
    /*a*/ opens /*b*/ com.example.inventory.kiosk.model /*a*/to/*b*/ partner.module /*a*/,/*b*/ reporting.module /*c*/,/*d*/ audit.module /*a*/;/*b*/


    // uses
    /*a*/uses/*b*/ com.example.inventory/*a*/./*b*/kiosk.services.MenuService /*a*/;/*b*/
}
