package tech.jhipster;

import java.util.Map;

public class StrangePrettierIgnore {
    private StrangePrettierIgnore() {}

    public static void drinkBeers() {
        // prettier-ignore
        Map<String, String> beers = Map.of( "beer1", "Gulden Draak", "beer2", "Piraat", "beer3", "Kapittel" );
        // not well formated here
        System.out.println(beers);
    }
}
