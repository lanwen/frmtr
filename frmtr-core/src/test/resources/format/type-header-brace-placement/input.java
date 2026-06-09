package sample;

sealed interface WideSubject extends BaseSubject permits FirstSubject, SecondSubject, ThirdSubject, FourthSubject {
    String id();
}

final class WideImplementation extends BaseImplementation<FirstSubject, SecondSubject, ThirdSubject> implements FirstSubject, SecondSubject {
    String id() {
        return "wide";
    }
}
