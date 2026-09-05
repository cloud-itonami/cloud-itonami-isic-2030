(ns fibremfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`fibremfg.operation` ->
  `fibremfg.advisor` -> `fibremfg.governor` -> `fibremfg.phase` ->
  `fibremfg.store`) through a scenario adapted from this repo's own
  `fibremfg.sim` demo driver (`clojure -M:dev:run`, confirmed BEFORE
  writing this file to produce a sensible ledger against the real seeded
  ids `batch-001`..`batch-003` / `spinning-line-001` /
  `extrusion-line-002` from `fibremfg.store/sample-data!`), and renders
  the resulting store deterministically.

  What is REAL RUNTIME OUTPUT on the page (read back out of the store
  after the graph runs, never hand-typed):
    - every production-batch and equipment row (`store/all-batches`,
      `store/all-equipment`), including the shipped-weight-kg that the
      committed shipment actually moved;
    - every scheduled maintenance window and its `MNT-######` draft
      record number (`store/all-maintenance`,
      `store/maintenance-history`) and every `SHP-######` shipment draft
      (`store/shipment-history`) -- assigned by
      `fibremfg.registry`, not chosen here;
    - every safety concern (`store/safety-concerns`);
    - every ledger fact, disposition and basis (`store/ledger`);
    - every HARD-hold rule keyword AND its Japanese detail string,
      lifted verbatim out of `fibremfg.governor`'s own violation maps.

  What is STATIC DESCRIPTION of a fixed contract (honestly labelled as
  such on the page): the `action-gate-rows` table, which restates this
  actor's own closed op/phase contract from `fibremfg.governor`'s
  `allowed-ops` and `fibremfg.phase`'s `phases` -- documentation of
  fixed behaviour, not runtime telemetry.

  Deterministic: no timestamps, no randomness, no wall-clock anywhere in
  the page content; `store/all-*` sort by `:id` and the ledger is
  append-ordered, so two consecutive runs against the same seed are
  byte-identical (verify by diffing two runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [fibremfg.store :as store]
            [fibremfg.registry :as registry]
            [fibremfg.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, then returns the store.

  Auto-commit (no human): a clean `:log-production-batch` patch on
  `batch-001` -- the only op in any phase's `:auto` set
  (`fibremfg.phase`).

  Human-approved commits: a `:schedule-maintenance` window on the
  verified+registered `spinning-line-001` (never auto at any phase, by
  construction), a `:flag-safety-concern` (always high-stakes, so the
  governor always escalates), and a `:coordinate-shipment` of 5000.0 kg
  against `batch-001` (within its own recorded 40000.0 kg production
  weight).

  HARD holds -- none of these ever reach a human, and the two marked
  PERMANENT can never be approved by anyone:
    :not-propose-effect       a caller whose own request `:effect` is
                              `:direct-write`, not `:propose`
    :unknown-op /             an `:actuate-spinning-line` op outside the
    :line-control-blocked     closed allowlist, whose proposal effect is
                              also outside the closed propose-shaped
                              effect set (PERMANENT)
    :line-actuate-blocked     a maintenance proposal declaring
                              `:actuate-line? true` -- direct
                              spinning/extrusion-line actuation
                              (PERMANENT, no phase and no approver can
                              override)
    :equipment-not-verified   maintenance against the UNVERIFIED,
                              unregistered `extrusion-line-002`
    :already-scheduled        the same `mnt-1` window scheduled twice
    :batch-not-verified       a shipment against the UNVERIFIED,
                              unregistered `batch-003`
    :shipment-weight-exceeded 1000.0 kg against `batch-002`, whose own
                              record already shows 7500.0 kg shipped of
                              8000.0 kg produced
    :invalid-fibre-type       a fabricated `:unobtainium` fibre type
    :invalid-denier           an implausible -5.0 denier reading

  Every number, id, rule and detail rendered by `render` below is read
  back out of this store afterwards -- nothing on the page is
  hand-typed."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean auto-commit (phase 3, governor-clean, no physical risk) ---
    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                       :patch {:fibre-type :polyester :last-assessed "2026-07-14"}})

    ;; --- escalate -> human plant supervisor approves -> commit ---
    (exec! actor "t2" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "spinning-line-001"
                               :maintenance-type :spinneret-inspection
                               :scheduled-date "2026-08-01" :actuate-line? false}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "spinning-line-001" :severity :moderate
                               :description "紡糸ライン周辺の溶剤臭気上昇、換気系統の点検要"}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :weight-kg 5000.0
                               :destination "buyer-yard-north"}})
    (approve! actor "t4")

    ;; --- HARD holds (never reach a human) ---
    (exec! actor "t5" {:op :log-production-batch :effect :direct-write :subject "batch-001"
                       :patch {:fibre-type :polyester}})

    (exec! actor "t6" {:op :actuate-spinning-line :effect :propose :subject "batch-001"})

    (exec! actor "t7" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                       :value {:equipment-id "extrusion-line-002"
                               :maintenance-type :die-inspection
                               :scheduled-date "2026-08-01" :actuate-line? false}})

    (exec! actor "t8" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                       :value {:equipment-id "spinning-line-001"
                               :maintenance-type :force-run
                               :scheduled-date "2026-09-01" :actuate-line? true}})

    (exec! actor "t9" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "spinning-line-001"
                               :maintenance-type :spinneret-inspection
                               :scheduled-date "2026-08-01" :actuate-line? false}})

    (exec! actor "t10" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :weight-kg 1000.0
                                :destination "buyer-yard-south"}})

    (exec! actor "t11" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :weight-kg 1000.0
                                :destination "buyer-yard-east"}})

    (exec! actor "t12" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:fibre-type :unobtainium}})

    (exec! actor "t13" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:denier -5.0}})
    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc
  "HTML-escape any interpolated value. Every cell on the page goes
  through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm
  "Readable name for a keyword/string/nil cell value."
  [v]
  (cond (nil? v) "—"
        (keyword? v) (name v)
        :else (str v)))

