#!/usr/bin/env bb

(ns route-card
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def usage-text
  (str "Route a new board card, gated on an operator approval.\n"
       "\n"
       "Usage:\n"
       "  route_card.sh propose <project-root> <task-name> <note-file>\n"
       "  route_card.sh commit <proposal-id> [--approval <record-id>]\n"
       "  route_card.sh list [--project <project>] [--forge-root <root>] [--json]\n"
       "  route_card.sh detail <proposal-id> [--forge-root <root>] [--json]\n"
       "  route_card.sh approve <proposal-id> [--approval <record-id>]\n"
       "                           [--operator-said <words>] [--forge-root <root>] [--json]\n"
       "\n"
       "propose records the proposal; commit creates the card only when the\n"
       "operator's own words are affirmative. Each approval is single-use.\n"
       "list, detail and approve emit one JSON document on stdout. They are the\n"
       "machine interface used by Forgelet Host; propose and commit retain their\n"
       "existing human-readable output.\n"
       "\n"
       "The proposal is never written to the clarification store: that channel\n"
       "belongs to the pack's agents asking to be unblocked. Ask the operator\n"
       "yourself — in the chat channel, in this pane, or on the phone.\n"
       "\n"
       "commit reports the lane read back from the created board row and records\n"
       "it as created-lane; the proposal's original lane remains in the record.\n"
       "\n"
       "Run both commands with the forge root as the working directory. The\n"
       "forge root defaults to $PWD and can be overridden with --forge-root.\n"
       "\n"
       "Approval forms: answering \"approve\" (or yes/ok/go ahead) is enough,\n"
       "because the answer is tied to this one proposal; replying with the\n"
       "card's name works too, and a negation anywhere in the answer refuses.\n"
       "Any other answer declines. --approval names the dashboard request that\n"
       "recorded their answer, which is what a reply on the phone leaves;\n"
       "--operator-said records words from a channel that leaves no file (this\n"
       "pane). Either one passed to propose records the proposal without\n"
       "asking again.\n"))

(def ^:dynamic *machine-output* false)

