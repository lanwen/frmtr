package routes;

class UriResolverRoute {

    void configure() {
        context.addRoutes(new RouteBuilder() {
            public void configure() {
                from("direct:start")
                        .setHeader("xslt_file", new ConstantExpression("xslt/staff/staff.xsl")) //
                        .recipientList(
                            new SimpleExpression("xslt:${header.xslt_file}?uriResolverFactory=#uriResolverFactory")
                        ) //
                        .to("mock:result");
            }
        });
    }
}
