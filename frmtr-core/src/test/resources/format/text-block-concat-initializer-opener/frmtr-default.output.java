package dev.example;

class TextBlockConcatInitializerOpenerSample {

    void render() {
        String diff = """
                diff --git origin frmtr
                --- origin
                +++ frmtr
                @@ -1,5 +1,5 @@
                 before
                -12345678901234567890
                +12345678901234567890123
                """
            + " \n"
            + """
                 after
                +short
                \\ No newline at end of file
                """;
    }
}
