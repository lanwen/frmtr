# prettier-java fixtures

This directory contains the adopted Java formatting fixtures copied from
`jhipster/prettier-java` at commit `efac7313d4bfb21cc3d4b5323b3f30587fb2354c`.

Source paths inspected:

- `test/unit-test/*/_input.java`
- `test/unit-test/*/_output.java`
- `test/repository-test/*.ts`
- `test-samples/`
- `LICENSE`
- `NOTICE`

The copied fixture files are licensed by upstream under Apache License 2.0. The upstream
`LICENSE` and `NOTICE` files are preserved in this directory.

Each adopted fixture directory contains:

- `input.java`: the input formatted by frmtr.
- `prettier.output.java`: the upstream Prettier Java reference output.
- `frmtr.output.java`: the current frmtr output snapshot for the adopted input.

Some upstream samples are not JavaParser compilation units as copied. The adopted copies keep
the original expression or language-feature intent while adjusting the surrounding Java source
shape so `input.java` and `prettier.output.java` parse as compilation units. The verbatim
upstream copy remains under `../../upstream/prettier-java`.
