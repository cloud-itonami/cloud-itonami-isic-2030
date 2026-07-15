# ADR-0001: FibreAdvisor ⊣ Man-Made Fibre Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2030` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2030` publishes an OSS blueprint for man-made-fibre
**plant operations coordination** (production-batch fibre-type/weight/
denier/tenacity data logging, spinning/extrusion-line maintenance
scheduling, safety-concern flagging, and outbound fibre shipment
coordination). Like every actor in this fleet, the blueprint alone is
not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the
same langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2013` (Manufacture of
plastics and synthetic rubber in primary forms): both are back-office
coordination actors for a fixed processing PLANT with heavy
manufacturing equipment and a real physical safety dimension, and both
share the same four-op shape (`:log-production-batch`/`:schedule-
maintenance`/`:flag-safety-concern`/`:coordinate-shipment`) and the
same two-entity verified/registered gate structure (equipment for
maintenance scheduling, batch for shipment coordination). The two
verticals are, however, distinct plants producing distinct primary
forms: 2013 polymerizes/compounds monomers into plastics resin and
synthetic rubber as pellets/granules/powder/flake/latex, while 2030
spins/extrudes/draws a synthetic polymer melt/solution or a
regenerated-cellulose dope into continuous filament, staple fibre, or
tow. This build mirrors 2013's architecture closely but adapts the
hazard profile and equipment/product vocabulary to the fibre plant:
2030's central physical hazard is chemical-solvent exposure (e.g.
carbon disulfide in viscose wet-spinning, DMF in dry-spinning of
acrylic or spandex) and spinning/extrusion-line thermal/mechanical
hazard, rather than 2013's monomer-exposure/exothermic-polymerization
hazard; 2030's permanent equipment-actuation block guards a
spinning/extrusion LINE (`:actuate-line?`) rather than a
polymerization/compounding REACTOR (`:actuate-reactor?`); and 2030's
production-batch record declares a `:fibre-type` (spanning both
synthetic and regenerated-cellulose fibres, per ISIC 2030's own
combined "man-made fibres" scope) and a `:denier` (fibre linear
density), rather than 2013's `:polymer-grade`/`:off-spec-rate-percent`.

This vertical has NO pre-existing `kotoba-lang/fibremfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`fibremfg.registry` (equipment/batch verification, shipment-weight
recompute, fibre-type validation, denier plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most directly
`cloud-itonami-isic-2013`'s `resinmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:man-made-fibre-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "man-made-fibre-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external man-made-fibre-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
man-made-fibre vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-weight / fibre-type
/ denier validation functions live as pure functions in
`fibremfg.registry` and are re-verified independently by
`fibremfg.governor` — the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2013`'s `resinmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of man-made-fibre
plant operations. It does NOT:
- Control spinning or extrusion-line equipment directly
- Make plant-safety or chemical-safety decisions (exclusive to the human plant supervisor)
- Actuate the spinning/extrusion line

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: man-made-fibre manufacturing is a
safety-critical domain (chemical-solvent exposure, spinning/extrusion-
line thermal/mechanical hazard, heavy material handling).
Safety-concern flagging NEVER auto-commits. All safety concerns
escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (chemical-hazard concern, solvent-exposure,
equipment-safety concern, crew fatigue) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" — it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2013`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date weight plus the proposal's own
claimed weight would exceed the batch's own recorded production
weight — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`fibremfg.governor`, mirroring `cloud-itonami-isic-2013`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's weight must independently recompute within the batch's own logged production weight
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct spinning/extrusion-line-equipment control or line actuation is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Man-made-fibre plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or line actuation. Safety concerns
are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and line operation remain
human-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-2030`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-weight-exceeded, line-actuate-blocked,
  already-scheduled, invalid-fibre-type, invalid-denier).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