(defn- kg
  "Locale-independent kilogram rendering -- `str` on a double, never
  `format`, so the page is byte-identical on any machine."
  [v]
  (if (number? v) (str (double v)) "—"))

(defn- yes-no [b]
  (if b
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">NO</span>"))

;; ----------------------------- runtime-derived rows -----------------------------

(defn- batch-row
  "One production-batch row, entirely from the batch's own stored
  record plus `fibremfg.registry`'s own ground-truth gate -- the same
  predicate `fibremfg.governor` re-derives independently."
  [{:keys [id material fibre-type output-form denier weight-kg
           shipped-weight-kg last-assessed] :as b}]
  (str "        <tr><td><code>" (esc id) "</code></td>"
       "<td>" (esc material) "</td>"
       "<td>" (esc (nm fibre-type)) " / " (esc (nm output-form)) "</td>"
       "<td class=\"num\">" (esc (kg denier)) "</td>"
       "<td class=\"num\">" (esc (kg weight-kg)) "</td>"
       "<td class=\"num\">" (esc (kg shipped-weight-kg)) "</td>"
       "<td class=\"num\">"
       (esc (if (and (number? weight-kg) (number? shipped-weight-kg))
              (kg (- (double weight-kg) (double shipped-weight-kg)))
              "—"))
       "</td>"
       "<td>" (yes-no (registry/batch-ready? b)) "</td>"
       "<td>" (esc (nm last-assessed)) "</td></tr>"))

(defn- equipment-row
  "One equipment row -- `:verified?`/`:registered?` collapsed through
  `registry/equipment-ready?`, the gate the governor re-derives before
  any maintenance may be scheduled."
  [{:keys [id kind last-maintenance-date last-scheduled-maintenance-date] :as eq}]
  (str "        <tr><td><code>" (esc id) "</code></td>"
       "<td>" (esc (nm kind)) "</td>"
       "<td>" (esc (nm last-maintenance-date)) "</td>"
       "<td>" (esc (nm last-scheduled-maintenance-date)) "</td>"
       "<td>" (yes-no (registry/equipment-ready? eq)) "</td></tr>"))

