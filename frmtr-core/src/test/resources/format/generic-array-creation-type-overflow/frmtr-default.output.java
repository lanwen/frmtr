package sample;

final class GenericArrayCreationTypeOverflow {

    void allocate() {
        var compactGenericArray = new Holder<FirstArgument, SecondArgument>[] {
            firstHolderInstance,
            secondHolderInstance,
        };
        var overflowingGenericArray = new VeryLongGenericContainerTypeNameForOverflowCaseExtended<
            FirstTypeArgumentThatIsRatherLong,
            SecondTypeArgumentAlsoQuiteLong
        >[] {
            firstContainerInstance,
            secondContainerInstance,
        };
    }
}
