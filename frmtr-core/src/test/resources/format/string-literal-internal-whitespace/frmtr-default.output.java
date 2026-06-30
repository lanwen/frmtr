package sample;

import java.util.List;
import java.util.stream.Collectors;

final class StringLiteralInternalWhitespace {

    void renderSpaces(StringBuilder buffer, List<String> moduleNames, String indent) {
        buffer.append(
            moduleNames
                    .stream()
                    .map(name -> indent + "            " + name)
                    .collect(Collectors.joining(",\n"))
        );
    }

    void renderTabs(StringBuilder buffer, List<String> moduleNames, String indent) {
        buffer.append(
            moduleNames
                    .stream()
                    .map(name -> indent + "			" + name)
                    .collect(Collectors.joining(",\n"))
        );
    }

    void renderPlain(StringBuilder buffer, List<String> moduleNames, String indent) {
        buffer.append(
            moduleNames
                    .stream()
                    .map(name -> indent + " : " + name)
                    .collect(Collectors.joining(",\n"))
        );
    }
}
