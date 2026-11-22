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

(load-file (str (fs/path (fs/parent *file*) "stage_jobs.bb")))

(def usage-text
  (str "Usage:\n"
       "  stage list [--root DIR]\n"
       "  stage check <name> [--detach] [--timeout-seconds N] [--retry] [--root DIR]\n"
       "  stage jobs [--json] [--root DIR]\n"
       "  stage job <job-id> [--json] [--root DIR]\n"
       "  stage cancel <job-id> [--root DIR]\n"
       "  stage retry <job-id> [--root DIR]\n"
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
       "A stage marked :detached true, or check --detach, launches one durable\n"
       "supervisor job and returns immediately. End the turn after a launch;\n"
       "the completion request wakes a later turn. Do not poll with sleeps.\n"
       "Use focused :stamp inputs for the files a gate actually consumes instead\n"
       "of copying :whole-tree. Missing inputs remain fail-visible.\n"))

(defn usage []
  (println usage-text))

(def stage-outcome-prefix "STAGE_OUTCOME_JSON:")
(def stage-outcome-version "forgelet.stage.outcome/v1")
(def ^:dynamic *outcome-context* (atom {}))

(def outcome-details
  {"STAGE_OK"
   ["success" "Continue with the returned stage state."]
   "STAGE_JOB_WAITING"
   ["waiting-for-job" "End the turn without polling; a completion wake will follow."]
   "STAGE_JOB_COMPLETED"
   ["completed" "Continue with the recorded stage result."]
   "STAGE_CANCELLATION_REQUESTED"
   ["waiting-for-job" "Wait for the supervisor's durable cancellation exit."]
   "STAGE_CONFIG_MISSING"
   ["failed" "Restore stages.edn or pass --root to the managed project."]
   "STAGE_CONFIG_INVALID"
   ["failed" "Repair the stage configuration and retry the same command."]
   "STAGE_UNKNOWN"
   ["failed" "Use one of the stage names returned by stage list."]
   "STAGE_BLOCKED"
   ["blocked" "Run or reconcile the named prerequisite stages in order."]
   "STAGE_CARD_MISSING"
   ["failed" "Route or restore exactly one active board card for this project."]
   "STAGE_CARD_AMBIGUOUS"
   ["failed" "Close or queue extra cards so exactly one active card remains."]
   "STAGE_CARD_INVALID"
   ["failed" "Repair the active card row and task_id, then retry."]
   "STAGE_SPEC_MISSING"
   ["failed" "Propose and approve the current spec before running the gate."]
   "STAGE_SPEC_PENDING"
   ["approval-required" "Approve, reject or explicitly replace the pending spec."]
   "STAGE_SPEC_CHANGED"
   ["failed" "Propose the changed spec again and obtain a fresh approval."]
   "STAGE_APPROVAL_INVALID"
   ["approval-required" "Use a fresh pending proposal and the operator's own approval words."]
   "STAGE_INPUT_INVALID"
   ["failed" "Correct the command input and retry."]
   "STAGE_REPORT_INVALID"
   ["failed" "Repair the bounded task report and retry completion."]
   "STAGE_JOB_ACTIVE"
   ["blocked" "Inspect the active job and wait for its completion wake."]
   "STAGE_JOB_ORPHANED"
   ["blocked" "Reconcile or retire the orphaned job through its owning task."]
   "STAGE_JOB_FAILED"
   ["failed" "Inspect the failed job and retry it explicitly."]
   "STAGE_JOB_TIMED_OUT"
   ["timed-out" "Inspect the timeout and retry explicitly."]
   "STAGE_JOB_CANCELLED"
   ["cancelled" "Start a new explicit retry when ready."]
   "STAGE_JOB_UNKNOWN"
   ["unknown" "Inspect identity and state; do not start a duplicate job."]
   "STAGE_JOB_NOT_FOUND"
   ["failed" "Use a current job id from stage jobs."]
   "STAGE_CANCEL_NOT_ALLOWED"
   ["failed" "Only a running job with matching process identity can be cancelled."]
   "STAGE_RETRY_NOT_ALLOWED"
   ["failed" "Retry only a failed, timed-out, cancelled, interrupted or unknown job."]
   "STAGE_EVIDENCE_MISSING"
   ["failed" "Produce the required evidence and rerun the gate."]
   "STAGE_COMMAND_FAILED"
   ["failed" "Inspect the command failure and correct the underlying gate."]
   "STAGE_TREE_DIRTY"
   ["failed" "Commit the reviewed working tree, then retry completion."]
   "STAGE_HEAD_MISSING"
   ["failed" "Create the project's initial commit before completion."]
   "STAGE_NOT_CURRENT"
   ["stale" "Run the named stale stages and record current evidence."]
   "STAGE_HELPER_FAILED"
   ["failed" "Inspect the retained human diagnostic and correct the stage request."]})

