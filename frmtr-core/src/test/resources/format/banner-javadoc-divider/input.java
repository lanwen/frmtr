package demo;

/*****
 * General Requests
 *****/
public class BannerJavadocDivider {

    /** *** Fetch Configuration *** */
    int fetch;

    /**
       * Normal multi-line Javadoc whose asterisk rows must keep reflowing canonically.
          *
        * @param value the value to handle
       * @return the handled value
     */
    int handle(int value) {
        return value;
    }
}
