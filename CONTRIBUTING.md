# Contributing

`cloud-itonami-8121` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/robotics` and
`kotoba-lang/labor`. This repo holds the business blueprint and
operator contracts.

```bash
clojure -X:test
clojure -M:lint
```

## Rules
- Do not commit real client or building data.
- Keep robot dispatch and follow-up records behind the Building
  Cleaning Governor.
- Treat dispatch/chemical-handling workflows as high-risk: add tests
  for robot-safety gating, access scope, evidence, disclosure and
  audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
