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
}
