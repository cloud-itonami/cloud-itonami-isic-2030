# cloud-itonami-isic-2030: Manufacture of man-made fibres

Open Business Blueprint for **ISIC Rev.5 2030**: manufacture of man-made fibres — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **man-made-fibre plant operations**: production-batch data logging (fibre-type/weight/denier/tenacity), spinning/extrusion-line maintenance scheduling, safety-concern flagging, and outbound fibre shipment coordination.

This repository designs a forkable OSS business for man-made-fibre
plant operations: run by a qualified operator so a plant keeps its own
operating records instead of renting a closed SaaS.

## Scope: spinning/extrusion of continuous filament or staple fibre

ISIC 2030 covers plants that spin/extrude/draw synthetic polymers
(polyester, nylon, acrylic, polypropylene, spandex, aramid) or a
regenerated-cellulose dope (viscose rayon, acetate, lyocell, modal,
cupro) into **continuous filament**, **staple fibre**, or **tow** —
ready to sell or ship to a downstream yarn spinner, weaver, knitter or
nonwovens producer. This is the closest domain sibling of
`cloud-itonami-isic-2013` (Manufacture of plastics and synthetic
rubber in primary forms) — both are upstream polymer-process plants
producing a primary form rather than a finished downstream product,
and share a very similar governed-actor shape (both are back-office
plant-operations coordinators with a verified/registered
equipment+batch gate and a permanent equipment-actuation block). This
plant's hazard profile is chemical-solvent exposure (e.g. carbon
disulfide in viscose wet-spinning, DMF in dry-spinning of acrylic or
spandex) and spinning/extrusion-line thermal/mechanical hazard, not
2013's monomer/exothermic-polymerization hazard.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — spinning/extrusion batch, denier/tenacity, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — spinning/extrusion-line maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard (solvent exposure)/equipment-safety concern (always escalates)
- `:coordinate-shipment` — outbound fibre shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(spinning/extrusion-line equipment, solvent exposure and
thermal/mechanical process hazard):

- Does NOT control spinning or extrusion-line equipment directly
- Does NOT make plant-safety or chemical-safety decisions (that's the plant supervisor's exclusive human authority)
- Does NOT actuate the spinning/extrusion line (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`fibremfg.operation/build`, a langgraph-clj StateGraph):
1. **`fibremfg.advisor`** (sealed intelligence node, `FibreAdvisor`): proposes decisions only, never commits
2. **`fibremfg.governor`** (independent, `Man-Made Fibre Plant Operations Governor`): validates against domain rules, re-derived from `fibremfg.registry`'s pure functions and `fibremfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct spinning/extrusion-line-equipment control)
     - Directly actuating the spinning/extrusion line (`:actuate-line? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped weight past its own logged production weight (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:fibre-type` value on a production-batch patch
     - No physically implausible `:denier` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`fibremfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`fibremfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
