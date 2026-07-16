# cloud-itonami-8121

Open Business Blueprint for **ISIC Rev.5 8121**: general cleaning of
buildings (routine janitorial and floor-care services for commercial
and institutional buildings).

This repository designs a forkable OSS business for community
building-cleaning operations: chemical-handling and access-scope
management, robotics-assisted floor scrubbing/vacuuming and
supply-restocking, and crew dispatch/follow-up records — run by a
qualified operator so a cleaning company keeps its own safety and
compliance history instead of renting a closed janitorial-operations
platform.

## Scope note: general cleaning, not specialized cleaning or landscape care

`cloud-itonami-isic-8129` ("Other building and industrial cleaning
activities" -- specialized cleaning such as chimney sweeping,
disinfection/decontamination and industrial cleaning) and
`cloud-itonami-isic-8130` ("Community Landscape Care and Maintenance
Operations") remain separate, undertaken (or reserved) verticals in
this fleet's own ISIC coverage. This repository is deliberately
scoped to routine, general-purpose janitorial and floor-care service
for buildings: vacuuming, mopping, floor scrubbing/waxing, restroom
and common-area servicing, and supply restocking. Building-cleaning
work carries its own safety and compliance regime distinct from
specialized cleaning (OSHA's Bloodborne Pathogens Standard and
Hazard Communication Standard for chemical handling; ISSA's Cleaning
Industry Management Standard as the common industry certification
framework; many jurisdictions apply prevailing-wage/living-wage rules
specifically to janitorial contracts on public buildings).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (autonomous floor
scrubbers/vacuums, supply-restocking carts) operate under an actor
that proposes actions and an independent **Building Cleaning
Governor** that gates them. The governor never dispatches a chemical-
handling or restricted-access job itself; `:high`/`:safety-critical`
actions (a chemical-handling task outside verified safety-data-sheet
scope, an access request outside verified building scope, a follow-up
record without verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + chemical-handling/access scope + crew registration
        |
        v
Building Cleaning Advisor -> Building Cleaning Governor -> match, dispatch, follow-up record, or human approval
        |
        v
robot actions (gated) + service record + audit ledger
```

No automated advice can dispatch a chemical-handling job the governor
refuses, match an unregistered crew member to a restricted-access
job, or publish a follow-up record without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `8121`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — crew registration, dispatch, timesheet/follow-up contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Actor implementation (`buildingcleaningops`)

An `langgraph-clj` StateGraph actor implementing the operations-
coordination layer of the Core Contract above: `BuildingCleaningAdvisor`
(the contained intelligence node) → `BuildingCleaningGovernor`
(independent censor) → staged-rollout phase gate → commit | hold |
human-approval escalation, with an append-only audit ledger.

- **Closed proposal-op allowlist**: `log-service-record`,
  `schedule-cleaning-operation`, `coordinate-supply-order`,
  `flag-safety-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Site unverified** — the target site's client/site-contract
     record must exist AND be independently registered/verified in the
     store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — directly finalizing a chemical-safety-
     clearance decision, overriding a chemical-incompatibility warning,
     direct robot/equipment actuation, and safety-authority enforcement
     are permanently blocked. The closed op-allowlist NEVER includes an
     op that directly finalizes a chemical-safety-clearance decision or
     overrides an incompatibility warning — those are always a hard,
     permanent block, never auto-commit-eligible.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-safety-concern` — ALWAYS escalates, regardless of confidence
    or phase. A "flag a concern" op is never auto-commit-eligible and
    never finalizes a chemical-safety decision itself — it only
    surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold — a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: service-record logging only (approval-gated)
  - Phase 2: + cleaning-operation scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (safety concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log
  entry.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:dev:run
```

### Test suite

- `test/buildingcleaningops/governor_test.clj` — unit tests of governor
  hard checks, cost-threshold escalation, and scope exclusion
  (including a dedicated regression test asserting the default
  mock-advisor proposals never self-trip the scope-exclusion check)
- `test/buildingcleaningops/advisor_test.clj` — advisor proposal shape
  and consistency
- `test/buildingcleaningops/phase_test.clj` — rollout phase logic
- `test/buildingcleaningops/governor_contract_test.clj` — full graph
  integration, audit trail
- `test/buildingcleaningops/store_contract_test.clj` — Store protocol
  and MemStore implementation

### Modules

- `buildingcleaningops.store` — SSoT (MemStore, String-keyed site
  directory, append-only ledger)
- `buildingcleaningops.advisor` — contained intelligence node (mock +
  real-LLM seam)
- `buildingcleaningops.governor` — independent compliance layer
- `buildingcleaningops.phase` — staged rollout (0→3)
- `buildingcleaningops.operation` — langgraph-clj StateGraph
- `buildingcleaningops.sim` — demo driver

## License

AGPL-3.0-or-later.
