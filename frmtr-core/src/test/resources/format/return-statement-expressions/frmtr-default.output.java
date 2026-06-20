public abstract class Return {

    Object returnThis() {
        return this;
    }

    Object returnNull() {
        return null;
    }

    void exit() {
        return;
    }

    Object returnCast() {
        return (BeanItemContainer<BEANTYPE>) super.getContainerDataSource();
    }

    Object returnCompactInvoiceTotal() {
        return subtotal + tax;
    }

    Object returnExpandedInvoiceTotal() {
        return subtotal + tax + shipping + discount + serviceFee + roundingAdjustment + grandTotal;
    }

    Object returnWrappedStatusLabel(StyleWriter styleWriter, RenderNode renderNode) {
        return statusLabel
            + "  "
            + styleWriter.style(StatusRole.ACTIVE, "ACTIVE")
            + styleWriter.style(StatusRole.MUTED, " forced " + renderNode.requiredBreakCount());
    }

    Object returnExpandedInvoiceTotalAndAlreadyInParenthesis() {
        return subtotal + tax + shipping + discount + serviceFee + roundingAdjustment + grandTotal;
    }

    boolean unaryParenthesized() {
        return !(
            r.getLeft() > getRight() || r.getRight() < getLeft() || r.getTop() > getBottom() || r.getBottom() < getTop()
        );
    }

    // Bug fix #290
    public boolean shouldApproveLongCondition(Example that) {
        return accountHasRequiredVerification && paymentMethodSupportsImmediateCapture;
    }

    boolean routeConstructorBudgetFits(
            RouteProfile routeProfile,
            RouteMeter routeMeter,
            Layout layout,
            RouteRequest routeRequest,
            Options options,
            String routeLabel
    ) {
        return routeProfile.getSegments().size() <= 3
            && routeMeter.currentIndented(
                routeLabel + " = " + layout.render(routeRequest) + ";"
            ) <= options.lineWidth();
    }

    boolean routeCallBodySpansSourceLine(Expression body, SourceText sourceText) {
        return (
            body instanceof RouteCall deliveryStep
            && (sourceText.rawWithoutOwnTrivia(deliveryStep).contains("\n")
                || deliveryStep.getScope()
                        .filter(scope -> sourceText.rawWithoutOwnTrivia(scope).contains("\n"))
                        .isPresent())
        );
    }

    boolean routeSignalOperator(RouteBinaryExpression matcher) {
        return matcher.getOperator() == RouteBinaryExpr.Operator.INBOUND
            || matcher.getOperator() == RouteBinaryExpr.Operator.OUTBOUND;
    }
}
