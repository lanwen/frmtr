/*a*/ open module /*b*/ inventory.kiosk.ui {
  /*a*/ requires /*a*/ java.desktop /*a*/;
  requires inventory.kiosk.model;
  requires /*a*/ transitive /*b*/ inventory.core;
  /*a*/ uses /*b*/ com.example.inventory.kiosk.services.MenuService /*a*/;
}
