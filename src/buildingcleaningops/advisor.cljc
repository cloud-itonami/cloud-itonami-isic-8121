(ns buildingcleaningops.advisor
  "BuildingCleaningAdvisor -- the *contained intelligence node* for the
  ISIC-8121 general-building-cleaning operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: service-record logging, cleaning-operation scheduling,
  supply-order coordination, and safety-concern flagging. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER a
  direct actuation -- every proposal's `:effect` is always `:propose`.
  Every output is censored downstream by `buildingcleaningops.governor`
  before anything touches the SSoT.

  This advisor NEVER drafts a direct finalization of a chemical-safety-
  clearance decision, an override of a chemical-incompatibility warning,
  direct robot/equipment actuation, or any other safety-authority action
  (inspection clearance, license/permit decisions, compliance
  enforcement) -- those are permanently out of scope for this actor, not
  merely un-implemented. `buildingcleaningops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal for
  exactly this failure mode (a compromised or confused advisor drifting
  into scope it must never touch) and HARD-holds it, regardless of
  confidence or op.

  NOTE the deliberate wording discipline below: every default proposal's
  `:summary`/`:rationale` text -- including `propose-safety-concern`,
  whose entire job is to *report* chemical-incompatibility/slip-hazard/
  biohazard observations -- avoids the finalization/execution ACTION
  vocabulary the governor's `scope-excluded-terms` scans for (\"finalize
  ... clearance\", \"override ... warning\", direct equipment-actuation
  phrasing). Reporting a concern is always in scope; only the act of
  finalizing/overriding is excluded. See
  `buildingcleaningops.governor-test`'s
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :site-id    str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-service-record
  "Draft a cleaning-visit/task-completion service-record log entry. Pure
  logging of observed work (areas cleaned, tasks completed, consumables
  used) -- never a chemical-safety-clearance judgement."
  [_db {:keys [site-id patch]}]
  {:op         :log-service-record
   :site-id    site-id
   :summary    (str site-id " の清掃実施記録を記録: " (pr-str (keys patch)))
   :rationale  "清掃業務の実施内容・完了時刻・使用資材の観察記録のみ。安全に関する最終判断は行わない。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.93})

(defn- propose-cleaning-operation
  "Draft a crew/site cleaning-operation scheduling proposal (a
  calendar/roster entry, never a direct robot/equipment actuation)."
  [_db {:keys [site-id patch]}]
  {:op         :schedule-cleaning-operation
   :site-id    site-id
   :summary    (str site-id " の清掃オペレーション予定を提案: " (pr-str (keys patch)))
   :rationale  "清掃クルーとサイトのスケジュール調整提案のみ。設備の遠隔操作は行わない。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft a cleaning-supply procurement coordination request (detergent,
  towels, protective equipment, restocking carts -- never a finalized
  purchase order; a human always confirms procurement)."
  [_db {:keys [site-id patch]}]
  {:op         :coordinate-supply-order
   :site-id    site-id
   :summary    (str site-id " に関連する清掃用消耗品の調達オーダーを提案: " (pr-str (keys patch)))
   :rationale  "洗剤・タオル・保護具などの清掃用消耗品の調達調整提案のみ。確定発注は人間が行う。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence 0.90})

(defn- propose-safety-concern
  "Surface a safety concern (chemical-incompatibility, slip-hazard,
  biohazard) for HUMAN triage. This op ALWAYS escalates in
  `buildingcleaningops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.

  Reporting a chemical-incompatibility/slip-hazard/biohazard OBSERVATION
  is exactly this op's legitimate purpose and must never be confused
  with directly finalizing a chemical-safety-clearance decision or
  overriding a chemical-incompatibility warning (both permanently
  excluded, see `buildingcleaningops.governor`)."
  [_db {:keys [site-id patch]}]
  {:op         :flag-safety-concern
   :site-id    site-id
   :summary    (str site-id " のサイト安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "化学物質の不適合・滑走危険・生物学的危険に関する観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [site-id]
   :effect     :propose
   :value      (merge {:site-id site-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-service-record (propose-service-record _db request)
                   :schedule-cleaning-operation (propose-cleaning-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-safety-concern (propose-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually overrode the chemical-incompatibility warning and finalized the chemical-safety clearance directly")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :site-id (:site-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
