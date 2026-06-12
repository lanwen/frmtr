public class Lambda {

    public void singleArgumentWithParens() {
        call((x) -> {
            System.out.println(x);
            System.out.println(x);
        });
    }

    public void singleArgumentWithoutParens() {
        call(x -> {
            System.out.println(x);
            System.out.println(x);
        });
    }

    public void multiArguments() {
        call((x, y) -> {
            System.out.println(x);
            System.out.println(y);
        });
    }

    public void multiParameters() {
        call((Object x, final String y) -> {
            System.out.println(x);
            System.out.println(y);
        });
    }

    public void emptyArguments() {
        call(() -> {
            System.out.println();
            System.out.println();
        });
    }

    public void onlyOneMethodInBodyWithCurlyBraces() {
        call(x -> {
            System.out.println(x);
        });
    }

    public void onlyOneMethodInBody() {
        call(x -> System.out.println(x));
    }

    public void lambdaWithoutBracesWhichBreak() {
        call(x ->
            inventoryRule.isVeryVeryVeryLongConditionTrue() &&
                inventoryRule.isAnotherVeryVeryLongConditionTrue()
        );
        dispatchJob(orderEvent -> "123456789012345678901234567890123456789012345678");
        dispatchJob(orderEvent -> validateOrder("123456789012345678901234567890123456"));
    }

    public void chainCallWithLambda() {
        Stream.of(1, 2)
            .map(n -> {
                // testing method
                return n * 2;
            })
            .collect(Collectors.toList());
    }

    public void lambdaWithLongListOfParameters() {
        final List<Integer> values = Stream.of(1, 2)
            .map(
                (
                    veryLongCustomerFilterParameter,
                    veryLongCustomerFilterParameter,
                    veryLongCustomerFilterParameter,
                    veryLongCustomerFilterParameter,
                    veryLongCustomerFilterParameter,
                    veryLongCustomerFilterParameter
                ) -> {
                    // testing method
                    return n * 2;
                }
            )
            .collect(Collectors.toList());

        final List<Integer> values = Stream.of(1, 2)
            .map((veryLongCustomerFilterParameter, veryLongCustomerFilterParameter, truncatedCustomerFilterParam) -> {
                // testing method
                return n * 2;
            })
            .collect(Collectors.toList());
    }

    public void shortLambdaAssignation() {
        V t = t -> refreshToken();
    }

    public void longLambdaAssignation() {
        V t = (
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterTail
        ) -> {
            // testing method
            return n * 2;
        };
    }

    public void callWithLambdaAndExtraParameter() {
        CompletableFuture.supplyAsync(() -> {
            // some processing
            return 2;
        }, executor);
    }

    public void testConstructor() {
        new Value((x) -> {
            // testing method
            return n * 2;
        });

        new Value((veryLongCustomerFilterParameter, veryLongCustomerFilterParameter) -> {
            // testing method
            return n * 2;
        });

        new Value(
            (
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter
            ) -> {
                // testing method
                return n * 2;
            }
        );
    }

    private static <T extends Group> Function<Constructor<?>, T> createInstance(
        Group entity
    ) {
        return ctor ->
            Try.of(a, () -> {
                @SuppressWarnings("unchecked")
                var ng = (T) ctor.newInstance(
                    entity.getId(),
                    entity.getSystemGenerated(),
                    entity.getVersionKey()
                );
                return ng;
            }).getOrElseThrow(ex -> new RuntimeException(ex));
    }

    void singleLambdaWithBlockLastArgument() {
        a.of(b, c, d, e -> {
            return f;
        });
    }

    void singleLambdaWithBlockLastArgumentAndLongArguments() {
        a.of(
            customerOrderRoutingContext,
            billingAccountSnapshot,
            customerSegmentSnapshot,
            deliveryWindowSnapshot,
            e -> {
                return f;
            }
        );

        this.a(
            customerOrderRoutingContext,
            billingAccountSnapshot,
            customerSegmentSnapshot,
            deliveryWindowSnapshot,
            e -> {
                return f;
            }
        );
    }

    void singleLambdaWithBlockLastArgumentAndLongLambdaArgument() {
        a.of(b, c, d, veryLongCustomerEligibilityPredicateParameter -> {
            return f;
        });
    }

    void singleLambdaWithBlockLastArgumentAndLongLambdaArguments() {
        a.of(b, (customerSegmentSnapshot, deliveryWindowSnapshot, eligibleFulfillmentStep) -> {
            return f;
        });
    }

    void multipleLambdaArguments() {
        a.of(
            b,
            c -> d,
            e -> {
                return f;
            }
        );
    }

    void argumentAfterLambdaWithBlock() {
        a.of(
            b,
            c,
            d,
            e -> {
                return f;
            },
            g
        );
    }

    void huggableArguments() {
        A.b().c(() -> {
            return d;
        });

        largeCustomerBatch(
            (billingAccountRecord, customerSegmentRecord, deliveryWindowRecord) -> eligibilityRuleSet.calculateDiscounts()
        );

        a.b(
            c -> d ->
                eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules)
        );

        a.b(c -> d && eligibility.compute() ? g && shipmentWindows.accountStatus() : j && taxRules.hasOverride());

        a.b(c ->
            d &&
                eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules) > 0
        );

        a.b(c, (c0, c1) ->
            d &&
                eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules) > 0
        );

        a.b(c -> eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules) > 0);

        a.b(
            c,
            (c0, c1) -> eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules) > 0
        );

        a.b(c ->
            d &&
                eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules)
        );

        a.b(c, (c0, c1) ->
            d &&
                eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules)
        );

        a.b(c -> eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules));

        a.b(c -> {
            eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules);
        });

        a.b((c0, c1) -> eligibility.compute(
                regionalInventory,
                shipmentWindows,
                accountStatus,
                pricingRules,
                taxRules
        ));

        a.b(c, (c0, c1) -> eligibility.compute(
                regionalInventory,
                shipmentWindows,
                accountStatus,
                pricingRules,
                taxRules
        ));

        a(
            // comment
            (b, c, d) -> e.f()
        );

        a(
            (
                // comment
                b,
                c,
                d
            ) -> e.f()
        );

        a(
            (
                b, // comment
                c,
                d
            ) -> e.f()
        );

        a(
            (
                b,
                c,
                d // comment
            ) -> e.f()
        );

        a(
            (
                b,
                c,
                d
                // comment
            ) -> e.f()
        );

        a(/* comment */ (b, c, d) -> e.f());

        a((/* comment */ b, c, d) -> e.f());

        a((b, /* comment */ c, d) -> e.f());

        a((b, c, d /* comment */) -> e.f());

        a(
            (
                b,
                c,
                d
                /* comment */
            ) -> e.f()
        );

        largeCustomerBatch(
            (
                billingAccountRecord,
                customerSegmentRecord,
                deliveryWindowRecord
                // comment
            ) -> eligibilityRuleSet.calculateDiscounts()
        );

        largeCustomerBatch(
            /* comment */
            (billingAccountRecord, customerSegmentRecord, deliveryWindowRecord) -> eligibilityRuleSet.calculateDiscounts()
        );

        largeCustomerBatch(
            /* comment */ (billingAccountRecord, customerSegmentRecord, deliveryWindowRecord) -> eligibilityRuleSet.calculateDiscounts()
        );

        a.b(
            c,
            (
                c0,
                c1
                // comment
            ) -> d && eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules) > 0
        );

        a.b(
            c,
            (
                c0,
                c1
                // comment
            ) -> eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules) > 0
        );

        a.b(
            c,
            (
                c0,
                c1
                // comment
            ) -> d && eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules)
        );

        a.b(
            (
                c0,
                c1
                // comment
            ) -> eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules)
        );

        a.b(
            c,
            (
                c0,
                c1
                // comment
            ) -> eligibility.compute(regionalInventory, shipmentWindows, accountStatus, pricingRules, taxRules)
        );
    }

    void lambdaWithLeadingComments() {
        System.out.println(
            List.of(1, 2, 3)
                .stream()
                .map(
                    // a very long comment which explains the beatifullness of multiplication by 2
                    // yes this is very important
                    v -> v * 2
                )
                .collect(Collectors.summingInt(v -> v))
        );
    }

    void lambdaWithTrailingComments() {
        System.out.println(
            List.of(1, 2, 3)
                .stream()
                .map(
                    v -> v * 2
                    // a very long comment which explains the beatifullness of multiplication by 2
                    // yes this is very important
                )
                .collect(Collectors.summingInt(v -> v))
        );
    }

    void lambdaInParentheses() {
        (dispatchJob ->
            orderEvent.validateOrder().deliveryPlan().eligibility().compute());
    }
}

class T {

    T() {
        super(x -> {
            // testing method
            return n * 2;
        });
    }

    T() {
        super((x, y) -> {
            // testing method
            return n * 2;
        });
    }

    T() {
        super(
            (
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter,
                veryLongCustomerFilterParameter
            ) -> {
                // testing method
                return n * 2;
            }
        );
    }

    T() {
        super((veryLongCustomerFilterParameter, veryLongCustomerFilterParameter, truncatedCustomerFilterState) -> {
            // testing method
            return n * 2;
        });
    }

    T() {
        super(a, () -> {
            return b;
        });
    }
}

enum Enum {
    VALUE(x -> {
        // testing method
        return n * 2;
    }),
    VALUE((x, y) -> {
        // testing method
        return n * 2;
    }),
    VALUE(
        (
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter,
            veryLongCustomerFilterParameter
        ) -> {
            // testing method
            return n * 2;
        }
    ),
    VALUE(
        (veryLongCustomerFilterParameter, veryLongCustomerFilterParameter, truncatedCustomerFilterState) -> {
            // testing method
            return n * 2;
        }
    ),
    VALUE(x -> {
        // testing method
        return n * 2;
    }, other),
}
