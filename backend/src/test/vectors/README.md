# Extension review compatibility vectors

From the repository root (Node 24.13.0 was used to generate these files):

```powershell
node backend/src/test/vectors/generate-extension-digests.mjs C:/path/to/quizforge_hub
mvn -f backend/pom.xml '-Dtest=ExtensionDigestTest,EcmaJsonTest' test
```

The supplied Git checkout must contain commit **83d6dfd**, the legacy Hub revision
specified by `docs/legacy/01-contracts.md`. The script extracts and imports the
original `cloud/extension-review.mjs` and its two original local dependencies
without modifying any source. Temporary sample directories are deleted afterwards.
Node and the old repository are only needed for regeneration, not for `mvn verify`.

`extension-digest-vectors.json` contains ten cases with every input file as
base64 and the old implementation's complete digest/inventory output. Five use
the exact partial extensions under `rule-runner/test/fixtures/`; they are test
fixtures, not production review approvals for the complete published extensions.
Five additional samples cover Unicode/emoji, escapes/lone surrogates, malformed
UTF-8 replacement, binary bytes, nested and integer-like key ordering, depth-first
directory traversal, duplicate JSON properties, null/missing id/version, falsy
non-null values, binding normalization (also 1200 levels deep), binary64 rounding and exponent boundaries.
Fixture inputs are read from their tracked Git blobs, avoiding platform-specific
`autocrlf` changes to rules and schema bytes during vector regeneration.

`ecma-number-vectors.json` stores raw IEEE-754 bits and Node's `JSON.stringify`
result for 2062 numbers: edge values and a deterministic seeded bit corpus. The
Java tests compare the complete decimal output, including negative zero,
subnormals, non-finite values and exponent syntax. Production code has no Node
dependency and uses only Jackson Core already supplied by Spring Boot for parsing.