(defn bounded-outcome-text [value maximum]
  (let [text (-> (str (or value ""))
                 (str/replace #"(?<![\p{L}\p{N}_+.-])(?:~|/(?:Users|home|private|var|tmp|opt|Volumes|srv|Applications|Library|usr|etc|bin))/[^\s)\]},>]+"
                              "[redacted-path]")
                 (str/replace #"\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b"
                              "[redacted-token]")
                 (str/replace #"[\p{Cc}\p{Cf}]" " ")
                 (str/replace #"\s+" " ")
                 str/trim)]
    (subs text 0 (min maximum (count text)))))

(defn outcome-list [text prefix]
  (some-> (re-find (re-pattern (str prefix "(.+)$")) text)
          second
          (str/split #",\s*")
          vec
          not-empty))

(defn classify-stage-outcome [message]
  (cond
    (str/includes? message "No stages.edn") "STAGE_CONFIG_MISSING"
    (str/includes? message "No :stages") "STAGE_CONFIG_INVALID"
    (or (str/includes? message "required deploy stage")
        (str/includes? message "must not be optional")
        (str/includes? message "Invalid spec approval mode")) "STAGE_CONFIG_INVALID"
    (str/includes? message "Unknown stage:") "STAGE_UNKNOWN"
    (str/includes? message "is blocked by:") "STAGE_BLOCKED"
    (str/includes? message "More than one active card") "STAGE_CARD_AMBIGUOUS"
    (str/includes? message "Could not select the routed board card") "STAGE_CARD_MISSING"
    (str/includes? message "active card") "STAGE_CARD_INVALID"
    (str/includes? message "already pending") "STAGE_SPEC_PENDING"
    (or (str/includes? message "No current approved proposal")
        (str/includes? message "No proposal ")) "STAGE_SPEC_MISSING"
    (or (str/includes? message "spec changed since")
        (str/includes? message "Spec changed since")) "STAGE_SPEC_CHANGED"
    (or (str/includes? message "single-use")
        (str/includes? message "operator's own words")
        (str/includes? message "No spec files found")) "STAGE_APPROVAL_INVALID"
    (str/includes? message "orphaned stage job") "STAGE_JOB_ORPHANED"
    (str/includes? message " is timed-out") "STAGE_JOB_TIMED_OUT"
    (str/includes? message " is cancelled") "STAGE_JOB_CANCELLED"
    (str/includes? message " is unknown") "STAGE_JOB_UNKNOWN"
    (str/includes? message "retry explicitly") "STAGE_JOB_FAILED"
    (str/includes? message "Only a failed") "STAGE_RETRY_NOT_ALLOWED"
    (str/includes? message "is not running") "STAGE_CANCEL_NOT_ALLOWED"
    (str/includes? message "refusing cancellation") "STAGE_CANCEL_NOT_ALLOWED"
    (str/includes? message "Unknown stage job") "STAGE_JOB_NOT_FOUND"
    (str/includes? message "Evidence missing") "STAGE_EVIDENCE_MISSING"
    (str/includes? message "working tree is not committed") "STAGE_TREE_DIRTY"
    (str/includes? message "No HEAD commit") "STAGE_HEAD_MISSING"
    (str/includes? message "Stages not current") "STAGE_NOT_CURRENT"
    (or (str/includes? message "Task report")
        (str/includes? message "Operator acceptance")) "STAGE_REPORT_INVALID"
    (or (str/includes? message "Usage:")
        (str/includes? message "Unknown option")
        (str/includes? message " needs a value")
        (str/includes? message "Stage timeout")
        (str/includes? message "spec stage needs")) "STAGE_INPUT_INVALID"
    (str/includes? message "Stage command failed") "STAGE_COMMAND_FAILED"
    :else "STAGE_HELPER_FAILED"))

(defn stage-outcome-envelope [status code reason remediation exit-code]
  (let [context @*outcome-context*
        [default-status default-remediation] (get outcome-details code
                                                    (get outcome-details "STAGE_HELPER_FAILED"))]
    (cond-> {:version stage-outcome-version
             :status (or status default-status)
             :code code
             :reason (bounded-outcome-text reason 600)
             :remediation (bounded-outcome-text
                           (or remediation default-remediation) 400)
             :exit_code exit-code}
      (:project context) (assoc :project (:project context))
      (:card context) (assoc :card (:card context))
      (:stage context) (assoc :stage (:stage context))
      (:job_id context) (assoc :job_id (:job_id context))
      (:current_status context) (assoc :current_status (:current_status context))
      (seq (:missing context)) (assoc :missing (vec (take 20 (:missing context)))))))

(defn emit-stage-outcome! [stream outcome]
  (binding [*out* stream]
    (println (str stage-outcome-prefix " "
                  (json/generate-string outcome {:pretty false})))))

(defn exit! [status & messages]
  (let [reason (->> messages (remove nil?) (map str) (str/join " "))
        code (classify-stage-outcome reason)]
    (doseq [message messages]
      (when message
        (binding [*out* *err*]
          (println message))))
    (binding [*out* *err*]
      (emit-stage-outcome! *out*
                           (stage-outcome-envelope
                            nil code reason nil status))))
  (System/exit status))

(defn emit-stage-success! [result]
  (let [current-status (:current_status @*outcome-context*)
        [status code reason]
        (case current-status
          "waiting-for-job"
          ["waiting-for-job" "STAGE_JOB_WAITING"
           "stage job is waiting for supervised completion"]
          "completed"
          ["completed" "STAGE_JOB_COMPLETED"
           "stage job completed and was reconciled"]
          "cancellation-requested"
          ["waiting-for-job" "STAGE_CANCELLATION_REQUESTED"
           "stage job cancellation was requested"]
          ["success" "STAGE_OK"
           "stage command completed successfully"])]
    (emit-stage-outcome!
     *err*
     (stage-outcome-envelope status code reason nil 0))))

(defn outcome-card [card]
  (when (map? card)
    (cond-> {:name (bounded-outcome-text (:name card) 160)}
      (:id card) (assoc :id (bounded-outcome-text (:id card) 180))
      (:display card) (assoc :display (bounded-outcome-text (:display card) 200))
      (:lane card) (assoc :lane (bounded-outcome-text (:lane card) 80)))))

(defn now []
  (str (Instant/now)))

(def ^:dynamic *operation-cache* nil)

(defn cached-operation [key f]
  (if *operation-cache*
    (let [entry (delay (f))
          cached (get (swap! *operation-cache*
                             #(if (contains? % key)
                                %
                                (assoc % key entry)))
                      key)]
      @cached)
    (f)))

(defn sha256 [^bytes bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn file-hash [path]
  (cached-operation
   [:file-hash (str (fs/canonicalize (fs/path path)))]
   #(sha256 (Files/readAllBytes (fs/path path)))))

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
    (let [selected (assoc card :display (or (:display card) (:name card)))]
      (swap! *outcome-context* assoc :card (outcome-card selected))
      selected)))

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
  (cached-operation
   [:spec-content (str root) file]
   (fn []
     (let [text (slurp (str (fs/path root file)))]
       (if (and (str/starts-with? file "tasks/")
                (str/ends-with? file ".md"))
         (strip-report-metadata text)
         text)))))

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
  (cached-operation
   [:input-files (str root) pattern]
   (fn []
     (cond
       (= pattern ".") (->> (fs/glob root "**")
                            (filter fs/regular-file?)
                            (sort))
       (glob? pattern)
       (let [segments (str/split pattern #"/")
             prefix-parts (take-while #(not (re-find #"[*?\[]" %)) segments)
             prefix (str/join "/" prefix-parts)
             remaining (drop (count prefix-parts) segments)
             remaining-pattern (str/join "/" remaining)]
         (if (str/blank? prefix)
           (->> (fs/glob root pattern)
                (filter fs/regular-file?)
                (sort))
           (let [base (fs/path root prefix)]
             (if (fs/directory? base)
               (->> (fs/glob base remaining-pattern)
                    (filter fs/regular-file?)
                    (sort))
               []))))
       (fs/directory? (fs/path root pattern))
       (->> (fs/glob (fs/path root pattern) "**")
            (filter fs/regular-file?)
            (sort))
       (fs/regular-file? (fs/path root pattern)) [(fs/path root pattern)]
       :else []))))

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

(def default-stage-timeout-seconds 86400)
(def default-supervisor-heartbeat-ms 1000)
(def default-supervisor-poll-ms 200)
(def default-handshake-wait-ms 5000)

(defn supervisor-heartbeat-ms []
  (try
    (Long/parseLong
     (or (System/getenv "STAGE_JOB_HEARTBEAT_MS")
         (str default-supervisor-heartbeat-ms)))
    (catch Exception _ default-supervisor-heartbeat-ms)))

(defn supervisor-poll-ms []
  (try
    (Long/parseLong
     (or (System/getenv "STAGE_JOB_POLL_MS")
         (str default-supervisor-poll-ms)))
    (catch Exception _ default-supervisor-poll-ms)))

(defn handshake-wait-ms []
  (try
    (let [value (Long/parseLong
                 (or (System/getenv "STAGE_JOB_HANDSHAKE_WAIT_MS")
                     (str default-handshake-wait-ms)))]
      (max 0 value))
    (catch Exception _ default-handshake-wait-ms)))

(defn command-fingerprint [commands]
  (sha256 (.getBytes (pr-str (vec commands)) "UTF-8")))

(defn shell-quote [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn command-shell-text [command]
  (if (string? command)
    command
    (str/join " " (map shell-quote command))))

(defn stage-job-id [stage-name]
  (let [safe-stage (-> stage-name
                       str/lower-case
                       (str/replace #"[^a-z0-9._-]+" "-")
                       (str/replace #"^-+|-+$" ""))
        suffix (str/replace (str (java.util.UUID/randomUUID))
                            #"[^0-9A-Za-z]" "")
        generated (str "stage-job-" safe-stage "-"
                       (System/currentTimeMillis) "-" (subs suffix 0 8))]
    (if (re-matches stage-jobs/job-id-re generated)
      generated
      (exit! 1 "Could not create a safe stage job id"))))

(defn requested-role [root]
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (some-> (fs/path root ".swarmforge" "roles.tsv")
              str
              slurp
              str/split-lines
              first
              (str/split #"\t")
              first
              not-empty)
      "agent"))

(defn stage-timeout-seconds [stage options]
  (let [value (or (:timeout-seconds options)
                  (:timeout-seconds stage)
                  default-stage-timeout-seconds)]
    (try
      (let [parsed (Long/parseLong (str value))]
        (when-not (pos? parsed)
          (exit! 1 "Stage timeout must be a positive integer"))
        parsed)
      (catch NumberFormatException _
        (exit! 1 "Stage timeout must be a positive integer")))))

(defn stage-command-script!
  [root job]
  (let [log (stage-jobs/log-file root (:id job))
        commands (vec (:commands job))
        total (count commands)
        body
        (apply str
               "#!/bin/sh\n"
               "exec >> " (shell-quote log) " 2>&1\n"
               (mapcat
                (fn [index command]
                  [(str "printf '%s\\n' "
                        (shell-quote
                         (str "[stage-job] command " (inc index)
                              "/" total " started"))
                        "\n")
                   (str (command-shell-text command) "\n")
                   "status=$?\n"
                   "if [ \"$status\" -ne 0 ]; then\n"
                   "  printf '%s\\n' "
                   (shell-quote (str "[stage-job] command " (inc index)
                                     "/" total " failed"))
                   "\n"
                   "  exit \"$status\"\n"
                   "fi\n"
                   "printf '%s\\n' "
                   (shell-quote (str "[stage-job] command " (inc index)
                                     "/" total " completed"))
                   "\n"])
                (range)
                commands))
        script (fs/path (stage-jobs/jobs-dir root) (str (:id job) ".command.sh"))]
    (stage-jobs/atomic-write! script body)
    (fs/set-posix-file-permissions script "rwx------")
    script))

(defn launch-stage-supervisor! [root id]
  (let [bb (or (fs/which "bb") (exit! 1 "bb is not on PATH"))
        stage-script (fs/absolutize *file*)
        control (stage-jobs/control-log-file root id)
        socket (stage-jobs/supervisor-socket id)
        session (str "supervise-" id)
        command (str "exec " (shell-quote bb) " "
                     (shell-quote stage-script)
                     " _supervise-stage-job --root " (shell-quote root)
                     " --job-id " (shell-quote id)
                     " >> " (shell-quote control)
                     " 2>&1 < /dev/null")
        result
        (if (fs/which "tmux")
          (sh/sh "tmux" "-S" (str socket)
                 "new-session" "-d" "-s" session
                 "-c" (str root) command)
          (let [command (str "nohup " (shell-quote bb) " "
                             (shell-quote stage-script)
                             " _supervise-stage-job --root " (shell-quote root)
                             " --job-id " (shell-quote id)
                             " >> " (shell-quote control)
                             " 2>&1 < /dev/null &")]
            (sh/sh "sh" "-c" command)))]
    (fs/create-dirs (fs/parent control))
    (when-not (zero? (:exit result))
      (throw (ex-info "Could not launch stage supervisor"
                      {:exit 1 :result result})))
    (stage-jobs/update-job!
     root id #(assoc %
                     :supervisor_session session
                     :supervisor_socket (str socket)))))

(defn launch-stage-job!
  [root config state stage tree fingerprint options]
  (let [id (stage-job-id (:name stage))
        timeout (stage-timeout-seconds stage options)
        commands (vec (:runs stage))
        card (or (:card state) (fallback-card root config []))
        lane (or (:lane card) (:name stage))
        job {:id id
             :status "launching"
             :stage (:name stage)
             :project (:task config)
             :card (or (:display card) (:name card))
             :card_name (:name card)
             :card_id (:id card)
             :role (requested-role root)
             :requested_lane lane
             :tree tree
             :input_hash (:input_hash fingerprint)
             :inputs (vec (:inputs fingerprint))
             :command_fingerprint (command-fingerprint commands)
             :commands commands
             :evidence_patterns (vec (:evidence stage))
             :timeout_seconds timeout
             :created_at (now)
             :heartbeat_at (now)
             :progress "supervisor launching"}
        running {:stage (:name stage)
                 :status :waiting-for-job
                 :job-id id
                 :tree tree
                 :started-at (now)}]
    (stage-jobs/atomic-write-edn! (stage-jobs/job-file root id) job)
    (save-state root (:task config) (assoc state :running running))
    (update-board! root config card (:name stage))
    (swap! *outcome-context* assoc
           :job_id id
           :current_status "waiting-for-job")
    (try
      (launch-stage-supervisor! root id)
      (catch Throwable error
        (stage-jobs/atomic-write-edn!
         (stage-jobs/exit-file root id)
         {:job_id id
          :command_fingerprint (:command_fingerprint job)
          :status "failed"
          :exit 1
          :finished_at (now)
          :reason (or (ex-message error) "supervisor launch failed")})
        (stage-jobs/update-job!
         root id #(assoc % :status "failed"
                         :finished_at (now)
                         :reason (or (ex-message error)
                                     "supervisor launch failed")))
        (throw error)))
    (println (str "stage job " id ": waiting-for-job"))
    (println "End this model turn now. Do not poll or sleep.")
    (println "A completion request will wake a later turn to reconcile the job.")
    (let [current (stage-jobs/load-job root id)]
      (stage-jobs/append-event!
       root id
       {:event "yield-ready"
        :job_id id
        :project (:project current)
        :stage (:stage current)
        :card_id (:card_id current)
        :tree (:tree current)
        :input_hash (:input_hash current)
        :command_fingerprint (:command_fingerprint current)}))
    id))

(defn event-scope-matches-job?
  [event job]
  (and (= (:job_id event) (:id job))
       (= (:project event) (:project job))
       (= (:stage event) (:stage job))
       (= (:card_id event) (:card_id job))
       (= (:tree event) (:tree job))
       (= (:input_hash event) (:input_hash job))
       (= (:command_fingerprint event)
          (:command_fingerprint job))))

(defn valid-event-sequence? [events]
  (and (not= [:malformed] events)
       (every? map? events)
       (= (mapv :seq events)
          (vec (range 1 (inc (count events)))))
       (= (count events) (count (distinct (map :event events))))))

(defn mark-handshake-failure! [root id reason]
  (stage-jobs/update-job!
   root id #(assoc %
                   :handshake_status "failed"
                   :handshake_error reason
                   :reconcile_error reason
                   :handshake_at (now)))
  reason)

(defn queue-stage-job-wake!
  [root id handshake-status event-seq]
  (let [request-id (str "req-" id)
        request-script (fs/path (fs/parent *file*)
                                "pack_dashboard_request.sh")
        result (sh/sh (str request-script) "create"
                      "--root" (str root)
                      "--id" request-id
                      "--body" (str "Work complete for stage job " id))]
    (if (zero? (:exit result))
      (stage-jobs/update-job!
       root id #(assoc %
                       :wake_request_id (str/trim (:out result))
                       :handshake_status handshake-status
                       :handshake_event_seq event-seq
                       :handshake_at (now)))
      (stage-jobs/update-job!
       root id #(assoc % :wake_error
                       (stage-jobs/bounded-progress
                        (str (:err result) (:out result))))))
    (zero? (:exit result))))

(defn coordinate-stage-job-wake! [root id terminal-event]
  (let [wait-ms (handshake-wait-ms)
        ready? (fn [events]
                 (and (valid-event-sequence? events)
                      (some #(= "yield-ready" (:event %)) events)
                      (some #(= "terminal" (:event %)) events)))
        events (stage-jobs/wait-for-events! root id ready? wait-ms)
        job (stage-jobs/load-job root id)
        yield-event (some #(when (= "yield-ready" (:event %)) %) events)
        terminal (some #(when (= "terminal" (:event %)) %) events)
        exit (stage-jobs/load-exit root id)]
    (cond
      (not (valid-event-sequence? events))
      (mark-handshake-failure! root id "stage-job event log is malformed")

      (not (event-scope-matches-job? terminal job))
      (mark-handshake-failure! root id "terminal event scope does not match the job")

      (not (and (= (:supervisor_pid terminal) (:pid job))
                (= (:supervisor_start_identity terminal)
                   (:process_start_identity job))
                (stage-jobs/process-matches?
                 (:pid job) (:process_start_identity job))))
      (mark-handshake-failure!
       root id "terminal event supervisor identity does not match the job")

      (not (stage-jobs/exit-valid? id job exit))
      (mark-handshake-failure! root id "stage-job exit record is malformed")

      (and yield-event
           (not (event-scope-matches-job? yield-event job)))
      (mark-handshake-failure! root id "yield event scope does not match the job")

      yield-event
      (queue-stage-job-wake!
       root id "joined"
       [(:seq yield-event) (:seq terminal)])

      :else
      (queue-stage-job-wake!
       root id "fallback-terminal-only"
       [(:seq terminal)]))))

(defn finish-stage-job!
  [root id status exit-code reason]
  (let [job (stage-jobs/load-job root id)
        finished-at (now)
        missing (when (and job (= "succeeded" status))
                  (missing-evidence root (:evidence_patterns job)))
        status (if (seq missing) "failed" status)
        exit-code (if (seq missing) 1 exit-code)
        reason (or (when (seq missing)
                     (str "Evidence missing: " (str/join ", " missing)))
                   reason)
        evidence (when (and job (not (seq missing)))
                   (collect-evidence root (:evidence_patterns job)))
        exit-record {:job_id id
                     :command_fingerprint (:command_fingerprint job)
                     :status status
                     :exit exit-code
                     :finished_at finished-at
                     :reason reason
                     :evidence evidence
                     :progress (stage-jobs/progress-from-log
                                (stage-jobs/log-file root id))}]
    (stage-jobs/atomic-write-edn! (stage-jobs/exit-file root id) exit-record)
    (stage-jobs/update-job!
     root id #(assoc %
                     :status status
                     :finished_at finished-at
                     :heartbeat_at finished-at
                     :progress (or (:progress exit-record)
                                   (case status
                                     "succeeded" "command completed"
                                     status))
                     :reason reason))
    (let [job (stage-jobs/load-job root id)
          terminal (stage-jobs/append-event!
                    root id
                    {:event "terminal"
                     :job_id id
                     :project (:project job)
                     :stage (:stage job)
                     :card_id (:card_id job)
                     :tree (:tree job)
                     :input_hash (:input_hash job)
                     :command_fingerprint (:command_fingerprint job)
                     :supervisor_pid (:pid job)
                     :supervisor_start_identity
                     (:process_start_identity job)
                     :exit_status status
                     :exit exit-code})]
      (coordinate-stage-job-wake! root id terminal))
    status))

(defn destroy-command-tree! [pid]
  (when pid
    (let [maybe-handle (java.lang.ProcessHandle/of (long pid))
          handle (when (.isPresent maybe-handle) (.get maybe-handle))]
      (when handle
        (let [descendants (->> (.iterator (.descendants handle))
                               iterator-seq
                               vec)]
          (doseq [child (reverse descendants)]
            (.destroy ^java.lang.ProcessHandle child))
          (.destroy ^java.lang.ProcessHandle handle)
          (Thread/sleep 100)
          (doseq [child (reverse descendants)]
            (when (.isAlive ^java.lang.ProcessHandle child)
              (.destroyForcibly ^java.lang.ProcessHandle child)))
          (when (.isAlive ^java.lang.ProcessHandle handle)
            (.destroyForcibly ^java.lang.ProcessHandle handle)))))))

(defn supervise-stage-job! [root id]
  (let [job (or (stage-jobs/load-job root id)
                (exit! 1 (str "Unknown stage job: " id)))
        supervisor-pid (.pid (java.lang.ProcessHandle/current))
        supervisor-identity (stage-jobs/process-start-identity supervisor-pid)
        started-at (now)]
    (stage-jobs/update-job!
     root id #(assoc %
                     :status "running"
                     :pid supervisor-pid
                     :process_start_identity supervisor-identity
                     :started_at started-at
                     :heartbeat_at started-at
                     :progress "starting command"))
    (if (fs/exists? (stage-jobs/cancel-file root id))
      (finish-stage-job! root id "cancelled" 130 "cancelled before start")
      (let [script (stage-command-script! root job)
            child (process/process {:dir (str root) :out :string :err :string}
                                   "sh" (str script))
            child-pid (.pid (:proc child))
            child-identity (stage-jobs/process-start-identity child-pid)
            deadline (+ (System/currentTimeMillis)
                        (* 1000 (long (:timeout_seconds job))))]
        (stage-jobs/update-job!
         root id #(assoc %
                         :command_pid child-pid
                         :command_start_identity child-identity
                         :progress "command running"))
        (try
          ((fn []
             (loop [last-heartbeat 0]
               (let [current-ms (System/currentTimeMillis)
                     cancel? (fs/exists? (stage-jobs/cancel-file root id))
                     timed-out? (> current-ms deadline)
                     alive? (stage-jobs/process-matches?
                             child-pid child-identity)]
                 (cond
                cancel?
                (do
                  (destroy-command-tree! child-pid)
                  (try @child (catch Exception _ nil))
                  (finish-stage-job! root id "cancelled" 130
                                     "cancelled by operator"))

                timed-out?
                (do
                  (destroy-command-tree! child-pid)
                  (try @child (catch Exception _ nil))
                  (finish-stage-job! root id "timed-out" 124
                                     (str "timed out after "
                                          (:timeout_seconds job) " seconds")))

                (not alive?)
                (let [result (try @child
                                  (catch Exception error
                                    {:exit 1 :err (ex-message error)}))
                      code (long (or (:exit result) 1))]
                  (if (zero? code)
                    (finish-stage-job! root id "succeeded" 0 nil)
                    (finish-stage-job! root id "failed" code
                                       (str "command failed with exit " code))))

                   :else
                   (do
                     (let [heartbeat? (>= (- current-ms last-heartbeat)
                                          (supervisor-heartbeat-ms))]
                       (when heartbeat?
                         (stage-jobs/update-job!
                          root id
                          #(assoc %
                                  :heartbeat_at (now)
                                  :progress
                                  (or (stage-jobs/progress-from-log
                                       (stage-jobs/log-file root id))
                                      (:progress %)))))
                       (Thread/sleep (max 10 (supervisor-poll-ms)))
                       (recur (if heartbeat? current-ms last-heartbeat)))))))))
          (catch Throwable error
            (destroy-command-tree! child-pid)
            (finish-stage-job! root id "failed" 1
                               (or (ex-message error)
                                   "stage supervisor failed"))))))))

(defn mark-reconcile-failure! [root state id reason]
  (stage-jobs/update-job!
   root id #(assoc % :reconcile_error reason :reconciled_at (now)))
  (let [running (assoc (:running state)
                       :status :failed
                       :job-id id
                       :failed-at (now)
                       :exit 1
                       :message reason)
        state (assoc state :running running)]
    (save-state root (:task (load-config root)) state)
    state))

(defn reconcile-completed-job!
  [root config state id view]
  (let [job (:job view)
        exit (:exit view)
        stage-name (:stage job)
        stage (stage-by-name config stage-name)
        fingerprints (stage-fingerprints root config state)
        current (get fingerprints stage-name)
        current-tree (worktree-tree root)]
    (cond
      (not (stage-jobs/exit-valid? id job exit))
      (mark-reconcile-failure!
       root state id "stage job exit record is missing or malformed")

      (not= (:input_hash job) (:input_hash current))
      (mark-reconcile-failure!
       root state id "source stamps changed while the stage job ran")

      (not= current-tree (:tree job))
      (mark-reconcile-failure!
       root state id "stage job ran against a different source tree")

      (seq (missing-evidence root (:evidence stage)))
      (mark-reconcile-failure!
       root state id
       (str "Evidence missing: "
            (str/join ", " (missing-evidence root (:evidence stage)))))

      :else
      (let [evidence (collect-evidence root (:evidence stage))
            expected (:evidence exit)]
        (if (and (seq expected)
                 (not= expected evidence))
          (mark-reconcile-failure!
           root state id "stage job evidence changed before reconciliation")
          (let [recorded (assoc-in
                          (record-stage state stage-name (:tree job)
                                        (:input_hash job) evidence
                                        (:inputs current))
                          [:stages stage-name :job_id] id)
                state (dissoc recorded :running)]
            (save-state root (:task config) state)
            (stage-jobs/update-job!
             root id #(assoc % :consumed_at (now)
                             :reconciled_at (now)
                             :progress "completion consumed"))
            state))))))

(defn reconcile-stage-jobs! [root config state]
  (let [active-id (get-in state [:running :job-id])
        views (stage-jobs/blocking-views root)
        state
        (reduce
         (fn [current-state view]
           (let [id (:id view)
                 recorded-job (get-in current-state [:stages
                                                     (get-in view [:job :stage])
                                                     :job_id])]
             (cond
               (= "completed-unconsumed" (:status view))
               (if (= id recorded-job)
                 (do
                   (stage-jobs/update-job!
                    root id #(assoc % :consumed_at (now)
                                    :reconciled_at (now)))
                   (if (= id (get-in current-state [:running :job-id]))
                     (dissoc current-state :running)
                     current-state))
                 (if (= id active-id)
                   (reconcile-completed-job!
                    root config current-state id view)
                   current-state))

               (= id active-id)
               (let [status (:status view)
                     running (assoc (:running current-state)
                                    :status (keyword status)
                                    :job-id id
                                    :last_checked_at (now)
                                    :exit (get-in view [:exit :exit])
                                    :message (or (get-in view [:job :reconcile_error])
                                                 (get-in view [:exit :reason])))]
                 (save-state root (:task config)
                             (assoc current-state :running running))
                 (assoc current-state :running running))

               :else current-state)))
         state
         views)]
    state))

(defn active-job-summary [root state]
  (when-let [id (or (get-in state [:running :job-id])
                    (->> (vals (:stages state))
                         (filter :job_id)
                         (sort-by :at)
                         last
                         :job_id))]
    (let [view (stage-jobs/view root id)]
      (if-let [public (stage-jobs/public-view view (now))]
        (assoc public
               :state (if-let [reconciled (:reconcile_error (:job view))]
                        "failed"
                        (:status view))
               :reason (or (:reconcile_error (:job view))
                           (get-in view [:exit :reason])))
        {:id id :status "completed" :state "completed"}))))

(defn recorded-stage-job-id [state stage-name]
  (get-in state [:stages stage-name :job_id]))

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

(defn retry-ready? [view]
  (and (contains? #{"failed" "timed-out" "cancelled" "interrupted" "unknown"}
                  (:status view))
       (not (:live view))))

(defn retire-current-job! [root config state]
  (if-let [id (get-in state [:running :job-id])]
    (do
      (stage-jobs/update-job!
       root id #(assoc % :retired_at (now)
                       :retired_reason "explicit stage retry"))
      (let [state (dissoc state :running)]
        (save-state root (:task config) state)
        state))
    state))

(defn check! [root config state name options]
  (let [stage (stage-by-name config name)
        state (reconcile-stage-jobs! root config state)
        current-job-id (get-in state [:running :job-id])
        current-view (when current-job-id
                       (stage-jobs/view root current-job-id))
        recorded-job-id (recorded-stage-job-id state name)
        fingerprints (stage-fingerprints root config state)
        fingerprint (get fingerprints name)
        already-recorded? (and recorded-job-id
                                (recorded? state name fingerprints))
        orphan-jobs (remove #(= current-job-id (:id %))
                            (stage-jobs/blocking-views root))]
    (when (:retry options)
      (when-not (and current-view (retry-ready? current-view))
        (exit! 1 "Only a failed, timed-out, cancelled, interrupted or unknown stage job can be retried"))
      (let [state (retire-current-job! root config state)]
        (check! root config state name (dissoc options :retry))))
    (if current-view
      (case (:status current-view)
        "waiting-for-job"
        (do
          (swap! *outcome-context* assoc
                 :job_id current-job-id
                 :current_status "waiting-for-job")
          (println (str "stage job " current-job-id ": waiting-for-job"))
          (println "End this model turn now. Do not poll or sleep."))

        "completed-unconsumed"
        (do
          (swap! *outcome-context* assoc
                 :job_id current-job-id
                 :current_status "completed-unconsumed")
          (println (str "stage job " current-job-id
                        ": completion is ready to reconcile"))
          (println "Run stage status --json after the completion request is consumed."))

        ("failed" "timed-out" "cancelled" "interrupted" "unknown")
        (exit! 2
               (str "stage job " current-job-id " is " (:status current-view)
                    "; inspect it and retry explicitly with: stage retry "
                    current-job-id))

        (println (str "stage job " current-job-id " is "
                      (:status current-view))))
      (if already-recorded?
        (println (str "stage " name ": done from consumed stage job "
                      recorded-job-id
                      " at inputs "
                      (get-in state [:stages name :input_hash])))
        (if-let [orphan (first orphan-jobs)]
          (exit! 2
                 (str "Stage cannot start while orphaned stage job "
                      (:id orphan) " is " (:status orphan)
                      "; reconcile its owning task or retire it explicitly"))
          (let [tree (worktree-tree root)
            blocked (blocked-by config state fingerprints name)]
        (when (seq blocked)
          (exit! 1 (str "Stage " name " is blocked by: "
                        (str/join ", " blocked))))
        (if (or (:detach options) (:detached stage))
          (launch-stage-job! root config state stage tree fingerprint options)
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
                  (exit! code message))))))))))))

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
                         (if (:job-id attempt)
                           (let [status (:status attempt)]
                             (if (keyword? status)
                               (clojure.core/name status)
                               (str status)))
                           (case (:status attempt)
                             :failed "failed"
                             :running (if (process-alive? (:pid attempt))
                                        "running"
                                        "interrupted")
                             nil)))]
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
        report (completion-report root config state tree)
        active-job (active-job-summary root state)]
    (println (str "task: " (:task config)))
    (when-let [card (:card state)]
      (println (str "card: " (:name card) " (" (:id card) ")")))
    (println (str "tree: " tree))
    (println (str "completion: "
                  (completion-summary root config state tree fingerprints)))
    (when active-job
      (println (str "stage job: " (:id active-job)
                    " " (:state active-job)
                    " stage=" (:stage active-job)
                    " card=" (:card active-job)
                    " elapsed=" (:elapsed_seconds active-job) "s"
                    " heartbeat=" (or (:heartbeat_at active-job) "none")
                    " progress=" (or (:progress active-job) "unknown"))))
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
      :job (active-job-summary root state)
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

