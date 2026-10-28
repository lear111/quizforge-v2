# Frozen compatibility oracle

`rules-runner.cjs` is an unmodified test-only copy of
`quizforge_web/core/server/rules-runner.cjs` at commit
`4522bb81c2126a699fcf72ce61d074dbc1264161` (MIT).
The five basic-type rules, schemas, manifests and example banks in
`../fixtures/` come from `quizforge_web/extensions/基础题型/` at the same commit.
Only the assets required to construct test inputs are included.

Production code does not import this directory; the Docker build excludes all tests.
Tests compare successful `data` field by field. The v1 HTTP envelope adds timing
and safe error codes/messages; legacy raw errors are deliberately not exposed.
