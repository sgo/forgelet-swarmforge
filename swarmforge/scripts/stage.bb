#!/usr/bin/env bb

(ns stage
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.nio.file Files)
           (java.security MessageDigest)
           (java.time Instant)))

(def usage-text
  (str "Usage:\n"
       "  stage list [--root DIR]\n"
       "  stage check <name> [--root DIR]\n"
       "  stage propose spec <file>... [--replace] [--root DIR]\n"
       "  stage approve <proposal> --words \"<operator's words>\" [--root DIR]\n"
       "  stage reject <proposal> --words \"<operator's words>\" [--root DIR]\n"
       "  stage complete [--root DIR]\n"
       "  stage status [--json] [--root DIR]\n"
       "\n"
       "A new task report has ## Verification and ## Operator acceptance\n"
       "sections. Operator acceptance is None. or bounded entries with owner,\n"
       "environment, checks and pending/confirmed status. Pending entries do\n"
       "not replace any executable, release, destructive or policy gate.\n"
       "\n"
       "For task notes, spec hashing omits only the ## Verification and\n"
       "## Operator acceptance sections; stage completion still parses both.\n"
       "Use focused :stamp inputs for the files a gate actually consumes instead\n"
       "of copying :whole-tree. Missing inputs remain fail-visible.\n"))

(defn usage []
  (println usage-text))

(defn exit! [status & messages]
  (doseq [message messages]
    (when message
      (binding [*out* *err*]
        (println message))))
  (System/exit status))

(defn now []
  (str (Instant/now)))

