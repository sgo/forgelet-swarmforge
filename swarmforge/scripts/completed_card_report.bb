#!/usr/bin/env bb

(ns completed-card-report
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption StandardOpenOption]))

(def usage-text
  (str "Preserve verified completed-card reports and clean their board rows.\n"
       "\n"
       "Usage:\n"
       "  completed_card_report.sh record --root <forge-root> <report.edn>\n"
       "  completed_card_report.sh remove --root <forge-root> <report-id>\n"
       "\n"
       "A report input contains :report_id, :family, optional :reported_at and\n"
       ":lieutenant, and a non-empty :cards vector. Each card names :project,\n"
       ":task_id and :verification (\"verified\" or \"retained\"); a retained card\n"
       "may carry :retained_reason. The board supplies the card name, completion\n"
       "timestamp and active duration, so those values are not trusted from input.\n"
       "\n"
       "record accepts a report id once and preserves the original record on any\n"
       "retry. remove deletes only verified rows whose done snapshot still matches\n"
       "the recorded evidence. Before removing a row it stamps the consumed route\n"
       "proposal that created it with verified completion and task identity, which\n"
       "is the only event that releases dependent proposals. Retained rows remain\n"
       "on their boards.\n"))

(def report-id-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,150}")
(def family-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def lieutenant-re #"[A-Za-z][A-Za-z0-9._-]{0,63}")
(def project-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def task-id-re #"[A-Za-z0-9][A-Za-z0-9._~:-]{0,255}")
(def absolute-path-re
  #"(?<![A-Za-z0-9+.-])/(?:Users|home|private|var|tmp|opt|Volumes|srv)/[^\s)\]},>]+")
(def token-re
  #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")

(def flags
  {"--root" :root})

(defn fail!
  ([message] (fail! 1 message))
  ([status message]
   (throw (ex-info message {:exit status}))))

(defn now []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn parse-args [args]
  (loop [args args
         opts {}
         positional []]
    (if-let [head (first args)]
      (if-let [key (get flags head)]
        (if-let [value (second args)]
          (recur (nnext args) (assoc opts key value) positional)
          (fail! (str "Missing value for " head)))
        (recur (next args) opts (conj positional head)))
      (assoc opts :positional positional))))

(defn resolve-root [opts]
  (let [root (or (:root opts)
                 (not-empty (System/getenv "SWARMFORGE_FORGE_ROOT"))
                 (str (fs/absolutize ".")))]
    (when-not (fs/directory? (fs/path root "projects"))
      (fail! "The report root has no projects/ directory."))
    (when-not (fs/regular-file? (fs/path root ".swarmforge" "roles.tsv"))
      (fail! "The report root is not a forge root (no .swarmforge/roles.tsv)."))
    (fs/canonicalize (fs/path root))))

(defn parse-instant [value]
  (when (and (string? value) (not (str/blank? value)))
    (try
      (java.time.Instant/parse value)
      (catch Exception _ nil))))

(defn safe-text [value]
  (-> (str (or value ""))
      (str/replace #"[\p{Cntrl}&&[^\n\t]]" "")
      (str/replace #"[\r\n\t]+" " ")
      (str/replace absolute-path-re "[redacted-path]")
      (str/replace token-re "[redacted-token]")
      str/trim))

(defn require-identifier! [value label pattern]
  (let [value (str (or value ""))]
    (when-not (re-matches pattern value)
      (fail! (str label " is missing or invalid.")))
    value))

(defn report-id! [value]
  (require-identifier! value "Report id" report-id-re))

(defn phase [value]
  (let [value (cond
                (keyword? value) (name value)
                (symbol? value) (name value)
                (string? value) value
                :else "")]
    (str/lower-case (str/trim value))))

(defn verification! [value]
  (let [value (phase value)]
    (when-not (#{"verified" "retained"} value)
      (fail! "Each report card must be verified or retained."))
    value))

(defn verification-result [cards]
  (let [verified (count (filter #(= "verified" (:verification %)) cards))
        retained (- (count cards) verified)]
    (cond
      (zero? retained) "verified"
      (zero? verified) "retained"
      :else "partial")))

(defn master-role [forge]
  (let [file (fs/path forge ".swarmforge" "roles.tsv")
        rows (->> (str/split-lines (slurp (str file)))
                  (remove str/blank?)
                  (map #(str/split % #"\t" -1)))]
    (or (some (fn [row]
                (when (and (not (str/blank? (first row)))
                           (= "master" (second row)))
                  (first row)))
              rows)
        (some-> rows first first)
        "lieutenant")))

(declare completed-board-row)

(defn normalize-report-input [forge input]
  (when-not (map? input)
    (fail! "The report input must be an EDN map."))
  (let [report-id (report-id! (:report_id input))
        family (require-identifier! (:family input) "Family" family-re)
        lieutenant (require-identifier!
                    (or (:lieutenant input) (master-role forge))
                    "Reporting Lieutenant"
                    lieutenant-re)
        reported-at (or (:reported_at input) (now))
        _ (when-not (parse-instant reported-at)
            (fail! "The report completion time must be an ISO instant."))
        cards (:cards input)
        _ (when-not (and (vector? cards) (seq cards))
            (fail! "The report must name at least one completed card."))
        identities (atom #{})
        prepared
        (mapv
         (fn [item]
           (when-not (map? item)
             (fail! "Every reported card must be an EDN map."))
           (let [project (require-identifier! (:project item)
                                              "Reported project"
                                              project-re)
                 task-id (require-identifier! (:task_id item)
                                              "Reported task id"
                                              task-id-re)
                 identity [project task-id]]
             (when-not (swap! identities conj identity)
               (fail! (str "Report repeats project " project
                           " task id " task-id ".")))
             (let [project-root (fs/path forge "projects" project)]
               (when-not (fs/directory? project-root)
                 (fail! (str "Reported project " project " does not exist.")))
               (let [row (completed-board-row project-root task-id)
                     card-name (safe-text (:name row))
                     verification (verification! (:verification item))]
                 (when (str/blank? card-name)
                   (fail! (str "Reported task id " task-id
                               " has no card name on the board.")))
                 (when-not (parse-instant (:updated row))
                   (fail! (str "Reported task id " task-id
                               " has no valid completion timestamp.")))
                 {:project project
                  :task_id task-id
                  :card_name card-name
                  :started_at (:started-at row)
                  :completed_at (:updated row)
                  :active_seconds (:active-seconds row)
                  :verification verification
                  :retained_reason
                  (when (= "retained" verification)
                    (let [reason (safe-text
                                  (or (:retained_reason item)
                                      "Retained by the reporting Lieutenant."))]
                      (if (str/blank? reason)
                        "Retained by the reporting Lieutenant."
                        reason)))}))))
         cards)
        retained (->> prepared
                      (filter #(= "retained" (:verification %)))
                      (mapv #(select-keys %
                                          [:project :task_id :card_name
                                           :completed_at :retained_reason])))]
    {:version 1
     :report_id report-id
     :reported_at reported-at
     :family family
     :lieutenant lieutenant
     :verification_result (verification-result prepared)
     :cards prepared
     :retained_cards retained}))

(defn board-lock-dir [root]
  (fs/path root ".swarmforge" "board"))

(defn board-file [project-root]
  (fs/path project-root ".swarmforge" "board" "tasks.tsv"))

(defn board-rows [project-root]
  (let [file (board-file project-root)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (mapv (fn [line]
                   (let [[name lane created updated task-id audit-count]
                         (str/split line #"\t" -1)]
                     {:name name
                      :lane lane
                      :created created
                      :updated updated
                      :task_id (or (not-empty task-id) name)
                      :audit_count audit-count
                      :line line}))))
      [])))

(defn activation-file [project-root task-id]
  (fs/path project-root ".swarmforge" "card-activations"
           (str (str/replace (str task-id) #"[^0-9A-Za-z._-]" "_") ".edn")))

(defn activation-start [project-root task-id fallback]
  (let [file (activation-file project-root task-id)]
    (if (fs/regular-file? file)
      (try
        (let [record (edn/read-string (slurp (str file)))]
          (or (:activated_at record)
              (:activated-at record)
              (get record "activated_at")
              (get record "activated-at")))
        (catch Exception _ nil))
      fallback)))

(defn duration-seconds [start end]
  (let [start (parse-instant start)
        end (parse-instant end)]
    (when (and start end (not (.isBefore end start)))
      (.getSeconds (java.time.Duration/between start end)))))

(defn completed-board-row [project-root task-id]
  (let [matches (->> (board-rows project-root)
                     (filter #(= task-id (:task_id %)))
                     vec)]
    (when (empty? matches)
      (fail! (str "Reported task id " task-id " is not on project "
                  (fs/file-name project-root) "'s board.")))
    (when (> (count matches) 1)
      (fail! (str "More than one board row names task id " task-id ".")))
    (let [row (first matches)]
      (when-not (= "done" (:lane row))
        (fail! (str "Reported task id " task-id " is not in the done lane.")))
      (let [start (activation-start project-root task-id (:created row))]
        (assoc row
               :started-at start
               :active-seconds (duration-seconds start (:updated row)))))))

(defn report-dir [forge]
  (fs/path forge "completed-card-reports"))

(defn report-file [forge report-id]
  (fs/path (report-dir forge) (str report-id ".edn")))

(defn report-lock [forge]
  (fs/path forge ".swarmforge" "completed-card-reports.lock"))

(defn with-lock [forge f]
  (let [path (report-lock forge)
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs (fs/parent path))
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn atomic-create! [path text]
  (fs/create-dirs (fs/parent path))
  (Files/writeString path text StandardCharsets/UTF_8
                     (into-array OpenOption
                                 [StandardOpenOption/CREATE_NEW
                                  StandardOpenOption/WRITE])))

(defn atomic-write! [path text]
  (fs/create-dirs (fs/parent path))
  (let [tmp (fs/create-temp-file {:dir (fs/parent path)
                                  :prefix ".completed-card-report."})]
    (spit (str tmp) text)
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn report-text [record]
  (str (pr-str record) "\n"))

(defn report-marker [report-id]
  (str "<!-- completed-card-report:" report-id " -->"))

(defn markdown-cell [value]
  (-> (safe-text value)
      (str/replace "|" "\\|")
      (str/replace "\n" " ")
      str/trim))

(defn report-markdown [record]
  (let [cards (:cards record)
        verified (count (filter #(= "verified" (:verification %)) cards))
        retained (count (filter #(= "retained" (:verification %)) cards))
        rows (for [{:keys [project task_id card_name completed_at
                           active_seconds verification retained_reason]} cards]
               (str "| " (markdown-cell project)
                    " | " (markdown-cell task_id)
                    " | " (markdown-cell card_name)
                    " | " (markdown-cell completed_at)
                    " | " (markdown-cell (or active_seconds "unavailable"))
                    " | " (markdown-cell verification)
                    (when retained_reason
                      (str ": " (markdown-cell retained_reason)))
                    " |"))]
    (str (report-marker (:report_id record)) "\n"
         "### Completed-card report " (:report_id record) "\n\n"
         "- Report completed: " (:reported_at record) "\n"
         "- Family: " (markdown-cell (:family record)) "\n"
         "- Reporting Lieutenant: " (markdown-cell (:lieutenant record)) "\n"
         "- Verification: " (:verification_result record)
         " (" verified " verified, " retained " retained)\n"
         "- Machine record: `completed-card-reports/"
         (:report_id record) ".edn`\n\n"
         "| Project | Task id | Card | Completed | Active seconds | Verification |\n"
         "|---|---|---|---|---:|---|\n"
         (str/join "\n" rows) "\n\n")))

(defn append-backlog! [forge record]
  (let [file (fs/path forge "backlog.md")
        existing (if (fs/regular-file? file)
                   (slurp (str file))
                   "# Forge Backlog\n\n")
        marker (report-marker (:report_id record))]
    (if (str/includes? existing marker)
      false
      (do
        (atomic-write!
         file
         (str existing
              (when-not (or (str/blank? existing)
                            (str/ends-with? existing "\n"))
                "\n")
              "\n"
              (report-markdown record)))
        true))))

(defn unsafe-text? [value]
  (and (string? value)
       (or (re-find absolute-path-re value)
           (re-find token-re value))))

(defn valid-card-record? [card]
  (and (map? card)
       (string? (:project card))
       (re-matches project-re (:project card))
       (string? (:task_id card))
       (re-matches task-id-re (:task_id card))
       (string? (:card_name card))
       (not (str/blank? (:card_name card)))
       (not (unsafe-text? (:card_name card)))
       (string? (:completed_at card))
       (some? (parse-instant (:completed_at card)))
       (contains? #{"verified" "retained"} (:verification card))
       (or (nil? (:active_seconds card))
           (and (integer? (:active_seconds card))
                (not (neg? (:active_seconds card)))))
       (or (nil? (:started_at card))
           (some? (parse-instant (:started_at card))))
       (or (nil? (:retained_reason card))
           (and (string? (:retained_reason card))
                (not (unsafe-text? (:retained_reason card)))))))

(defn valid-retained-record? [retained cards]
  (and (map? retained)
       (some #(and (= (:project retained) (:project %))
                   (= (:task_id retained) (:task_id %))
                   (= "retained" (:verification %)))
             cards)
       (string? (:card_name retained))
       (not (str/blank? (:card_name retained)))
       (string? (:completed_at retained))
       (some? (parse-instant (:completed_at retained)))
       (or (nil? (:retained_reason retained))
           (and (string? (:retained_reason retained))
                (not (unsafe-text? (:retained_reason retained)))))))

(defn read-record [path]
  (try
    (let [record (edn/read-string (slurp (str path)))
          cards (:cards record)]
      (when (and (map? record)
                 (= 1 (:version record))
                 (string? (:report_id record))
                 (re-matches report-id-re (:report_id record))
                 (string? (:reported_at record))
                 (some? (parse-instant (:reported_at record)))
                 (string? (:family record))
                 (re-matches family-re (:family record))
                 (string? (:lieutenant record))
                 (re-matches lieutenant-re (:lieutenant record))
                 (contains? #{"verified" "partial" "retained"}
                            (:verification_result record))
                 (vector? cards)
                 (seq cards)
                 (every? valid-card-record? cards)
                 (vector? (:retained_cards record))
                 (every? #(valid-retained-record? % cards)
                         (:retained_cards record)))
        record))
    (catch Exception _ nil)))

(defn history-files [forge]
  (let [dir (report-dir forge)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".edn")))
           (sort-by #(fs/file-name %))
           vec)
      [])))

(defn history-records [forge]
  (->> (history-files forge)
       (keep read-record)
       (reduce (fn [records record]
                 (if (some #(= (:report_id %) (:report_id record)) records)
                   records
                   (conj records record)))
               [])
       (sort-by (juxt :reported_at :report_id))
       vec))

(defn reported-identities [records]
  (->> records
       (mapcat :cards)
       (keep (fn [{:keys [project task_id]}]
               (when (and project task_id)
                 [project task_id])))
       set))

(defn verified-history-cards [records project-name]
  (->> records
       (mapcat :cards)
       (filter #(and (= project-name (:project %))
                     (= "verified" (:verification %))))
       (reduce (fn [cards card]
                 (if (some #(= (:task_id %) (:task_id card)) cards)
                   cards
                   (conj cards card)))
               [])))

(defn verified-durations [records project-name]
  (->> (verified-history-cards records project-name)
       (keep :active_seconds)
       (filter #(and (integer? %) (not (neg? %))))
       vec))

(defn record! [opts]
  (let [forge (resolve-root opts)
        input-file (second (:positional opts))
        _ (when-not (and input-file (fs/regular-file? input-file))
            (fail! "Report input file not found."))]
    (with-lock
      forge
      (fn []
        (let [input (try
                      (edn/read-string (slurp input-file))
                      (catch Exception _
                        (fail! "Report input is not valid EDN.")))
              id (when (map? input)
                   (report-id! (:report_id input)))
              file (report-file forge id)
              existing (when (fs/regular-file? file)
                         (read-record file))]
          (if (fs/exists? file)
            (do
              (when existing
                (append-backlog! forge existing))
              (fail! (str "Duplicate report id " id
                          "; the original evidence was preserved.")))
            (let [candidate (normalize-report-input forge input)]
              (atomic-create! file (report-text candidate))
              (append-backlog! forge candidate)
              (println (str "RECORDED:" id ":" (:verification_result candidate)
                            ":" (count (:cards candidate)))))))))))

(defn with-board-lock [project-root f]
  (let [dir (board-lock-dir project-root)
        path (fs/path dir "tasks.lock")
        options (into-array OpenOption [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE])]
    (fs/create-dirs dir)
    (with-open [channel (FileChannel/open path options)]
      (.lock channel)
      (f))))

(defn route-proposals-dir [forge]
  (fs/path forge ".swarmforge" "route-proposals"))

(defn linked-route-proposals [forge verified]
  (let [dir (route-proposals-dir forge)
        identities (set (map (juxt :project :task_id) verified))]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".edn")))
           (keep (fn [file]
                   (let [proposal (try
                                    (edn/read-string (slurp (str file)))
                                    (catch Exception _ nil))]
                     (when (and (map? proposal)
                                (string? (:project proposal))
                                (string? (:task-id proposal)))
                       (let [project (try
                                       (fs/canonicalize
                                        (fs/path (str (:project proposal))))
                                       (catch Exception _ nil))
                             project-name (some-> project fs/file-name str)]
                         (when (and project-name
                                    (contains? identities
                                               [project-name (:task-id proposal)]))
                           {:file file :proposal proposal}))))))
           vec)
      [])))

(defn stamp-linked-proposals! [forge verified report-id]
  (let [completed-at (now)]
    (doseq [{:keys [file proposal]} (linked-route-proposals forge verified)]
      (when-not (and (string? (:consumed-at proposal))
                     (not (str/blank? (:consumed-at proposal))))
        (fail! (str "Linked proposal " (:id proposal)
                    " is not consumed; completed-card cleanup was not performed.")))
      (let [existing-completed-at (parse-instant (:completed-at proposal))
            existing-marker? (or (:completed-at proposal)
                                 (:completed-task-id proposal)
                                 (:completion-report-id proposal)
                                 (:cleanup-complete-at proposal))
            valid-marker? (and existing-completed-at
                               (= (:completed-task-id proposal)
                                  (:task-id proposal))
                               (re-matches report-id-re
                                           (str (:completion-report-id proposal))))]
        (when (and existing-marker? (not valid-marker?))
          (fail! (str "Linked proposal " (:id proposal)
                      " has a malformed completion marker.")))
        (when-not valid-marker?
        (atomic-write!
         file
         (str (pr-str
               (assoc proposal
                      :completed-at completed-at
                      :completed-task-id (:task-id proposal)
                      :completion-report-id report-id))
              "\n")))))))

(defn finalize-linked-proposals! [forge verified report-id]
  (doseq [{:keys [file proposal]} (linked-route-proposals forge verified)]
    (let [completed? (and (parse-instant (:completed-at proposal))
                          (= (:completed-task-id proposal)
                             (:task-id proposal))
                          (re-matches report-id-re
                                      (str (:completion-report-id proposal))))]
      (when-not completed?
        (fail! (str "Linked proposal " (:id proposal)
                    " lacks its verified completion stamp.")))
      (when (and (:cleanup-complete-at proposal)
                 (nil? (parse-instant (:cleanup-complete-at proposal))))
        (fail! (str "Linked proposal " (:id proposal)
                    " has a malformed cleanup marker.")))
      (when-not (parse-instant (:cleanup-complete-at proposal))
        (atomic-write!
         file
         (str (pr-str
               (assoc proposal
                      :cleanup-complete-at (now)
                      :cleanup-report-id report-id))
              "\n"))))))

(defn board-text [rows]
  (if (seq rows)
    (str (str/join "\n" (map :line rows)) "\n")
    ""))

(defn project-root! [forge project]
  (let [root (fs/path forge "projects" project)]
    (when-not (fs/directory? root)
      (fail! (str "Reported project " project " does not exist.")))
    root))

(defn remove! [opts]
  (let [forge (resolve-root opts)
        report-id (report-id! (second (:positional opts)))
        file (report-file forge report-id)
        record (when (fs/regular-file? file)
                 (read-record file))
        _ (when-not record
            (fail! (str "Completed-card report " report-id " is missing or malformed.")))
        verified (->> (:cards record)
                      (filter #(= "verified" (:verification %)))
                      vec)
        by-project (group-by :project verified)]
    (with-lock
      forge
      (fn []
        ;; Refuse before changing any project if a verified row was reopened or
        ;; replaced after the reported snapshot.
        (doseq [[project cards] by-project]
          (let [project-root (project-root! forge project)
                current (board-rows project-root)]
            (doseq [{:keys [task_id completed_at]} cards]
              (when-let [row (some #(when (= task_id (:task_id %)) %) current)]
                (when (not= "done" (:lane row))
                  (fail! (str "Reported task id " task_id
                              " is no longer in the done lane; retained it.")))
                (when (not= completed_at (:updated row))
                  (fail! (str "Reported task id " task_id
                              " changed after the report; retained it.")))))))
        ;; Completion is recorded before cleanup, but route-card release also
        ;; requires the linked board row to be gone. A crash between these two
        ;; steps therefore stays blocked and is repaired by rerunning remove.
        (stamp-linked-proposals! forge verified report-id)
        (doseq [[project cards] by-project]
          (let [project-root (project-root! forge project)
                remove-ids (set (map :task_id cards))]
            (with-board-lock
              project-root
              (fn []
                (let [before (board-rows project-root)
                      removed (filterv #(contains? remove-ids (:task_id %)) before)
                      after (filterv #(not (contains? remove-ids (:task_id %))) before)]
                  (atomic-write! (board-file project-root) (board-text after))
                  (doseq [{:keys [name]} removed]
                    (fs/delete-if-exists
                     (fs/path project-root ".swarmforge" "board"
                              (str name ".txt")))))))))
        (finalize-linked-proposals! forge verified report-id)
        (println (str "REMOVED:" (count verified)
                      ":" (count (:retained_cards record))
                      ":" (count (linked-route-proposals forge verified))))))))

(defn -main [& args]
  (let [opts (parse-args args)
        command (first (:positional opts))]
    (case command
      "record" (record! opts)
      "remove" (remove! opts)
      (do
        (binding [*out* *err*]
          (print usage-text)
          (flush))
        (System/exit 1)))))

(defn invoked-directly? []
  (let [invoked (System/getProperty "babashka.file")]
    (boolean
     (and invoked
          *file*
          (try
            (= (fs/canonicalize (fs/path (str *file*)))
               (fs/canonicalize (fs/path invoked)))
            (catch Exception _ false))))))

(when (invoked-directly?)
  (try
    (apply -main *command-line-args*)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit (or (:exit (ex-data e)) 1)))))