(defn job-status! [root id json?]
  (let [view (stage-jobs/view root id)]
    (when-not (:job view)
      (exit! 1 (str "Unknown stage job: " id)))
    (let [value (stage-jobs/public-view view (now))]
      (swap! *outcome-context* assoc
             :job_id id
             :current_status (:status view))
      (if json?
        (println (json/generate-string value))
        (doseq [key [:id :status :stage :card :requested_lane :role
                     :elapsed_seconds :heartbeat_at :progress
                     :timeout_seconds :exit :reason]]
          (when (contains? value key)
            (println (str (name key) "=" (get value key)))))))))

(defn jobs! [root json?]
  (let [values (mapv #(stage-jobs/public-view % (now))
                     (stage-jobs/blocking-views root))]
    (if json?
      (println (json/generate-string values))
      (if (seq values)
        (doseq [value values]
          (println (str (:id value) "\t" (:status value) "\t"
                        (:stage value) "\t" (:card value) "\t"
                        (:progress value))))
        (println "No blocking stage jobs")))))

(defn cancel-job! [root id]
  (let [view (stage-jobs/view root id)
        job (:job view)]
    (when-not job
      (exit! 1 (str "Unknown stage job: " id)))
    (when-not (contains? #{"launching" "running"} (:status job))
      (exit! 1 (str "Stage job " id " is not running")))
    (when-not (stage-jobs/process-matches?
               (:pid job) (:process_start_identity job))
      (exit! 1
             (str "Stage job " id
                  " supervisor identity is unavailable or stale; refusing cancellation")))
    (stage-jobs/atomic-write! (stage-jobs/cancel-file root id)
                              (str (now) "\n"))
    (swap! *outcome-context* assoc
           :job_id id
           :current_status "cancellation-requested")
    (println (str "cancellation requested for stage job " id))))

(defn retry-job! [root config state id options]
  (let [view (stage-jobs/view root id)
        job (:job view)]
    (when-not job
      (exit! 1 (str "Unknown stage job: " id)))
    (when-not (retry-ready? view)
      (exit! 1 (str "Stage job " id " is " (:status view)
                    " and cannot be retried")))
    (stage-jobs/update-job!
     root id #(assoc % :retired_at (now)
                     :retired_reason "explicit stage retry"))
    (let [state (if (= id (get-in state [:running :job-id]))
                  (let [state (dissoc state :running)]
                    (save-state root (:task config) state)
                    state)
                  state)]
      (check! root config state (:stage job)
              (assoc options :detach true :retry false)))))

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

          (= argument "--detach")
          (recur (next remaining) (assoc options :detach true) positional)

          (= argument "--retry")
          (recur (next remaining) (assoc options :retry true) positional)

          (= argument "--timeout-seconds")
          (if-let [value (second remaining)]
            (recur (nnext remaining)
                   (assoc options :timeout-seconds value)
                   positional)
            (exit! 1 "--timeout-seconds needs a value"))

          (= argument "--json")
          (recur (next remaining) (assoc options :json true) positional)

          (str/starts-with? argument "--")
          (exit! 1 (str "Unknown option: " argument))

          :else
          (recur (next remaining) options (conj positional argument)))))))

