(ns fibremfg.registry
  "Pure-function domain logic for the man-made-fibre plant-operations
  coordination actor -- equipment/batch verification, shipment-weight
  recompute, fibre-type validation, denier plausibility validation, and
  draft maintenance-schedule/shipment-coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/fibremfg`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by
  `fibremfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `resinmfg.registry/shipment-weight-exceeded?` from
  `cloud-itonami-isic-2013`, this actor's closest domain analog):
  never trust a proposal's own self-reported weight/status when the
  inputs needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating a spinning/extrusion
  line or dispatching a real freight carrier (this actor NEVER does
  either -- see README `What this actor does NOT do`).

  SCOPE NOTE: ISIC 2030 (this actor) covers man-made fibre
  manufacturing -- spinning/extrusion/drawing lines that turn a
  synthetic polymer melt/solution (polyester, nylon, acrylic,
  polypropylene, spandex, aramid) or a regenerated-cellulose dope
  (viscose rayon, acetate, lyocell, modal, cupro) into continuous
  filament, staple fibre, or tow. This is the closest domain sibling of
  `cloud-itonami-isic-2013` (Manufacture of plastics and synthetic
  rubber in primary forms) -- both are upstream polymer-process plants
  producing a primary form (resin pellets/granules/latex vs. fibre
  filament/staple/tow) rather than a finished downstream product, and
  both share the same governed-actor shape (a verified/registered
  equipment+batch gate and a permanent equipment-actuation block). The
  two verticals differ in hazard profile and output vocabulary: 2013's
  hazard is monomer exposure / exothermic polymerization runaway,
  while 2030's hazard is chemical-solvent exposure (e.g. carbon
  disulfide in viscose wet-spinning, DMF in dry-spinning of acrylic or
  spandex) and spinning/extrusion-line thermal/mechanical hazard;
  2013's batch record declares a `:polymer-grade` and an
  `:off-spec-rate-percent`, while 2030's batch record declares a
  `:fibre-type` and a `:denier` (fibre linear density, grams per 9000
  metres -- the standard textile-industry fineness measure) plus an
  informational `:tenacity-cn-per-tex` (breaking strength) field.")

;; ----------------------------- constants -----------------------------

(def valid-fibre-types
  "The closed set of fibre-type values a production-batch record may
  declare -- synthetic (polymer-melt/solution-spun) fibres AND
  regenerated-cellulose (man-made from a natural polymer) fibres,
  matching ISIC 2030's own 'man-made fibres' scope (synthetic AND
  regenerated/semi-synthetic, unlike a natural-fibre producer).
  Anything else is a fabricated/unrecognized fibre type -- the
  governor HARD-holds rather than let an invented type pass through."
  #{;; synthetic (melt- or solution-spun from a synthetic polymer)
    :polyester :nylon-6 :nylon-66 :acrylic :polypropylene :spandex :aramid
    ;; regenerated / semi-synthetic (spun from a regenerated-cellulose dope)
    :viscose-rayon :acetate :lyocell :modal :cupro})

(def valid-output-forms
  "The closed set of PRIMARY FORM shapes this plant's own output may
  take -- continuous filament (a single long strand or filament yarn),
  staple fibre (cut to a discrete, natural-fibre-like length), or tow
  (an untwisted bundle of continuous filaments, the intermediate form
  before cutting/crimping into staple). A man-made-fibre plant never
  ships a downstream woven/knitted/nonwoven finished good (that is
  ISIC 13xx/14xx's own downstream scope, not this actor's)."
  #{:filament :staple :tow})

(def denier-min-value
  "Physical floor for a batch's own denier (linear density) reading --
  denier is grams per 9000 metres of fibre; zero or negative is not a
  real fibre (strictly greater than this floor, never equal)."
  0.0)

(def denier-max-value
  "Physical ceiling for a batch's own denier reading -- generous enough
  to cover a large acrylic/carbon-fibre-precursor tow bundle (real
  commercial tow deniers commonly run into the hundreds of thousands),
  while still rejecting an implausible/fabricated sensor reading."
  1000000.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its fibre-type/weight/denier claims have actually been
  QC-inspected, not merely logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-weight-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-kg` + `new-weight-kg` exceed `batch`'s own
  recorded `:weight-kg` (the batch's own logged production weight)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-weight-kg]
  (let [capacity (:weight-kg batch)
        so-far (:shipped-weight-kg batch 0.0)]
    (and (number? capacity)
         (number? new-weight-kg)
         (> (+ (double so-far) (double new-weight-kg)) (double capacity)))))

(defn fibre-type-valid?
  "Is `fibre-type` one of the closed, known fibre-type values
  (synthetic or regenerated-cellulose)? nil/blank is treated as
  invalid (a production-batch patch must declare a real fibre type,
  not omit it silently)."
  [fibre-type]
  (contains? valid-fibre-types fibre-type))

(defn denier-valid?
  "Is `denier` a physically plausible batch linear-density (fineness)
  reading? Rejects nil, non-numbers, zero/negative values, and values
  beyond `denier-max-value` -- a fabricated or sensor-error reading,
  never let through as a real batch fact."
  [denier]
  (and (number? denier)
       (> (double denier) denier-min-value)
       (<= (double denier) denier-max-value)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  spinning/extrusion-line maintenance window against a verified,
  registered piece of equipment. Pure function -- does not actuate the
  spinning/extrusion line or execute any maintenance; it builds the
  RECORD a plant coordinator would keep. `fibremfg.governor`
  independently re-verifies the equipment's own verified/registered
  ground truth, and permanently blocks any attempt to directly actuate
  the spinning/extrusion line (see README `Actuation`), before this is
  ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound man-made-fibre shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `fibremfg.governor` independently re-verifies the shipment's
  own claimed weight against `shipment-weight-exceeded?`, before this
  is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
