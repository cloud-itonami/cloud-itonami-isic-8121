(ns buildingcleaningops.governor
  "BuildingCleaningGovernor -- the independent compliance layer that
  earns the BuildingCleaningAdvisor the right to commit. The advisor has
  no notion of whether a site is actually registered and verified (i.e.
  its client/site-contract is on file), whether its own proposed
  `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (cleaning-visit/task-completion service-record logging, crew/site
  cleaning-operation scheduling, cleaning-supply procurement
  coordination, safety-concern flagging). It NEVER performs or
  authorizes:
    - directly finalizing a chemical-safety-clearance decision
    - overriding a chemical-incompatibility warning
    - direct robot/equipment actuation or control (floor scrubbers,
      vacuums, supply-restocking carts)
    - safety-authority enforcement (OSHA-style citation, inspection
      clearance, license/permit suspension, compliance enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Site unverified          -- the target site's (client/
                                   site-contract) record must exist AND
                                   be independently confirmed
                                   `:registered?`/`:verified?` in the
                                   store before ANY proposal for it may
                                   commit or even escalate. Never trusts
                                   a proposal's own claim about the
                                   site -- re-derived from the site's own
                                   store record, the same 'ground truth,
                                   not self-report' discipline every
                                   sibling actor's governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST be
                                   `:propose`. Any other effect value
                                   is, by construction, a claim to
                                   directly actuate/commit outside
                                   governance -- HARD block, not merely
                                   low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   directly-finalizing-a-chemical-safety-
                                   clearance / overriding-a-chemical-
                                   incompatibility-warning / direct-
                                   equipment-actuation / safety-authority
                                   territory is a HARD, PERMANENT block --
                                   this actor's charter excludes that
                                   territory structurally, not as a
                                   rollout milestone. Evaluated
                                   UNCONDITIONALLY on every proposal. An
                                   op outside the closed four-op
                                   allowlist is the SAME failure mode (an
                                   advisor proposing something it was
                                   never authorized to propose) and is
                                   folded into this same check.

  IMPORTANT -- scope-excluded-terms are phrased as the finalization/
  execution ACTION (\"override the chemical-incompatibility warning\",
  \"finalize the chemical-safety clearance\"), never as a bare noun
  (\"chemical\"). A bare-noun term would self-trip on this actor's own
  legitimate `:flag-safety-concern` proposals, whose whole *purpose* is
  to report an observed chemical-incompatibility/slip-hazard/biohazard
  concern using that same vocabulary -- see
  `buildingcleaningops.advisor`'s `propose-safety-concern` and the
  `legitimate-safety-concern-is-not-scope-excluded` /
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion` tests.

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-safety-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `buildingcleaningops.phase` independently agrees:
      `:flag-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      procurement proposal always needs a human sign-off, even when the
      governor and phase would otherwise allow auto-commit."
  (:require [clojure.string :as str]
            [buildingcleaningops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example commercial building-cleaning supply-procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing
  an `:estimated-cost` above this value ALWAYS escalates to human
  sign-off, regardless of confidence or rollout phase."
  500.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-service-record :schedule-cleaning-operation
    :coordinate-supply-order :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  chemical-safety-clearance decision, overriding a chemical-
  incompatibility warning, direct robot/equipment actuation, or safety-
  authority enforcement. Every entry is phrased as the finalization/
  execution ACTION, never a bare noun (\"chemical\", \"biohazard\",
  \"slip\") -- a bare noun would match inside this actor's own
  legitimate `:flag-safety-concern` proposals, which exist precisely to
  *report* chemical-incompatibility/slip-hazard/biohazard observations
  using that vocabulary. Scanned across the proposal's op/summary/
  rationale/cites/value, never trusting the advisor's own framing of its
  intent."
  ["override the chemical-incompatibility warning" "overriding the chemical-incompatibility warning"
   "overrode the chemical-incompatibility warning" "overrides the chemical-incompatibility warning"
   "bypass the chemical-incompatibility warning" "bypassed the chemical-incompatibility warning"
   "waive the chemical-incompatibility warning" "ignore the chemical-incompatibility warning"
   "化学物質不適合警告を無視" "化学物質不適合警告を上書き" "薬品不適合警告を無視して発注"
   "finalize the chemical-safety clearance" "finalized the chemical-safety clearance"
   "finalizing the chemical-safety clearance" "chemical-safety clearance is hereby approved"
   "grant chemical-safety clearance" "granted chemical-safety clearance"
   "化学物質安全クリアランスを確定" "化学薬品安全確認を最終承認"
   "actuate the robot directly" "actuate equipment directly" "direct robot control override"
   "control the scrubber directly" "shut down the robot remotely" "power off equipment directly"
   "osha citation" "osha violation" "safety authority enforcement" "safety-authority enforcement"
   "inspection clearance" "regulatory clearance" "compliance sign-off"
   "license suspension" "license-suspension" "permit revocation" "permit-revocation"
   "労働基準監督署の是正勧告" "安全当局による是正命令" "営業許可取消" "違反是正命令"])

;; ----------------------------- checks -----------------------------

(defn- site-unverified-violations
  "The target site (client/site-contract) must exist AND be
  independently `:registered?`/`:verified?` in the store -- never trust
  the proposal's own `:site-id` claim without a store lookup."
  [{:keys [site-id]} st]
  (let [s (store/site st site-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :site-unverified
        :detail (str site-id " は未登録または未検証のサイト(クライアント契約) -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches directly-finalizing-a-chemical-safety-
  clearance / overriding-a-chemical-incompatibility-warning / direct-
  equipment-actuation / safety-authority territory, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "化学薬品安全クリアランスの直接確定/化学物質不適合警告の上書き/設備の直接操作/安全当局の判断領域に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost`
  above `supply-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a BuildingCleaningAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [site-id (or (:site-id proposal) (:site-id request))
        hard (into []
                   (concat (site-unverified-violations {:site-id site-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :site-id    (:site-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
