class Switch {

    void simple(Answer decision) {
        switch (decision) {
            case YES:
                System.out.println("YES");
                break;
            case NO:
                System.out.println("NO");
                break;
            default:
                break;
        }
    }

    // Bug fix: #276
    public int method() {
        switch ("abc") {
            case "a":
                return 1;
            case "b":
                return 2;
            case "c":
                return 3;
            // default case
            default:
                return 3;
        }
    }

    // Bug fix: #276
    public int method2() {
        switch ("abc") {
            case "a":
                return 1;
            case "b":
                return 2;
            // case c
            case "c":
                return 3;
            default:
                return 3;
        }
    }

    // Bug fix: #357
    public String shouldWrapEvenForSmallSwitchCases() {
        switch (decision) {
            case "YES":
                return "YES";
            default:
                return "NO";
        }
    }

    void switchCaseWithBlock1() {
        switch (state) {
            case 0: {
            }
            default: {
            }
        }
    }

    void switchCaseWithBlock2() {
        switch (state) {
            case 0: {
                open();
            }
            default: {
                close();
            }
        }
    }

    void switchCaseWithBlock3() {
        switch (state) {
            case 0:
                {
                    open();
                }
                {
                    close();
                }
            default:
                {
                    archive();
                }
                {
                    notifyOwner();
                }
        }
    }

    void switchCaseWithBlock4() {
        switch (state) {
            case 0:
                open();
                {
                    close();
                }
            default:
                archive();
                {
                    notifyOwner();
                }
        }
    }

    void switchCaseWithBlock5() {
        switch (state) {
            case 0:
                {
                    open();
                }
                close();
            default:
                {
                    archive();
                }
                notifyOwner();
        }
    }

    // Switch rules
    static void howManyAgain(int k) {
        switch (k) {
            case 1 -> System.out.println("one");
            case 2 -> {
                System.out.println("two");
            }
            case 3 -> throw new Exception(e);
            default -> throw new Exception(e);
        }
    }

    public Location getAdjacentLocation(Direction direction) {
        switch (direction) {
            case NORTH:
                return new Location(this.x, this.y - SnakeUtils.GRID_SIZE);
            case SOUTH:
                return new Location(this.x, this.y + SnakeUtils.GRID_SIZE);
            case EAST:
                return new Location(this.x + SnakeUtils.GRID_SIZE, this.y);
            case WEST:
                return new Location(this.x - SnakeUtils.GRID_SIZE, this.y);
            case NONE:
            // fall through
            default:
                return this;
        }
    }

    public void multipleCaseConstants(RoutingMode routingMode) {
        switch (routingMode) {
            case LOCAL -> System.out.println("Local route!");
            case REMOTE, HYBRID -> System.out.println("Not local!");
            case REMOTE,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID,
                    HYBRID -> System.out.println("Not local!");
        }
    }

    public void caseConstantsWithComments(RoutingMode routingMode) {
        switch (routingMode) {
            case REMOTE /* remote */, HYBRID -> System.out.println("Not local!");
            case REMOTE /* remote */, /* hybrid */ HYBRID -> System.out.println("Not local!");
            case REMOTE, /* hybrid */ HYBRID -> System.out.println("Not local!");
        }
    }

    static String formatterPatternSwitchRules(Object value) {
        return switch (value) {
            case Integer i -> String.format("int %d", i);
            case Long l -> String.format("long %d", l);
            case Double d -> String.format("double %f", d);
            case String s -> String.format("String %s", s);
            case FALLBACK -> String.format("FALLBACK %s", value);
            case null -> String.format("Null !");
            case null, default -> String.format("Default !");
            default -> value.toString();
        };
    }

    static String formatterPatternSwitch(Object value) {
        return switch (value) {
            case Integer i:
                yield "It is an integer";
            case Double d:
            case String s:
                yield "It is an integer";
        };
    }

    static String shouldFormatSwitchBlocksWithEmptyLastBlock(Object value) {
        switch (state) {
            case READY:
                return true;
            case DONE:
                return false;
            default:
        }

        log.info("Done !");
    }

    void switchRulesWithComments() {
        switch (state) {
            case queued ->
                // comment
                complete;
            case OrderEvent event ->
                // comment
                publish;
            case failed ->
                // comment
                throw new RuntimeException();
        }
    }

    void emptyBlocks() {
        switch (state) {
        }
        switch (state) {
            case 1: {
            }
        }
        switch (state) {
            case 1 -> {}
        }
    }
}
