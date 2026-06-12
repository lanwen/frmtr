package dev.lanwen.frmtr;

import java.util.List;

record UnsupportedFixture(String name, String source, List<String> expectedError) {
    @Override
    public String toString() {
        return name;
    }
}