(defn sha256 [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn file-hash [path]
  (sha256 (Files/readAllBytes (fs/path path))))

(defn read-edn [path]
  (edn/read-string (slurp (str path))))

(defn write-edn [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (with-out-str (pp/pprint value))))

(defn git [root & args]
  (apply sh/sh "git" "-C" (str root) args))

(defn git-repo? [root]
  (zero? (:exit (git root "rev-parse" "--is-inside-work-tree"))))

(defn git-env [index]
  (assoc (into {} (System/getenv)) "GIT_INDEX_FILE" (str index)))

(defn excluded? [root path]
  (let [relative (str (fs/relativize root path))]
    (or (= relative ".git")
        (str/starts-with? relative ".git/")
        (str/starts-with? relative "target/")
        (str/starts-with? relative ".swarmforge/")
        (str/starts-with? relative "tmp/"))))

(defn whole-tree-hash [root]
  (let [files (->> (fs/glob root "**")
                   (filter fs/regular-file?)
                   (remove #(excluded? root %))
                   sort)]
    (sha256 (.getBytes
             (str/join "\n"
                       (map #(str (fs/relativize root %) "\n" (file-hash %))
                            files))
             "UTF-8"))))

(defn worktree-tree [root]
  (if (git-repo? root)
    (let [dir (fs/create-temp-dir {:prefix "stage-index."})
          index (fs/path dir "index")]
      (try
        (let [add (sh/sh "git" "-C" (str root) "add" "-A"
                         :env (git-env index))]
          (when-not (zero? (:exit add))
            (exit! 1 "git add -A failed:" (:err add))))
        (let [write (sh/sh "git" "-C" (str root) "write-tree"
                           :env (git-env index))]
          (when-not (zero? (:exit write))
            (exit! 1 "git write-tree failed:" (:err write)))
          (str/trim (:out write)))
        (finally
          (fs/delete-tree dir))))
    (whole-tree-hash root)))

(defn head-tree [root]
  (let [result (git root "rev-parse" "HEAD^{tree}")]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

(defn stages-file [root]
  (or (some #(when (fs/regular-file? %) %)
            [(fs/path root "stages.edn")
             (fs/path root ".swarmforge" "stages.edn")
             (fs/path root "swarmforge" "stages.edn")])
      (exit! 1 (str "No stages.edn in " root))))

(defn load-config [root]
  (let [file (stages-file root)
        config (read-edn file)]
    (when-not (seq (:stages config))
      (exit! 1 (str "No :stages in " file)))
    (assoc config :file (str file))))

(defn state-file [root task]
  (fs/path root ".swarmforge" "stages" (str task ".edn")))

(defn compact-state [state]
  (if (map? (:proposals state))
    (update state :proposals
            (fn [proposals]
              (into {}
                    (map (fn [[id proposal]]
                           [id (dissoc proposal :body)]))
                    proposals)))
    state))

(defn load-state [root task]
  (let [file (state-file root task)]
    (if (fs/regular-file? file)
      (let [stored (read-edn file)
            compacted (compact-state stored)]
        (when-not (= stored compacted)
          (write-edn file compacted))
        compacted)
      {:task task :proposals {} :stages {}})))

(defn save-state [root task state]
  (write-edn (state-file root task) state))

(defn board-dir [root]
  (fs/path root ".swarmforge" "board"))

(defn board-tasks-file [root]
  (fs/path (board-dir root) "tasks.tsv"))

(defn board-lanes-file [root]
  (fs/path (board-dir root) "lanes.tsv"))

(defn board-columns [line]
  (str/split line #"\t" -1))

(defn board-rows [root]
  (let [file (board-tasks-file root)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (remove str/blank?)
           (map (fn [line]
                  (let [[name lane created updated task-id audit]
                        (board-columns line)]
                    {:name name :lane lane :created created :updated updated
                     :id (or (not-empty task-id) name)
                     :audit (or audit "0")})))
           vec)
      [])))

(defn update-board! [root config card lane]
  (let [name (:name card)
        card-id (or (:id card) name)
        file (board-tasks-file root)
        now (now)
        rows (if (fs/regular-file? file)
               (->> (str/split-lines (slurp (str file)))
                    (remove str/blank?))
               [])
        existing (some #(when (= name (first (board-columns %))) %) rows)
        created (if existing (get (board-columns existing) 2 now) now)
        existing-id (when existing (not-empty (nth (board-columns existing) 4 nil)))
        audit (or (when existing (nth (board-columns existing) 5 nil)) (:audit card) "0")
        row (str/join "\t" [name lane created now (or existing-id card-id) audit])
        project-row (str (:task config))
        stale-project-row? (fn [line]
                             (let [[row-name _ _ _ row-id _] (board-columns line)]
                               (and (not= row-name name)
                                    (= row-name project-row)
                                    (= (or row-id row-name) project-row))))
        kept (->> rows
                  (remove #(= name (first (board-columns %))))
                  (remove stale-project-row?))]
    (fs/create-dirs (board-dir root))
    (spit (str file) (str (str/join "\n" (concat kept [row])) "\n"))
    (spit (str (board-lanes-file root))
          (str (str/join "\n" (concat (map :name (:stages config)) ["done"])) "\n"))))

(defn message-headers [text]
  (let [[header] (str/split (or text "") #"\n\n" 2)]
    (into {}
          (for [line (str/split-lines (or header ""))
                :let [[key value] (str/split line #": " 2)]
                :when (and key value)]
            [key value]))))

(defn stage-approval? [path]
  (= "stage-spec"
     (get (message-headers (slurp (str path))) "kind")))

(defn pending-approval-dir [root]
  (fs/path root ".swarmforge" "handoffs" "pending_approval"))

(defn stage-approval-file [root id]
  (fs/path (pending-approval-dir root) (str id ".handoff")))

(defn resolved-approval-dir [root]
  (fs/path root ".swarmforge" "approvals" "resolved"))

(defn record-resolution! [root config card id resolution]
  (let [file (fs/path (resolved-approval-dir root) (str id ".edn"))]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (pr-str {:id id
                   :task (or (:display card) (:name card) (:task config))
                   :card (:name card)
                   :card_id (:id card)
                   :resolution resolution
                   :at (now)}))))

(defn remove-stage-approvals! [root]
  (let [dir (pending-approval-dir root)]
    (when (fs/directory? dir)
      (doseq [file (fs/list-dir dir)
              :when (and (fs/regular-file? file)
                         (str/ends-with? (fs/file-name file) ".handoff")
                         (stage-approval? file))]
        (fs/delete-if-exists file)))))

(defn card-name [root config files]
  (let [heading (fn [file]
                  (some #(when (str/starts-with? % "# ") (str/trim (subs % 2)))
                        (str/split-lines (slurp (str (fs/path root file))))))
        task-files (->> files
                        (filter #(str/starts-with? % "tasks/"))
                        (sort-by #(fs/last-modified-time (fs/path root %))
                                 #(compare %2 %1)))]
    (or (some heading task-files)
      (some heading files)
      (:task config))))

(defn fallback-card [root config files]
  (let [display (card-name root config files)]
    {:name (:task config)
     :id (:task config)
     :display display
     :lane "agent"}))

(defn select-card [root config state files]
  (let [active (->> (board-rows root)
                    (remove #(contains? #{"done" "queued"} (:lane %)))
                    vec)
        project-name (:task config)
        routed (remove #(= project-name (:name %)) active)
        display (card-name root config files)
        by-display (some #(when (= display (:name %)) routed) routed)
        state-card (:card state)
        state-card (some #(when (= (:name state-card) (:name %)) %) active)
        card (cond
               (and state-card (not= project-name (:name state-card)))
               state-card

               by-display
               by-display

               (= 1 (count routed))
               (first routed)

               state-card
               state-card

               (empty? active)
               (fallback-card root config files)

               (= 1 (count active))
               (first active)

               :else
               (exit! 1 (str "More than one active card: "
                             (str/join ", " (map :name active))
                             ". Name the card in the task note or close the others first.")))
          card (if (map? card) card (first card))]
    (when-not (map? card)
      (exit! 1 (str "Could not select the routed board card from: "
                    (str/join ", " (map :name active)))))
    (assoc card :display (or (:display card) (:name card)))))

(defn hydrate-state-card [root config state]
  (if (:card state)
    state
    (let [latest (->> (vals (:proposals state))
                      (filter #(contains? #{:pending :approved} (:status %)))
                      (sort-by :at)
                      last)
          files (or (:files latest) [])]
      (assoc state :card (select-card root config state files)))))

(defn card-completed? [state card]
  (and card
       (= (:id card) (:id (:card (:complete state))))))

(defn first-approval-recorded? [state card]
  (and card
       (some (fn [proposal]
               (and (= :approved (:status proposal))
                    (= (:id card) (:id (:card proposal)))))
             (vals (:proposals state)))))

(defn single-agent? [root]
  (fs/regular-file? (fs/path root ".swarmforge" "single-agent")))

(defn owning-forge [root]
  (let [projects-dir (fs/parent (fs/canonicalize (fs/absolutize root)))]
    (when (and projects-dir
               (= "projects" (str (fs/file-name projects-dir))))
      (fs/parent projects-dir))))

(defn configured-spec-approval [root]
  (when-let [forge (owning-forge root)]
    (let [file (fs/path forge "swarmforge" "local-spec-approval.conf")]
      (when (fs/regular-file? file)
        (let [setting (->> (str/split-lines (slurp (str file)))
                           (map str/trim)
                           (remove #(or (str/blank? %)
                                        (str/starts-with? % "#")))
                           (keep #(second (re-matches #"spec\s*=\s*(\S+)" %)))
                           last)]
          (case setting
            nil nil
            "automatic" :automatic
            "operator" :operator
            (exit! 1 (str "Invalid spec approval mode in " file
                          ": expected operator or automatic"))))))))

(defn spec-approval-mode [root]
  (if (single-agent? root)
    (or (configured-spec-approval root) :automatic)
    :operator))

(defn auto-approve-correction? [root state card]
  (and (single-agent? root)
       (= :operator (spec-approval-mode root))
       (first-approval-recorded? state card)
       (not (card-completed? state card))))

(defn write-stage-approval! [root config proposal]
  (let [file (stage-approval-file root (:id proposal))
        card (:card proposal)]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "from: agent\n"
               "to: operator\n"
               "kind: stage-spec\n"
               "task: " (:display card) "\n"
               "task_id: " (:id card) "\n"
               "artifacts: " (str/join ", " (:files proposal)) "\n"
               "created_at: " (:at proposal) "\n"
               "\n"
               "Spec proposal for " (:task config) "\n\n"
               "Approve this exact Gherkin and task note, or send it back with the changes you want.\n\n"
               (:body proposal)))))

(defn stage-by-name [config name]
  (or (some #(when (= name (:name %)) %) (:stages config))
      (exit! 1 (str "Unknown stage: " name))))

(defn predecessors [config name]
  (take-while #(not= name (:name %)) (:stages config)))

(defn run-command [root command]
  (println "$" (if (string? command) command (str/join " " command)))
  (let [options {:dir (str root) :out :inherit :err :inherit}
        process (if (string? command)
                  (process/process options "sh" "-c" command)
                  (apply process/process (concat [options] command)))
        result @process]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Stage command failed: " command)
                      {:exit (or (:exit result) 1)})))))

(defn glob? [value]
  (boolean (re-find #"[*?\[\]]" value)))

(defn expand-paths [root pattern]
  (if (glob? pattern)
    (vec (fs/glob root pattern))
    [(fs/path root pattern)]))

(defn spec-patterns [spec]
  (cond
    (string? spec) [spec]
    (sequential? spec) (mapv str spec)
    :else (exit! 1 "The spec stage needs :spec <path> or a vector of paths")))

(defn spec-files [root spec]
  (->> (spec-patterns spec)
       (mapcat (fn [pattern]
                 (cond
                   (glob? pattern) (fs/glob root pattern)
                   (fs/directory? (fs/path root pattern))
                   (fs/glob root (str pattern "/**"))
                   (fs/regular-file? (fs/path root pattern))
                   [(fs/path root pattern)]
                   :else [])))
       (filter fs/regular-file?)
       (map #(str (fs/relativize root %)))
       distinct
       sort
       vec))

(def report-metadata-headings
  #{"## Verification" "## Operator acceptance"})

(defn strip-report-metadata [text]
  (let [lines (str/split-lines (or text ""))
        trailing-newline? (str/ends-with? (or text "") "\n")]
    (loop [remaining lines
           skipping? false
           kept []]
      (if-let [line (first remaining)]
        (let [trimmed (str/trim line)]
          (cond
            skipping?
            (if (str/starts-with? trimmed "## ")
              (if (contains? report-metadata-headings trimmed)
                (recur (next remaining) true kept)
                (recur (next remaining) false (conj kept line)))
              (recur (next remaining) true kept))

            (contains? report-metadata-headings trimmed)
            (recur (next remaining) true kept)

            :else
            (recur (next remaining) false (conj kept line))))
        (let [result (str/join "\n" kept)]
          (if (and trailing-newline?
                   (not (str/ends-with? result "\n")))
            (str result "\n")
            result))))))

(defn spec-content [root file]
  (let [text (slurp (str (fs/path root file)))]
    (if (and (str/starts-with? file "tasks/")
             (str/ends-with? file ".md"))
      (strip-report-metadata text)
      text)))

(defn spec-hash [root files]
  (sha256 (.getBytes
           (str/join "\n"
                     (map #(str %
                                "\n"
                                (sha256 (.getBytes (spec-content root %)
                                                   "UTF-8")))
                          files))
           "UTF-8")))

(defn spec-body [root files]
  (str/join "\n\n"
            (for [file files]
              (str "--- " file " ---\n" (spec-content root file)))))

(declare approved-spec-proposal)

(defn spec-approved? [root config state]
  (boolean (approved-spec-proposal root config state)))

(defn maven-modules [root]
  (let [pom (fs/path root "pom.xml")]
    (when (fs/regular-file? pom)
      (->> (re-seq #"<module>\s*([^<]+?)\s*</module>" (slurp (str pom)))
           (mapv second)))))

(defn source-inputs [root]
  (let [modules (maven-modules root)]
    (if (seq modules)
      (vec (cons "pom.xml"
                 (mapcat (fn [module]
                           [(str module "/pom.xml")
                            (str module "/src/main")
                            (str module "/src/test")])
                         modules)))
      ["pom.xml" "src/main" "src/test"])))

(defn resolve-inputs [root stage]
  (mapcat
   (fn [input]
     (cond
       (= input :sources) (source-inputs root)
       (= input :features) ["features"]
       (= input :snapshots)
       (do
         (binding [*out* *err*]
           (println "  note: :snapshots widened to the whole tree"))
         ["."])
       (= input :whole-tree) ["."]
       (string? input) [input]
       :else
       (do
         (binding [*out* *err*]
           (println "  note: unknown stamp input" (pr-str input)
                    "- widened to the whole tree"))
         ["."])))
   (or (:stamp stage) [:whole-tree])))

(defn input-files [root pattern]
  (cond
    (= pattern ".") (->> (fs/glob root "**")
                         (filter fs/regular-file?)
                         (sort))
    (glob? pattern) (->> (fs/glob root pattern)
                         (filter fs/regular-file?)
                         (sort))
    (fs/directory? (fs/path root pattern))
    (->> (fs/glob root (str pattern "/**"))
         (filter fs/regular-file?)
         (sort))
    (fs/regular-file? (fs/path root pattern)) [(fs/path root pattern)]
    :else []))

(defn input-fingerprint-entries [root patterns]
  (->> patterns
       (mapcat
        (fn [pattern]
          (if (= pattern ".")
            [{:path "."
              :hash (whole-tree-hash root)}]
            (let [files (input-files root pattern)]
              (if (seq files)
                (for [file files]
                  {:path (str (fs/relativize root file))
                   :hash (file-hash file)})
                [{:path (str pattern) :missing true}])))))
       (sort-by :path)
       vec))

(defn content-hash [entries]
  (sha256 (.getBytes (pr-str entries) "UTF-8")))

(defn spec-stage [config]
  (some #(when (= "spec" (:name %)) %) (:stages config)))

(defn proposal-current? [root proposal]
  (try
    (and (seq (:files proposal))
         (= (:hash proposal)
            (spec-hash root (:files proposal))))
    (catch Exception _ false)))

(defn proposal-for-active-card? [state proposal]
  (let [card (:card state)
        proposal-card (:card proposal)]
    (and (map? card)
         (map? proposal-card)
         (not (str/blank? (:id card)))
         (= (:id card) (:id proposal-card)))))

(defn approved-spec-proposal [root config state]
  (let [stage (spec-stage config)
        spec (or (:spec stage) (:file stage))]
    (when spec
      (->> (vals (:proposals state))
           (filter #(= :approved (:status %)))
           (filter #(proposal-for-active-card? state %))
           (filter #(proposal-current? root %))
           (sort-by (juxt :at :id))
           last))))

(defn stage-input-fingerprint [root config state stage]
  (if (= "spec" (:name stage))
    (if-let [proposal (approved-spec-proposal root config state)]
      {:input_hash (:hash proposal)
       :inputs (:files proposal)
       :proposal (:id proposal)}
      (let [spec (or (:spec stage) (:file stage))
            files (if spec (spec-files root spec) [])]
        {:input_hash nil
         :inputs files
         :proposal nil}))
    (let [patterns (resolve-inputs root stage)]
      {:input_hash (content-hash (input-fingerprint-entries root patterns))
       :inputs patterns})))

(defn stage-fingerprints [root config state]
  (into {}
        (map (juxt :name #(stage-input-fingerprint root config state %)))
        (:stages config)))

(defn recorded? [state name fingerprints]
  (let [stored (get-in state [:stages name :input_hash])
        current (:input_hash (get fingerprints name))]
    (and (string? stored)
         (string? current)
         (= stored current))))

(defn blocked-by [config state fingerprints name]
  (->> (predecessors config name)
       (remove :optional)
       (remove #(recorded? state (:name %) fingerprints))
       (map :name)
       vec))

(defn collect-evidence [root patterns]
  (vec (for [pattern patterns
             file (expand-paths root pattern)
             :when (fs/regular-file? file)]
         {:path (str (fs/relativize root file))
          :hash (file-hash file)})))

(defn missing-evidence [root patterns]
  (vec (for [pattern patterns
             :when (empty? (filter fs/regular-file?
                                   (expand-paths root pattern)))]
         pattern)))

(defn record-stage [state name tree input-hash evidence inputs]
  (assoc-in state [:stages name]
            {:tree tree
             :at (now)
             :input_hash input-hash
             :evidence evidence
             :inputs (vec (distinct inputs))}))

(defn process-alive? [pid]
  (boolean
   (when pid
     (zero? (:exit (sh/sh "kill" "-0" (str pid)))))))

(defn begin-stage! [root config state name tree]
  (let [running {:stage name
                 :tree tree
                 :pid (.pid (java.lang.ProcessHandle/current))
                 :status :running
                 :started-at (now)}
        state (assoc state :running running)]
    (save-state root (:task config) state)
    (update-board! root config
                   (or (:card state) (fallback-card root config []))
                   name)
    state))

(defn finish-stage! [root config state name]
  (let [state (dissoc state :running)]
    (save-state root (:task config) state)
    (update-board! root config
                   (or (:card state) (fallback-card root config []))
                   name)
    (println (str "stage " name ": done at inputs "
                  (get-in state [:stages name :input_hash])
                  " (tree " (get-in state [:stages name :tree]) ")"))))

(defn fail-stage! [root config state code message]
  (let [running (assoc (:running state)
                       :status :failed
                       :failed-at (now)
                       :exit code
                       :message message)
        state (assoc state :running running)]
    (save-state root (:task config) state)
    (update-board! root config
                   (or (:card state) (fallback-card root config []))
                   (:stage running))))

(defn check-spec [root state stage tree fingerprint]
  (let [spec (or (:spec stage) (:file stage))]
    (when-not spec
      (throw (ex-info "The spec stage needs :spec <file> or a vector of paths"
                      {:exit 1})))
    (when-not (:input_hash fingerprint)
      (throw (ex-info "No current approved proposal for the active spec bundle"
                      {:exit 1})))
    (println "spec approved:" (:proposal fingerprint))
    (record-stage state "spec" tree (:input_hash fingerprint)
                  [{:proposal (:proposal fingerprint)
                    :hash (:input_hash fingerprint)
                    :files (:inputs fingerprint)}]
                  (:inputs fingerprint))))

(defn check! [root config state name]
  (let [stage (stage-by-name config name)
        tree (worktree-tree root)
        fingerprints (stage-fingerprints root config state)
        fingerprint (get fingerprints name)
        blocked (blocked-by config state fingerprints name)]
    (when (seq blocked)
      (exit! 1 (str "Stage " name " is blocked by: "
                    (str/join ", " blocked))))
    (let [state (begin-stage! root config state name tree)]
      (try
        (let [state
              (if (= :operator (:approval stage))
                (check-spec root state stage tree fingerprint)
                (do
                  (doseq [command (:runs stage)]
                    (run-command root command))
                  (let [patterns (:evidence stage)
                        missing (missing-evidence root patterns)]
                    (when (seq missing)
                      (throw (ex-info (str "Evidence missing: "
                                           (str/join ", " missing))
                                      {:exit 1})))
                    (record-stage state name tree (:input_hash fingerprint)
                                  (collect-evidence root patterns)
                                  (:inputs fingerprint)))))]
          (finish-stage! root config state name))
        (catch Throwable error
          (let [code (or (:exit (ex-data error)) 1)
                message (or (ex-message error) (str error))]
            (fail-stage! root config state code message)
            (exit! code message)))))))

(defn pending-proposals [state]
  (->> (:proposals state)
       (filter (fn [[_ proposal]] (= :pending (:status proposal))))
       (sort-by key)))

(defn active-card-board-lane! [root card]
  (let [name (:name card)
        id (:id card)]
    (when (or (str/blank? name) (str/blank? id))
      (exit! 1 (str "Cannot advance spec approval: malformed active card "
                    (pr-str card))))
    (let [matches (->> (board-rows root)
                       (filter #(and (= name (:name %))
                                     (= id (:id %))))
                       vec)]
      (when (empty? matches)
        (exit! 1 (str "Cannot advance spec approval: active card " name
                      " (" id ") is not on the board")))
      (when (> (count matches) 1)
        (exit! 1 (str "Cannot advance spec approval: more than one board row "
                      "matches " name " (" id ")")))
      (:lane (first matches)))))

(defn advance-approved-card! [root config card]
  (when (single-agent? root)
    (let [lane (active-card-board-lane! root card)]
      (cond
        (= lane "spec") (update-board! root config card "code")
        (#{"queued" "done"} lane)
        (exit! 1 (str "Cannot advance spec approval: active card " (:name card)
                      " (" (:id card) ") is in lane " lane))
        :else nil))))

(defn propose! [root config state patterns replace?]
  (let [files (spec-files root patterns)]
    (when (empty? files)
      (exit! 1 (str "No spec files found for " (str/join ", " patterns))))
    (let [id (str "spec-" (System/currentTimeMillis))
          pending (pending-proposals state)
          card (select-card root config state files)
          _ (when (single-agent? root)
              (active-card-board-lane! root card))
          automatic-spec? (= :automatic (spec-approval-mode root))
          correction-auto? (and (not automatic-spec?)
                                (not replace?)
                                (auto-approve-correction? root state card))
          auto-approve? (or automatic-spec? correction-auto?)
          supersede-pending? (or replace? automatic-spec?)
          approval-policy (cond
                            automatic-spec? "forge policy: automatic spec approval"
                            correction-auto? "correction after first operator approval")
          tree (worktree-tree root)]
      (when (and (seq pending) (not supersede-pending?))
        (exit! 1
               (str "Spec proposal(s) already pending: "
                    (str/join ", " (map first pending))
                    ". Approve or reject them, or pass --replace to supersede them.")))
      (let [state (if supersede-pending?
                    (reduce (fn [result [pending-id proposal]]
                              (record-resolution! root config
                                                  (or (:card proposal) card)
                                                  pending-id "superseded")
                              (assoc-in result [:proposals pending-id]
                                        (assoc proposal
                                               :status :superseded
                                               :superseded-at (now)
                                               :superseded-by id)))
                            state pending)
                    state)
            body (spec-body root files)
            proposal {:id id
                      :card card
                      :patterns (vec patterns)
                      :files files
                      :file (first files)
                      :hash (spec-hash root files)
                      :at (now)
                      :status (if auto-approve? :approved :pending)
                      :approved-at (when auto-approve? (now))
                      :approval-policy approval-policy
                      :words (cond
                               automatic-spec?
                               "automatic spec approval under forge policy"
                               correction-auto?
                               "single-agent correction after first approval")}
            state (assoc-in (assoc state :card card)
                            [:proposals id] proposal)
            state (if auto-approve?
                    (record-stage state "spec" tree (:hash proposal)
                                  [{:proposal id :hash (:hash proposal)
                                    :files files}]
                                  files)
                    state)]
        (save-state root (:task config) state)
        (remove-stage-approvals! root)
        (when auto-approve?
          (advance-approved-card! root config card)
          (record-resolution! root config card id
                              (if automatic-spec?
                                "automatic_spec_approval"
                                "auto_approved")))
        (when-not auto-approve?
          (update-board! root config card "spec")
          (write-stage-approval! root config (assoc proposal :body body)))
        (println (str (if correction-auto? "spec correction " "spec proposal ") id))
        (println (str "files: " (str/join ", " files)))
        (println (str "sha256: " (:hash proposal)))
        (println "---")
        (println body)
        (println "---")
        (if automatic-spec?
          (println "Auto-approved by forge policy; this single-agent forge does not request exact-spec approval.")
          (if correction-auto?
          (println "Auto-approved under the single-agent correction policy; this card already has its first operator approval.")
          (do
            (println (str "Ask the operator to approve this exact text with: "
                          "stage approve " id " --words \"<their words>\""))
            (println "The proposal is also in the project dashboard and the phone's approvals room."))))))))

(defn approve! [root config state id words]
  (let [proposal (get-in state [:proposals id])]
    (when-not proposal
      (exit! 1 (str "No proposal " id)))
    (when (= :approved (:status proposal))
      (exit! 1 (str "Proposal " id " is already approved; approval is single-use")))
    (let [patterns (or (:patterns proposal) [(:file proposal)])
          files (spec-files root patterns)]
      (when (empty? files)
        (exit! 1 (str "No spec files found for " (str/join ", " patterns))))
      (when-not (= (:hash proposal) (spec-hash root files))
        (exit! 1 "The spec changed since it was proposed; propose it again"))
      (when (str/blank? words)
        (exit! 1 "Pass the operator's own words with --words"))
      (let [tree (worktree-tree root)
            approved (assoc proposal
                            :status :approved
                            :approved-at (now)
                            :words words)
            state (assoc-in state [:proposals id] approved)
            state (record-stage state "spec" tree (:hash proposal)
                                [{:proposal id :hash (:hash proposal)
                                  :files files}]
                                files)]
        (save-state root (:task config) state)
        (record-resolution! root config (:card proposal) id "approved")
        (remove-stage-approvals! root)
        (advance-approved-card! root config (:card proposal))
        (println (str "approved " id " at tree " tree))))))

(defn reject! [root config state id words]
  (let [proposal (get-in state [:proposals id])]
    (when-not proposal
      (exit! 1 (str "No proposal " id)))
    (when (= :approved (:status proposal))
      (exit! 1 (str "Proposal " id " is already approved")))
    (when (str/blank? words)
      (exit! 1 "Pass the operator's own words with --words"))
    (let [rejected (assoc proposal
                          :status :sent_back
                          :sent-back-at (now)
                          :words words)
          state (assoc-in state [:proposals id] rejected)]
      (save-state root (:task config) state)
      (record-resolution! root config
                          (:card proposal)
                          id "sent_back")
      (remove-stage-approvals! root)
      (update-board! root config (:card proposal) "spec")
      (println (str "sent back " id)))))

(def report-path-re
  #"(?:(?<![A-Za-z0-9+.-])/(?:Users|home|private|var|tmp|opt|Volumes|srv)/[^\s)\]},>]+|[A-Za-z]:\\[^\s)\]},>]+)")

(def report-token-re
  #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b")

(def operator-acceptance-limit 8)
(def operator-field-limits
  {"owner" 80
   "environment" 120
   "checks" 500
   "status" 20})

(defn report-invalid! [message]
  (throw (ex-info message {:exit 1})))

(defn section-lines [lines heading]
  (let [headings (keep-indexed
                  (fn [index line]
                    (when (= heading (str/trim line)) index))
                  lines)]
    (when (> (count headings) 1)
      (report-invalid! (str "Task report has more than one " heading " section")))
    (when-let [start (first headings)]
      (let [end (or (some #(when (and (> % start)
                                      (str/starts-with?
                                       (str/trim (nth lines %)) "## "))
                              %)
                          (range (inc start) (count lines)))
                    (count lines))]
        {:present true
         :lines (subvec (vec lines) (inc start) end)}))))

(defn safe-report-field! [field value]
  (let [maximum (get operator-field-limits field)
        value (some-> value str/trim)]
    (when (or (str/blank? value)
              (> (count value) maximum)
              (re-find #"[\p{Cc}\p{Cf}]" value)
              (re-find report-path-re value)
              (re-find report-token-re value))
      (report-invalid! (str "Operator acceptance " field " is malformed or unsafe")))
    value))

(defn acceptance-fields [line]
  (when-not (str/starts-with? line "- ")
    (report-invalid! "Operator acceptance entries must begin with '- '"))
  (let [parts (str/split (subs line 2) #" \| " -1)]
    (when-not (= ["owner" "environment" "checks" "status"]
                 (mapv #(first (str/split % #": " 2)) parts))
      (report-invalid!
       "Operator acceptance entry needs owner, environment, checks and status"))
    (let [fields (into {}
                       (map (fn [part]
                              (let [[key value] (str/split part #": " 2)]
                                [key (safe-report-field! key value)]))
                            parts))
          owner (str/lower-case (get fields "owner"))
          status (str/lower-case (get fields "status"))]
      (when (not= "operator" owner)
        (report-invalid! "Operator acceptance owner must be operator"))
      (when-not (contains? #{"pending" "confirmed"} status)
        (report-invalid! "Operator acceptance status must be pending or confirmed"))
      {:owner "operator"
       :environment (get fields "environment")
       :checks (get fields "checks")
       :status (keyword status)})))

(defn parse-operator-acceptance [lines]
  (let [lines (vec (remove str/blank? lines))]
    (cond
      (and (= 1 (count lines))
           (= "none." (str/lower-case (first lines))))
      {:recorded true :verification_recorded true :status :none :entries []}

      (empty? lines)
      (report-invalid! "Operator acceptance must be None. or bounded entries")

      (> (count lines) operator-acceptance-limit)
      (report-invalid! "Operator acceptance has too many entries")

      (some #(= "none." (str/lower-case %)) lines)
      (report-invalid! "Operator acceptance None. cannot be combined with entries")

      :else
      (let [entries (mapv acceptance-fields lines)]
        {:recorded true
         :verification_recorded true
         :status (if (some #(= :pending (:status %)) entries)
                   :pending
                   :confirmed)
         :entries entries}))))

(defn parse-task-report [text]
  (let [lines (str/split-lines (or text ""))
        verification (section-lines lines "## Verification")
        acceptance (section-lines lines "## Operator acceptance")]
    (cond
      (and (nil? verification) (nil? acceptance))
      {:recorded false
       :verification_recorded false
       :status :not-recorded
       :entries []}

      (or (nil? verification) (nil? acceptance))
      (report-invalid!
       "Task report must include both ## Verification and ## Operator acceptance")

      (every? str/blank? (:lines verification))
      (report-invalid! "Task report ## Verification section must not be empty")

      :else
      (parse-operator-acceptance (:lines acceptance)))))

(defn task-report-file [root config state]
  (let [card (:card state)
        proposal (approved-spec-proposal root config state)
        tasks-dir (try (fs/canonicalize (fs/path root "tasks"))
                       (catch Exception _ nil))
        direct (when (and card (not (str/blank? (:name card))))
                 (try
                   (fs/canonicalize
                    (fs/path root "tasks" (str (:name card) ".md")))
                   (catch Exception _ nil)))
        direct (when (and direct tasks-dir
                          (= tasks-dir (fs/parent direct))
                          (fs/regular-file? direct))
                 direct)
        candidates (->> (:files proposal)
                        (filter #(str/starts-with? % "tasks/"))
                        (map #(fs/path root %))
                        (filter fs/regular-file?)
                        vec)]
    (or direct
        (when (= 1 (count candidates)) (first candidates)))))

(defn task-report [root config state]
  (if-not (single-agent? root)
    {:recorded false
     :verification_recorded false
     :status :not-recorded
     :entries []}
    (if-let [file (task-report-file root config state)]
      (try
        (parse-task-report (slurp (str file)))
        (catch Exception error
          (if (:exit (ex-data error))
            (throw error)
            (report-invalid! (or (ex-message error) "Task report is malformed")))))
      {:recorded false
       :verification_recorded false
       :status :not-recorded
       :entries []})))

(defn safe-task-report [root config state]
  (try
    (task-report root config state)
    (catch Exception error
      {:recorded true
       :verification_recorded false
       :status :invalid
       :entries []
       :error (or (ex-message error) "Task report is malformed")})))

(defn completion-report [root config state tree]
  (if (and (= tree (get-in state [:complete :tree]))
           (map? (get-in state [:complete :operator_acceptance])))
    (get-in state [:complete :operator_acceptance])
    (safe-task-report root config state)))

(defn required-stages-current? [config state fingerprints]
  (every? #(recorded? state (:name %) fingerprints)
          (remove :optional (:stages config))))

(defn completion-summary [root config state tree fingerprints]
  (let [automation? (required-stages-current? config state fingerprints)
        completed? (= tree (get-in state [:complete :tree]))
        report (completion-report root config state tree)]
    (cond
      (not automation?) "automation incomplete"
      (= :invalid (:status report))
      (str "automation complete; operator acceptance invalid: " (:error report))
      (= :pending (:status report))
      "automation complete; operator acceptance pending"
      completed? "fully complete"
      (= :not-recorded (:status report))
      "automation complete; completion not recorded (legacy task note)"
      :else "automation complete; completion not recorded")))

(defn operator-acceptance-summary [report]
  (case (:status report)
    :none "operator acceptance: none"
    :confirmed "operator acceptance: confirmed"
    :pending "operator acceptance: pending"
    :not-recorded "operator acceptance: not recorded (legacy task note)"
    :invalid "operator acceptance: invalid"
    "operator acceptance: unknown"))

(defn operator-acceptance-lines [report]
  (for [entry (:entries report)]
    (str "  - " (:owner entry) " | " (:environment entry)
         " | " (:checks entry) " | " (name (:status entry)))))

(defn stage-status [config state fingerprints stage]
  (let [name (:name stage)
        record (get-in state [:stages name])
        blocked (blocked-by config state fingerprints name)
        running (:running state)
        attempt (when (= name (:stage running)) running)
        attempt-status (when attempt
                         (case (:status attempt)
                           :failed "failed"
                           :running (if (process-alive? (:pid attempt))
                                      "running"
                                      "interrupted")
                           nil))]
    (cond
      attempt-status attempt-status
      (and record (recorded? state name fingerprints)) "done"
      record "stale"
      (:optional stage) "n/a"
      (seq blocked) "blocked"
      :else "todo")))

(defn stage-command [root stage]
  (if (seq (:runs stage))
    (str/join "; " (map #(if (string? %) % (str/join " " %)) (:runs stage)))
    (if (= :operator (:approval stage))
      (case (spec-approval-mode root)
        :automatic "(automatic approval)"
        "(operator approval)")
      "")))

(declare deploy-configuration-problem)

(defn list! [root config state]
  (let [tree (worktree-tree root)
        fingerprints (stage-fingerprints root config state)
        report (completion-report root config state tree)]
    (println (str "task: " (:task config)))
    (when-let [card (:card state)]
      (println (str "card: " (:name card) " (" (:id card) ")")))
    (println (str "tree: " tree))
    (println (str "completion: "
                  (completion-summary root config state tree fingerprints)))
    (println (operator-acceptance-summary report))
    (doseq [line (operator-acceptance-lines report)]
      (println line))
    (println "Each stage is a gate on the current vertical slice; tests, cleanup and hardening are not later tasks.")
    (when-let [problem (deploy-configuration-problem root config)]
      (println (str "warning: " problem)))
    (doseq [stage (:stages config)]
      (println (format "%-10s %-8s %s"
                       (:name stage)
                       (stage-status config state fingerprints stage)
                       (stage-command root stage)))
      (when (seq (:evidence stage))
        (println (str "           evidence: " (str/join ", " (:evidence stage)))))
      (let [inputs (:inputs (get fingerprints (:name stage)))]
        (when (seq inputs)
          (println (str "           inputs: " (str/join ", " inputs))))))))

(defn deploy-configuration-problem [root config]
  (when (and (single-agent? root)
             (fs/regular-file? (fs/path root "pom.xml")))
    (let [stage (some #(when (= "deploy" (:name %)) %) (:stages config))]
      (cond
        (nil? stage)
        "a single-agent Maven project needs a required deploy stage for local SNAPSHOT installation"

        (:optional stage)
        "the Maven project's local SNAPSHOT deploy stage must not be optional"

        :else nil))))

(defn status-json [root config state]
  (let [tree (worktree-tree root)
        head (head-tree root)
        fingerprints (stage-fingerprints root config state)
        report (completion-report root config state tree)]
    (json/generate-string
     {:task (:task config)
      :card (:card state)
      :tree tree
      :head-tree head
      :clean (= tree head)
      :completion_status (completion-summary root config state tree fingerprints)
      :automation_complete
      (required-stages-current? config state fingerprints)
      :operator_acceptance report
      :spec_approval_mode (name (spec-approval-mode root))
      :spec_approved (spec-approved? root config state)
      :complete (get-in state [:complete :tree])
      :running (:running state)
      :stages (mapv (fn [stage]
                      (let [fingerprint (get fingerprints (:name stage))
                            record (get-in state [:stages (:name stage)])]
                        {:name (:name stage)
                       :status (stage-status config state fingerprints stage)
                       :command (:runs stage)
                       :evidence (:evidence stage)
                       :inputs (vec (:inputs fingerprint))
                       :input_hash (:input_hash fingerprint)
                       :recorded_input_hash (:input_hash record)
                       :recorded_at (:at record)
                       :attempt (when (= (:name stage)
                                         (get-in state [:running :stage]))
                                  (:running state))}))
                    (:stages config))})))

(defn complete! [root config state]
  (let [tree (worktree-tree root)
        head (head-tree root)
        fingerprints (stage-fingerprints root config state)]
    (when-let [problem (deploy-configuration-problem root config)]
      (exit! 1 problem))
    (when-not head
      (exit! 1 "No HEAD commit; commit the project first"))
    (when-not (= tree head)
      (let [status (str/trim (:out (git root "status" "--porcelain")))]
        (exit! 1 "The working tree is not committed:"
               (when (seq status) status))))
    (let [missing (->> (:stages config)
                       (remove :optional)
                       (remove #(recorded? state (:name %) fingerprints))
                       (map :name)
                       vec)]
      (when (seq missing)
        (exit! 1 (str "Stages not current: " (str/join ", " missing))))
      (let [report (try
                     (task-report root config state)
                     (catch Exception error
                       (exit! 1 (or (ex-message error)
                                    "Task report is malformed"))))
            complete-state
            (assoc state :complete {:tree tree
                                    :at (now)
                                    :card (:card state)
                                    :task_report
                                    {:verification_recorded
                                     (:verification_recorded report)}
                                    :operator_acceptance report})]
        (save-state root (:task config) complete-state)
      (let [card (or (:card state) (fallback-card root config []))
            pack-board-sh (fs/path root "swarmforge" "scripts" "pack_board.sh")
            pack-board-bb (let [project-script (fs/path root "swarmforge" "scripts"
                                                         "pack_board.bb")]
                            (if (fs/regular-file? project-script)
                              project-script
                              (fs/path (fs/parent *file*) "pack_board.bb")))
            result (when (and (single-agent? root)
                              (some #(= (:name card) (:name %)) (board-rows root)))
                     (if (fs/executable? pack-board-sh)
                       (sh/sh (str pack-board-sh) "complete-promote"
                              "--name" (:name card) "--root" (str root))
                       (sh/sh "bb" (str pack-board-bb) "complete-promote"
                              "--name" (:name card) "--root" (str root))))]
        (if result
          (do
            (when-not (zero? (:exit result))
              (exit! (or (:exit result) 1)
                     (str/trim (str (:err result) (:out result)))))
            (print (:out result)))
          (update-board! root config card "done")))
        (println (operator-acceptance-summary report))
        (doseq [line (operator-acceptance-lines report)]
          (println line))
        (println (str "complete: " (:task config) " at tree " tree))))))

(defn parse-args [args]
  (loop [remaining args
         options {}
         positional []]
    (if (empty? remaining)
      {:options options :args positional}
      (let [argument (first remaining)]
        (cond
          (= argument "--root")
          (if-let [value (second remaining)]
            (recur (nnext remaining) (assoc options :root value) positional)
            (exit! 1 "--root needs a value"))

          (= argument "--words")
          (if-let [value (second remaining)]
            (recur (nnext remaining) (assoc options :words value) positional)
            (exit! 1 "--words needs a value"))

          (= argument "--replace")
          (recur (next remaining) (assoc options :replace true) positional)

          (= argument "--json")
          (recur (next remaining) (assoc options :json true) positional)

          (str/starts-with? argument "--")
          (exit! 1 (str "Unknown option: " argument))

          :else
          (recur (next remaining) options (conj positional argument)))))))

(defn -main [& args]
  (if (some #{"--help" "-h"} args)
    (usage)
    (let [{:keys [options args]} (parse-args args)
          root (fs/absolutize (or (:root options) "."))
          config (load-config root)
          state (hydrate-state-card root config
                                   (load-state root (:task config)))]
      (case (first args)
        "list" (list! root config state)
        "check" (if-let [name (second args)]
                  (check! root config state name)
                  (exit! 1 "Usage: stage check <name>"))
        "propose" (let [[_ kind & files] args]
                    (if (and (= "spec" kind) (seq files))
                      (propose! root config state files (:replace options))
                      (exit! 1 "Usage: stage propose spec <file>... [--replace]")))
        "approve" (if-let [id (second args)]
                    (approve! root config state id (:words options))
                    (exit! 1 "Usage: stage approve <proposal> --words \"<words>\""))
        "reject" (if-let [id (second args)]
                   (reject! root config state id (:words options))
                   (exit! 1 "Usage: stage reject <proposal> --words \"<words>\""))
        "complete" (complete! root config state)
        "status" (if (:json options)
                   (println (status-json root config state))
                   (list! root config state))
        "help" (usage)
        (usage)))))

(apply -main *command-line-args*)
