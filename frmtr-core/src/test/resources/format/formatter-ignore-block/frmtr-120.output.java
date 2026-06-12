package tech.jhipster;

import java.util.Map;

public class BeverageMenuPrinter {

    private BeverageMenuPrinter() {}

    public static void printBeverageMenu() {
        // frmtr-ignore
        Map<String, String> beverages = Map.of(
      "draft", "Gulden Draak",
      "reserve", "Piraat",
      "seasonal", "Kapittel"
    );

        System.out.println(beverages); // intentionally uneven here
    }
}