(defn- maintenance-row
  "One committed maintenance window. `:maintenance-number` is the
  `MNT-######` id `fibremfg.registry/register-maintenance` assigned at
  commit time -- not chosen here."
  [{:keys [id equipment-id maintenance-type scheduled-date
           maintenance-number scheduled?]}]
  (str "        <tr><td><code>" (esc id) "</code></td>"
       "<td><code>" (esc (nm maintenance-number)) "</code></td>"
       "<td><code>" (esc equipment-id) "</code></td>"
       "<td>" (esc (nm maintenance-type)) "</td>"
       "<td>" (esc (nm scheduled-date)) "</td>"
       "<td>" (if scheduled?
                "<span class=\"ok\">scheduled (draft)</span>"
                "<span class=\"muted\">not scheduled</span>")
       "</td></tr>"))

(defn- draft-row
  "One registry draft record (maintenance or shipment). String keys --
  these come straight out of `fibremfg.registry`."
  [r]
  (str "        <tr><td><code>" (esc (get r "record_id")) "</code></td>"
       "<td>" (esc (get r "kind")) "</td>"
       "<td><code>" (esc (or (get r "maintenance_id") (get r "shipment_id"))) "</code></td>"
       "<td><code>" (esc (nm (get r "equipment_id"))) "</code></td>"
       "<td>" (if (get r "immutable")
                "<span class=\"ok\">immutable</span>"
                "<span class=\"warn\">mutable</span>")
       "</td></tr>"))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (str "        <tr><td><code>" (esc id) "</code></td>"
       "<td><code>" (esc (nm equipment-id)) "</code></td>"
       "<td>" (esc (nm severity)) "</td>"
       "<td>" (esc (nm description)) "</td></tr>"))

(defn- disposition-cell [{:keys [t]}]
  (case t
    :committed "<span class=\"ok\">committed</span>"
    :governor-hold "<span class=\"critical\">HARD hold</span>"
    :approval-rejected "<span class=\"critical\">rejected by approver</span>"
    :approval-requested "<span class=\"warn\">awaiting approval</span>"
    "<span class=\"muted\">—</span>"))

(defn- ledger-row [{:keys [t op subject basis] :as fact}]
  (str "        <tr><td>" (esc (nm t)) "</td>"
       "<td><code>" (esc (nm op)) "</code></td>"
       "<td><code>" (esc subject) "</code></td>"
       "<td>" (disposition-cell fact) "</td>"
       "<td>" (esc (if (seq basis) (str/join ", " (map nm basis)) "—")) "</td></tr>"))

(defn- hold-rows
  "One row per governor violation actually recorded in the ledger --
  the rule keyword and the governor's own detail string, verbatim."
  [ledger]
  (for [{:keys [op subject violations]} ledger
        :when (seq violations)
        {:keys [rule detail]} violations]
    (str "        <tr><td><code>" (esc (nm rule)) "</code></td>"
         "<td><code>" (esc (nm op)) "</code></td>"
         "<td><code>" (esc subject) "</code></td>"
         "<td>" (esc detail) "</td></tr>")))

;; ----------------------------- static contract description -----------------------------

(def ^:private action-gate-rows
  ;; STATIC description of this actor's own closed op contract
  ;; (`fibremfg.governor/allowed-ops`, `fibremfg.phase/phases`, README
  ;; `Ops`) -- documentation of fixed behaviour, not runtime telemetry,
  ;; so it is legitimately hand-described rather than derived from a
  ;; live run. Every other table on this page IS derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean</span> · fibre-type &amp; denier independently validated</td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval · never in any phase's <code>:auto</code> set</span> · equipment verified+registered re-derived · <span class=\"critical\">actuate-line? PERMANENTLY blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval · high-stakes by construction</span> · never gated on the equipment being verified</td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">phase-3: human approval</span> · batch verified+registered re-derived · shipment weight independently recomputed against the batch's own record</td></tr>"
   "        <tr><td><code>anything else</code></td><td><span class=\"critical\">HARD hold · outside the closed op allowlist</span></td></tr>"])

