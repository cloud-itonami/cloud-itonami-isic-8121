# Business Model: Community Building Cleaning Operations

## Classification
- Repository: `cloud-itonami-8121`
- ISIC Rev.5: `8121` — general cleaning of buildings
- Social impact: crew worker safety, public health, local jobs

## Customer
- independent/community building-cleaning companies needing an
  auditable chemical-handling and access-scope platform
- property managers and institutions needing verifiable service and
  compliance records
- regulators needing verifiable safety-data-sheet and access-scope
  compliance records
- programs that cannot accept closed, unauditable janitorial-
  operations platforms

## Offer
- chemical-handling and building-access-scope management
- robotics-assisted floor scrubbing/vacuuming and supply restocking
- crew registration, dispatch and follow-up records
- client billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per building/site
- support retainer with SLA
- floor-scrubbing/vacuuming robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (a chemical-handling task outside verified
  safety-data-sheet scope, an access request outside verified
  building scope, an unverified follow-up record) require human
  sign-off
- crews cannot be dispatched outside verified access scope
- follow-up records require verified evidence
- sensitive client and building data stays outside Git
