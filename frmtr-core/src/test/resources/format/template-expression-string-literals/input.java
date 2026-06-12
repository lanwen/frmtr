class TemplateExpressionExamples {

    String greeting = "STR.\"My name is \\{name}\"";

    String equation = "STR.\"\\{x} + \\{y} = \\{x + y}\"";

    String offerNotice = "STR.\"You have a \\{getOfferType()} waiting for you!\"";

    String fileStatus = "STR.\"The file \\{filePath} \\{file.exists() ? does : does not} exist\"";

    String clockTime = "STR.\"The time is \\{DateTimeFormatter.ofPattern(HH:mm:ss).format(LocalTime.now())} right now\"";

    String sequence = "STR.\"\\{index++}, \\{index++}, \\{index++}, \\{index++}\"";

    String nestedFruit = "STR.\"\\{fruit[0]}, \\{STR.\\\"\\{fruit[1]}, \\{fruit[2]}\\\"}\"";

    String htmlReport = "STR text block with title and text interpolations";

    String areaTable = "FMT text block with zone name, width, height, and area interpolations";

    String personLookup = "DB.\"SELECT * FROM Person p WHERE p.last_name = \\{name}\"";
}