;; ----------------------------- document -----------------------------

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario). Every
  table below except `Action gate` is read back out of `db`."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        maintenance (store/all-maintenance db)
        concerns (vec (store/safety-concerns db))
        drafts (concat (store/maintenance-history db) (store/shipment-history db))
        holds (hold-rows ledger)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2030 &middot; man-made fibre plant operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of man-made fibres (ISIC 2030) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance scheduling &amp; safety concerns always human-approved · direct spinning/extrusion-line actuation permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot read back out of <code>fibremfg.store</code> after a real actor run (<code>clojure -M:dev:render-html</code>). Headroom is <code>weight-kg − shipped-weight-kg</code> from the batch's own record; the ground-truth gate is <code>fibremfg.registry/batch-ready?</code>, the same predicate <code>fibremfg.governor</code> re-derives independently before any shipment may be coordinated.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Material</th><th>Fibre / form</th><th>Denier</th><th>Produced (kg)</th><th>Shipped (kg)</th><th>Headroom (kg)</th><th>Verified + registered</th><th>Last assessed</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map batch-row batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Spinning / extrusion equipment</h2>\n"
     "    <p class=\"muted\">Maintenance may only ever be scheduled against equipment that is independently both <code>:verified?</code> and <code>:registered?</code> (<code>fibremfg.registry/equipment-ready?</code>) — never on the advisor's own report.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Last maintenance</th><th>Last scheduled window</th><th>Verified + registered</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map equipment-row equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Scheduled maintenance windows (this run)</h2>\n"
     "    <p class=\"muted\">A DRAFT window, never an actuation: this actor schedules, a human plant supervisor approves, and the spinning/extrusion line itself is never touched by this system. Record numbers are assigned by <code>fibremfg.registry/register-maintenance</code> at commit time.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Window</th><th>Record no.</th><th>Equipment</th><th>Type</th><th>Scheduled date</th><th>State</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq maintenance)
       (str (str/join "\n" (map maintenance-row maintenance)) "\n")
       "        <tr><td colspan=\"6\" class=\"muted\">no maintenance committed in this run</td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Registry draft records (this run)</h2>\n"
     "    <p class=\"muted\">The immutable draft records <code>fibremfg.registry</code> built for each committed maintenance window and shipment. Every certificate this actor produces is <em>unsigned</em> — signing is the human approver's act, not this actor's.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Subject</th><th>Equipment</th><th>Immutability</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq drafts)
       (str (str/join "\n" (map draft-row drafts)) "\n")
       "        <tr><td colspan=\"5\" class=\"muted\">no draft records in this run</td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns (this run)</h2>\n"
     "    <p class=\"muted\">Always high-stakes, always escalated to a human — and deliberately never gated on the referenced equipment being verified, so an administrative technicality can never suppress a safety report.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Severity</th><th>Description</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq concerns)
       (str (str/join "\n" (map concern-row concerns)) "\n")
       "        <tr><td colspan=\"4\" class=\"muted\">no safety concerns in this run</td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Man-Made Fibre Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">Static description of this actor's fixed op contract (<code>fibremfg.governor/allowed-ops</code>, <code>fibremfg.phase/phases</code>) — not runtime telemetry. Every other table on this page is read back out of a real run.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor holds (this run)</h2>\n"
     "    <p class=\"muted\">Every violation the governor actually recorded, with its own detail string verbatim. None of these reached a human. <code>:line-actuate-blocked</code> and <code>:line-control-blocked</code> are PERMANENT — no phase and no approver can ever override them.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Op</th><th>Subject</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (if (seq holds)
       (str (str/join "\n" holds) "\n")
       "        <tr><td colspan=\"4\" class=\"muted\">no holds in this run</td></tr>\n")
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and hold this scenario produced, in order. Basis is the advisor's cited fields for a commit, the violated rules for a hold.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>Generated at build time by <code>fibremfg.render-html</code> from a real "
     "<code>fibremfg.operation</code> actor run — deterministic, no wall-clock, no invented data.</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html :encoding "UTF-8")
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-batches db)) "batches,"
             (count (store/all-equipment db)) "equipment,"
             (count (store/all-maintenance db)) "maintenance windows,"
             (count (store/safety-concerns db)) "safety concerns,"
             (+ (count (store/maintenance-history db))
                (count (store/shipment-history db))) "draft records )")))
