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

## License

AGPL-3.0-or-later.