(defn option-value [args name]
  (second (drop-while #(not= name %) args)))

(defn -main [& args]
  (if (= "_supervise-stage-job" (first args))
    (let [root (fs/absolutize
                (or (option-value args "--root")
                    (exit! 1 "Supervisor needs --root")))
          id (or (option-value args "--job-id")
                 (exit! 1 "Supervisor needs --job-id"))]
      (binding [*operation-cache* (atom {})]
        (supervise-stage-job! root id)))
    (if (some #{"--help" "-h"} args)
    (usage)
    (let [{:keys [options args]} (parse-args args)
          root (fs/absolutize (or (:root options) "."))
          config (load-config root)
          state (reconcile-stage-jobs!
                 root config
                 (hydrate-state-card root config
                                     (load-state root (:task config))))
          command (first args)
          context (atom {:project (bounded-outcome-text (:task config) 160)
                         :card (outcome-card (:card state))})]
      (binding [*outcome-context* context]
        (let [result
              (case command
                "list" (list! root config state)
                "check" (if-let [name (second args)]
                          (do
                            (swap! context assoc :stage
                                   (bounded-outcome-text name 120))
                            (check! root config state name options))
                          (exit! 1 "Usage: stage check <name>"))
                "jobs" (jobs! root (:json options))
                "job" (if-let [id (second args)]
                        (do
                          (swap! context assoc :job_id
                                 (bounded-outcome-text id 180))
                          (job-status! root id (:json options)))
                        (exit! 1 "Usage: stage job <job-id>"))
                "cancel" (if-let [id (second args)]
                           (do
                             (swap! context assoc :job_id
                                    (bounded-outcome-text id 180))
                             (cancel-job! root id))
                           (exit! 1 "Usage: stage cancel <job-id>"))
                "retry" (if-let [id (second args)]
                          (do
                            (swap! context assoc :job_id
                                   (bounded-outcome-text id 180))
                            (retry-job! root config state id options))
                          (exit! 1 "Usage: stage retry <job-id>"))
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
                (usage))]
          (when (contains? #{"list" "check" "jobs" "job" "cancel"
                            "retry" "propose" "approve" "reject" "complete"
                            "status"}
                           command)
            (emit-stage-success! result))
          result))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (binding [*operation-cache* (atom {})]
    (apply -main *command-line-args*)))
