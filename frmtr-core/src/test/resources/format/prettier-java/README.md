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

When frmtr supports formatting the upstream syntax, the fixture directory also contains
`frmtr.output.java`: the current frmtr output snapshot for the input. Some active snapshots
cover inputs that JavaParser reports with parse problems but the formatter can recover under
the default parse-error behavior.

Fixture directories may also contain `frmtr.options.properties`: frmtr-only metadata that
selects formatter options when a fixture is compared against Prettier reference output.
Metadata is inherited from parent fixture directories to child fixture directories, so a
fixture family can share one option override. Metadata under fixtures whose upstream syntax
JavaParser does not parse yet is validated by tests but may remain future-facing until the
fixture joins the active compatibility assertion. Supported keys are `line-width`,
`require-pragma`, `lambda-arrow-parens`, and `binary-operator-position`.

The `input.java` and `prettier.output.java` files are kept byte-for-byte aligned with the
original upstream files. Some upstream samples use syntax that the bundled JavaParser
dependency does not parse yet; those fixtures are explicitly enumerated by
`PrettierJavaFixtureTest`. Fixtures that formatter recovery supports stay in formatter
assertions; fixtures outside supported recovery remain skipped until JavaParser or formatter
recovery supports them.

`frmtr-output-examples/unit-test` contains formatter snapshots that were produced from earlier
parseable adaptations of unsupported upstream fixtures. They are examples only, not active
compatibility snapshots.
