# prettier-java fixtures

This directory contains the adopted Java formatting fixtures copied from
`jhipster/prettier-java` at commit `efac7313d4bfb21cc3d4b5323b3f30587fb2354c`.

Source paths inspected:

- `test/unit-test/*/input.java`
- `test/unit-test/*/prettier.output.java`
- `test/repository-test/*.ts`
- `test-samples/`
- `LICENSE`
- `NOTICE`

The copied fixture files are licensed by upstream under Apache License 2.0. The upstream
`LICENSE` and `NOTICE` files are preserved in this directory.

Each fixture directory contains:

- `input.java`: the input formatted by frmtr.
- `prettier.output.java`: the upstream Prettier Java reference output.

When JavaParser supports parsing the upstream syntax, the fixture directory also contains
`frmtr.output.java`: the current frmtr output snapshot for the input.

The `input.java` and `prettier.output.java` files are kept byte-for-byte aligned with the
preserved upstream copy under `../../upstream/prettier-java`. Some upstream samples use
syntax that the bundled JavaParser dependency does not parse yet; formatter assertions skip
those fixtures until JavaParser supports them.
