class TemplateExpression {

    String info = "STR.\"My name is \\{name}\"";

    String sum = "STR.\"\\{x} + \\{y} = \\{x + y}\"";

    String offer = "STR.\"You have a \\{getOfferType()} waiting for you!\"";

    String conditional = "STR.\"The file \\{filePath} \\{file.exists() ? does : does not} exist\"";

    String time = "STR.\"The time is \\{DateTimeFormatter.ofPattern(HH:mm:ss).format(LocalTime.now())} right now\"";

    String data = "STR.\"\\{index++}, \\{index++}, \\{index++}, \\{index++}\"";

    String nested = "STR.\"\\{fruit[0]}, \\{STR.\\\"\\{fruit[1]}, \\{fruit[2]}\\\"}\"";

    String html = "STR text block with title and text interpolations";

    String table = "FMT text block with zone name, width, height, and area interpolations";

    String query = "DB.\"SELECT * FROM Person p WHERE p.last_name = \\{name}\"";
}