(def affirmative-re #"(?i)\b(approve|approved|yes|ok|okay|go ahead|go|start|started)\b")

(def negative-re #"(?i)\b(no|not|dont|don't|won't|wont|wait|hold|reject|never)\b")

(def proposal-id-re #"proposal-[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")

(def project-slug-re #"[a-z0-9][a-z0-9-]{0,63}")

(def absolute-path-re
  #"(?<![A-Za-z0-9+.-])/(?:Users|home|private|var|tmp|opt|Volumes|srv)/[^\s)\]},>]+")

(def token-re
  #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")

(defn usage []
  (println usage-text))

(defn exit! [status message]
  (if *machine-output*
    (throw (ex-info message {:exit status :code "proposal_invalid"}))
    (do
      (binding [*out* *err*]
        (println message))
      (System/exit status))))

(defn fail! [code status message]
  (if *machine-output*
    (throw (ex-info message {:exit status :code code}))
    (exit! status message)))

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT (java.time.Instant/now)))

(defn script-dir []
  (fs/parent *file*))

(defn flag-value [args flag]
  (second (drop-while #(not= flag %) args)))

(defn positional-args [args]
  (loop [remaining args
         positional []]
    (if-let [argument (first remaining)]
      (cond
        (contains? #{"--forge-root" "--project" "--approval"
                     "--operator-said"}
                   argument)
        (recur (nnext remaining) positional)

        (str/starts-with? argument "--")
        (recur (next remaining) positional)

        :else
        (recur (next remaining) (conj positional argument)))
      positional)))

(defn forge-root [args]
  (let [given (or (flag-value args "--forge-root")
                  (not-empty (System/getenv "SWARMFORGE_FORGE_ROOT"))
                  (str (fs/absolutize ".")))]
    (when-not (fs/regular-file? (fs/path given ".swarmforge" "roles.tsv"))
      (exit! 1 (str "Not a forge root (no .swarmforge/roles.tsv): " given)))
    (when-not (fs/directory? (fs/path given "projects"))
      (exit! 1 (str "Not a forge root (no projects/): " given)))
    (fs/absolutize given)))

(defn project-root [given]
  (let [root (fs/absolutize given)]
    (when-not (fs/directory? root)
      (exit! 1 (str "Project root not found: " root)))
    (when-not (or (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
                  (fs/regular-file? (fs/path root ".swarmforge" "single-agent")))
      (exit! 1 (str "Not a project root (no .swarmforge/roles.tsv or "
                    ".swarmforge/single-agent): " root)))
    root))

(defn proposition-id!
  [id]
  (when (or (str/blank? (or id ""))
            (not (re-matches proposal-id-re (str id))))
    (fail! "invalid_proposal_id" 1 "Invalid proposal id."))
  (str id))

(defn projects-root [forge]
  (try
    (fs/canonicalize (fs/path forge "projects"))
    (catch Exception _
      (fail! "invalid_project" 1 "Forge projects directory could not be resolved."))))

(defn project-marker? [root]
  (or (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
      (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))))

(defn resolve-project-root
  "Resolve a proposal's target to one direct child of <forge>/projects. The
  returned path is canonical and never included in machine output."
  [forge value]
  (when (str/blank? (or value ""))
    (fail! "invalid_project" 1 "Proposal target project is missing."))
  (let [projects (projects-root forge)
        candidate (if (fs/absolute? (str value))
                    (fs/path (str value))
                    (fs/path projects (str value)))]
    (try
      (let [root (fs/canonicalize candidate)
            parent (fs/parent root)]
        (when-not (and (= projects parent)
                       (fs/directory? root)
                       (project-marker? root))
          (fail! "invalid_project" 1
                 "Proposal target is not a project in this forge."))
        root)
      (catch Exception e
        (if (and (instance? clojure.lang.ExceptionInfo e)
                 (:code (ex-data e)))
          (throw e)
          (fail! "invalid_project" 1
                 "Proposal target is not a project in this forge."))))))

(defn project-slug! [forge value]
  (let [root (resolve-project-root forge value)
        slug (str (fs/file-name root))]
    (when-not (re-matches project-slug-re slug)
      (fail! "invalid_project" 1 "Proposal target project name is invalid."))
    slug))

(defn project-filter
  [args forge]
  (let [given (or (flag-value args "--project")
                  (first (positional-args args)))]
    (when-not (str/blank? (or given ""))
      (project-slug! forge given))))

(defn redact-output [text]
  (-> (or text "")
      (str/replace #"[\p{Cntrl}&&[^\n\t]]" "")
      (str/replace absolute-path-re "[redacted-path]")
      (str/replace token-re "[redacted-token]")))

(defn parse-request [path]
  (let [[head body] (str/split (slurp (str path)) #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines (or head ""))
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:headers headers
     :body (or body "")
     :response (str/replace (get headers "response" "") #"\\n" "\n")}))

(defn clarification-path [root id state]
  (fs/path root ".swarmforge" "dashboard" "clarifications" state (str id ".request")))

(defn chat-path [root id state]
  (fs/path root ".swarmforge" "dashboard" "requests" state (str id ".request")))

(defn record-in-root
  "The operator's own words, from an answered clarification or from a chat
  request they wrote. Returns nil when the id is unknown in this root."
  [root id]
  (let [answered (clarification-path root id "done")
        pending (clarification-path root id "pending")
        chat-pending (chat-path root id "pending")
        chat-done (chat-path root id "done")]
    (cond
      (fs/regular-file? answered)
      (let [r (parse-request answered)]
        {:kind "clarification" :path (str answered) :text (:response r)})

      (fs/regular-file? chat-pending)
      (let [r (parse-request chat-pending)]
        {:kind "chat request" :path (str chat-pending) :text (:body r)})

      (fs/regular-file? chat-done)
      (let [r (parse-request chat-done)]
        {:kind "chat request" :path (str chat-done) :text (:body r)})

      (fs/regular-file? pending)
      {:kind "clarification" :path (str pending) :pending true :text ""}

      :else nil)))

;; Root order matters: a proposal's question is answered in the project store
;; (the dashboard aggregates open projects, so a question written at the forge
;; root would never surface), and the chat channel writes into the forge root.
(defn approval-record [roots id]
  (some #(record-in-root % id) roots))

(defn approval-from
  "Resolve the operator's words for a proposal: an explicit record id, words
  transcribed from a channel that leaves no file, a source recorded at propose
  time, or the proposal's own dashboard question."
  [roots proposal explicit-approval explicit-said]
  (let [from-record (fn [id]
                      (let [record (approval-record roots id)]
                        (cond
                          (nil? record)
                          (exit! 1 (str "No approval record '" id "' in "
                                        (str/join ", " (map str roots))
                                        "/.swarmforge/dashboard."))
                          (:pending record)
                          (exit! 1 (str "Approval '" id "' is still unanswered ("
                                        (:path record) "). The operator has not replied yet."))
                          :else (assoc record :via (str (:kind record) " " id)))))]
    (cond
      (not (str/blank? (or explicit-approval ""))) (from-record explicit-approval)
      (not (str/blank? (or explicit-said "")))
      {:kind "chat transcription" :text explicit-said :via "operator words in chat"}
      (not (str/blank? (or (:approval-id proposal) ""))) (from-record (:approval-id proposal))
      (not (str/blank? (or (:operator-said proposal) "")))
      {:kind "chat transcription" :text (:operator-said proposal) :via "operator words in chat"}
      :else (from-record (:id proposal)))))

(defn affirmative?
  "Consent from the operator's own words. A negation anywhere refuses, so
  \"no, not yet\" and \"don't approve\" cannot slip through on a stray \"ok\"."
  [text card-name]
  (let [text (or text "")]
    (cond
      (str/blank? text) false
      (re-find negative-re text) false
      (re-find affirmative-re text) true
      :else (and (not (str/blank? card-name))
                 (str/includes? (str/lower-case text) (str/lower-case card-name))))))

(defn proposal-file [forge id]
  (fs/path forge ".swarmforge" "route-proposals" (str id ".edn")))

(defn proposal-status [proposal]
  (let [raw (:status proposal)
        declared (cond
                   (nil? raw) nil
                   (keyword? raw) (name raw)
                   (string? raw) raw
                   (symbol? raw) (name raw)
                   :else (fail! "proposal_invalid" 1
                                "Proposal status is malformed."))
        declared (some-> declared str/lower-case)]
    (cond
      (or (not (str/blank? (or (:consumed-at proposal) "")))
          (= declared "consumed"))
      "consumed"

      (or (not (str/blank? (or (:superseded-at proposal) "")))
          (not (str/blank? (or (:superseded-by proposal) "")))
          (= declared "superseded"))
      "superseded"

      (or (str/blank? declared) (= declared "pending"))
      "pending"

      :else
      (fail! "proposal_invalid" 1 "Proposal status is malformed."))))

(defn read-proposal! [forge id]
  (let [id (proposition-id! id)
        file (proposal-file forge id)]
    (when-not (fs/regular-file? file)
      (fail! "proposal_not_found" 404 "Proposal was not found."))
    (let [proposal
          (try
            (edn/read-string (slurp (str file)))
            (catch Exception _
              (fail! "proposal_invalid" 1 "Proposal record is malformed.")))]
      (when-not (map? proposal)
        (fail! "proposal_invalid" 1 "Proposal record is malformed."))
      (when-not (= id (:id proposal))
        (fail! "proposal_invalid" 1 "Proposal id does not match its record."))
      (let [{:keys [project name lane created-at note]} proposal]
        (when (or (str/blank? (or name ""))
                  (str/blank? (or lane ""))
                  (str/blank? (or created-at ""))
                  (not (or (nil? note) (string? note))))
          (fail! "proposal_invalid" 1 "Proposal record is malformed."))
        (assoc proposal
               :resolved-project
               (if *machine-output*
                 (resolve-project-root forge project)
                 (project-root project)))))))

(defn proposal-rows [forge]
  (let [dir (fs/path forge ".swarmforge" "route-proposals")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".edn")))
           (map (fn [file]
                  (read-proposal! forge
                                  (str/replace (fs/file-name file) #"\.edn$" ""))))
           (sort-by (juxt :created-at :id))
           vec)
      [])))

(defn proposal-json [proposal]
  (let [status (proposal-status proposal)
        proposed-lane (str (:lane proposal))
        created-lane (some-> (:created-lane proposal) str not-empty)
        actual-lane (if (= status "consumed")
                      (or created-lane proposed-lane)
                      proposed-lane)
        project (some-> (:resolved-project proposal) fs/file-name str)
        base {:id (:id proposal)
              :name (redact-output (str (:name proposal)))
              :card_name (redact-output (str (:name proposal)))
              :project project
              :target_project project
              :lane (redact-output actual-lane)
              :proposed_lane (redact-output proposed-lane)
              :original_lane (redact-output proposed-lane)
              :created_at (redact-output (str (:created-at proposal)))
              :status status
              :note (redact-output (or (:note proposal) ""))
              :consumed (= status "consumed")}
        task-fields (cond-> {}
                      (not (str/blank? (or (:task-id proposal) "")))
                      (assoc :task_id (redact-output (str (:task-id proposal))))

                      created-lane
                      (assoc :created_lane (redact-output created-lane))

                      (not (str/blank? (or (:consumed-at proposal) "")))
                      (assoc :consumed_at
                             (redact-output (str (:consumed-at proposal))))

                      (not (str/blank? (or (:superseded-at proposal) "")))
                      (assoc :superseded_at
                             (redact-output (str (:superseded-at proposal)))))]
    (merge base task-fields)))

(defn ensure-pending! [proposal]
  (case (proposal-status proposal)
    "consumed" (fail! "proposal_consumed" 1
                      (str "Approval " (:id proposal)
                           " was already used to create " (:task-id proposal)
                           " — nothing created."))
    "superseded" (fail! "proposal_superseded" 1
                        (str "Proposal " (:id proposal)
                             " was superseded — nothing created."))
    proposal))

(defn require-package [file ns-sym]
  (load-file (str (fs/path (script-dir) file)))
  ns-sym)

(defn master-lane [project]
  (let [result (sh/sh (str (fs/path (script-dir) "pack_board.sh"))
                      "master-lane" "--root" (str project))]
    (when-not (zero? (:exit result))
      (exit! 1 (str "Could not resolve the master lane for " project "\n"
                    (:err result) (:out result))))
    (str/trim (:out result))))

(defn initial-lane [project]
  (if (fs/regular-file? (fs/path project ".swarmforge" "single-agent"))
    (let [file (fs/path project ".swarmforge" "board" "tasks.tsv")
          stages-file (fs/path project "stages.edn")
          project-name (when (fs/regular-file? stages-file)
                         (:task (edn/read-string (slurp (str stages-file)))))
          active? (when (fs/regular-file? file)
                    (some (fn [line]
                            (let [[name lane] (str/split line #"\t" -1)]
                              (and (not= name project-name)
                                   (not (#{"done" "queued"} lane)))))
                          (str/split-lines (slurp (str file)))))]
      (if active? "queued" "spec"))
    (master-lane project)))

(defn board-task-id [project card-name]
  (let [file (fs/path project ".swarmforge" "board" "tasks.tsv")]
    (when (fs/regular-file? file)
      (some (fn [line]
              (let [cols (str/split line #"\t" -1)]
                (when (= (str/lower-case card-name) (str/lower-case (or (first cols) "")))
                  (nth cols 4 nil))))
            (str/split-lines (slurp (str file)))))))

(defn created-task-row
  [project task-id]
  (let [file (fs/path project ".swarmforge" "board" "tasks.tsv")]
    (when (str/blank? task-id)
      (throw (ex-info (str "Card reporting failed: no task id for " project)
                      {:kind :missing-task-id})))
    (when-not (fs/regular-file? file)
      (throw (ex-info (str "Card reporting failed: board row for task id " task-id
                           " is missing")
                      {:kind :missing-board-row :task-id task-id})))
    (let [matches (->> (str/split-lines (slurp (str file)))
                       (remove str/blank?)
                       (filter (fn [line]
                                 (= task-id
                                    (nth (str/split line #"\t" -1) 4 nil))))
                       vec)]
      (when (empty? matches)
        (throw (ex-info (str "Card reporting failed: board row for task id " task-id
                             " is missing")
                        {:kind :missing-board-row :task-id task-id})))
      (when (> (count matches) 1)
        (throw (ex-info (str "Card reporting failed: more than one board row names task id "
                             task-id)
                        {:kind :duplicate-board-row :task-id task-id})))
      (let [[name lane _ _ row-task-id] (str/split (first matches) #"\t" -1)]
        (when (or (str/blank? name) (str/blank? lane)
                  (not= task-id row-task-id))
          (throw (ex-info (str "Card reporting failed: malformed board row for task id "
                               task-id)
                          {:kind :malformed-board-row :task-id task-id})))
        {:name name :lane lane :task-id task-id}))))

(defn propose! [args]
  (let [forge (forge-root args)
        project (project-root (first args))
        name (second args)
        note-file (nth args 2 nil)
        approval-id (flag-value args "--approval")
        operator-said (flag-value args "--operator-said")
        pre-approved (or (not (str/blank? (or approval-id "")))
                         (not (str/blank? (or operator-said ""))))]
    (when (str/blank? name)
      (exit! 1 "Missing card name"))
    (when-not (and note-file (fs/regular-file? note-file))
      (exit! 1 (str "Note file not found: " note-file)))
    (when pre-approved
      (let [words (:text (approval-from [project forge] {} approval-id operator-said))]
        (when-not (affirmative? words name)
          (exit! 1 (str "Not approved: \"" (str/trim (first (str/split-lines (str/trim (or words "")))))
                        "\" — nothing recorded.")))))
    (let [note (str/trimr (slurp (str note-file)))
          lane (initial-lane project)
          ;; The proposal is only ever a record here. The clarification store
          ;; belongs to the pack's agents asking the operator to unblock a card;
          ;; a card proposal is the lieutenant's own question to the operator,
          ;; asked in the chat channel (this pane) or on the phone, never by
          ;; borrowing the agents' channel.
          id (str "proposal-" (str/replace (now) #"[^0-9A-Za-z]" ""))
          record {:id id :project (str project) :name name :note note
                  :lane lane :created-at (now)
                  :approval-id approval-id
                  :operator-said operator-said}]
      (fs/create-dirs (fs/parent (proposal-file forge id)))
      (spit (str (proposal-file forge id)) (pr-str record))
      (println "PROPOSED:" id "card" name "in" (str project) "lane" lane)
      (if pre-approved
        (println "Approval already in hand; run: route_card.sh commit" id)
        (do (println "Ask the operator directly — in this chat, or on the phone where")
            (println "their message arrives as a chat request. Nothing was written to")
            (println "the clarification store, which is the agents' channel.")
            (println "Then run: route_card.sh commit" id "--operator-said \"<their exact words>\"")
            (println "or, when their answer left a record: route_card.sh commit" id "--approval <request-id>")))
      id)))

(defn commit-proposal!
  [{:keys [forge id explicit said]}]
  (let [id (proposition-id! id)
        file (proposal-file forge id)]
    (when-not (fs/regular-file? file)
      (fail! "proposal_not_found" 1
             (str "No proposal record for " id " at " file)))
    (let [proposal (read-proposal! forge id)
          {:keys [name note lane]} proposal
          project-root (:resolved-project proposal)
          _ (when (every? str/blank?
                          [explicit said
                           (:approval-id proposal)
                           (:operator-said proposal)])
              (exit! 1 (str "No approval source. Pass --operator-said \"<their exact words>\""
                            " for approval given in this chat, or --approval <request-id>"
                            " for one that left a record. A card proposal is never"
                            " answered through the clarification store.")))
          record (approval-from [project-root forge] proposal explicit said)]
      (ensure-pending! proposal)
      (when-not (affirmative? (:text record) name)
        (exit! 1 (str "Not approved: " (:via record) " reads \""
                      (str/trim (first (str/split-lines
                                        (str/trim (or (:text record) "")))))
                      "\" — nothing created. Re-propose with route_card.sh propose.")))
      (let [current-lane (initial-lane project-root)
            _ (when (not= current-lane lane)
                (binding [*out* *err*]
                  (println (str "Note: lane moved from '" lane "' to '" current-lane
                                "' since the proposal; the card will land in '"
                                current-lane "'."))))
            _ (require-package "pack_web.bb" 'pack-web)
            create-task! (requiring-resolve 'pack-web/create-task!)]
        (try
          (create-task! project-root name note)
          (catch Exception e
            (exit! 1 (str "Card creation failed: " (.getMessage e)))))
        (let [created-id (board-task-id project-root name)
              created-row (try
                            (created-task-row project-root created-id)
                            (catch Exception e
                              (exit! 1 (.getMessage e))))
              created-lane (:lane created-row)
              updated (assoc proposal
                             :consumed-at (now)
                             :approved-by (:via record)
                             :approved-text (str/trim (or (:text record) ""))
                             :task-id created-id
                             :created-lane created-lane)]
          (spit (str file) (pr-str (dissoc updated :resolved-project)))
          {:proposal (assoc updated :resolved-project project-root)
           :name name
           :project-root project-root
           :created-id created-id
           :created-lane created-lane})))))

(defn commit! [args]
  (let [forge (forge-root args)
        id (first args)
        explicit (flag-value args "--approval")
        said (flag-value args "--operator-said")]
    (when (str/blank? id)
      (exit! 1 "Missing proposal id"))
    (let [{:keys [proposal name project-root created-id created-lane]}
          (commit-proposal! {:forge forge :id id :explicit explicit :said said})]
      (println "APPROVED:" (:approved-by proposal))
      (println "CREATED:" name "in lane" created-lane
               (when created-id (str "task-id " created-id)))
      (if (fs/regular-file? (fs/path project-root ".swarmforge" "single-agent"))
        (if (= "queued" created-lane)
          (println "The card is queued; it will be requested after the active card completes.")
          (println "The new card request is pending on the single-agent dashboard."))
        (println "The New Task note is in the project's outbox for the handoff daemon."))
      created-id)))

(defn machine-error-json [error]
  (let [code (or (:code (ex-data error)) "proposal_unavailable")
        messages {"invalid_proposal_id" "proposal id is invalid"
                  "proposal_not_found" "proposal was not found"
                  "proposal_consumed" "proposal was already consumed"
                  "proposal_superseded" "proposal was superseded"
                  "invalid_project" "proposal target is not a project in this forge"
                  "proposal_invalid" "proposal data was malformed"
                  "proposal_unavailable" "route-card operation failed"}]
    (json/generate-string
     {:error {:code code
              :message (get messages code "route-card operation failed")}})))

(defn machine-list! [args]
  (let [forge (forge-root args)
        project (project-filter args forge)
        rows (->> (proposal-rows forge)
                  (filter #(= "pending" (proposal-status %)))
                  (filter #(or (nil? project)
                               (= project
                                  (str (fs/file-name (:resolved-project %))))))
                  (mapv proposal-json))]
    {:proposals rows}))

(defn machine-detail! [args]
  (let [forge (forge-root args)
        id (first args)]
    (proposal-json (read-proposal! forge id))))

(defn machine-approve! [args]
  (let [forge (forge-root args)
        id (first args)
        explicit (flag-value args "--approval")
        said (flag-value args "--operator-said")
        {:keys [proposal]} (commit-proposal!
                            {:forge forge :id id :explicit explicit :said said})]
    (proposal-json proposal)))

(defn machine-main [operation args]
  (binding [*machine-output* true]
    (try
      (let [result (case operation
                     "list" (machine-list! args)
                     "detail"
                     (do
                       (when (str/blank? (first args))
                         (exit! 1 "Missing proposal id"))
                       (machine-detail! args))
                     "inspect"
                     (do
                       (when (str/blank? (first args))
                         (exit! 1 "Missing proposal id"))
                       (machine-detail! args))
                     "approve"
                     (do
                       (when (str/blank? (first args))
                         (exit! 1 "Missing proposal id"))
                       (machine-approve! args)))]
        (println (json/generate-string result)))
      (catch Throwable error
        (println (machine-error-json error))
        (System/exit 1)))))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (usage)
    (System/exit 0))
  (case (first args)
    "propose" (propose! (rest args))
    "commit" (commit! (rest args))
    "list" (machine-main "list" (rest args))
    "detail" (machine-main "detail" (rest args))
    "inspect" (machine-main "inspect" (rest args))
    "approve" (machine-main "approve" (rest args))
    (do (usage)
        (System/exit 1)))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
