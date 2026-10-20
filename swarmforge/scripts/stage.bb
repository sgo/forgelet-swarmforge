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
       "  stage status [--json] [--root DIR]\n"))

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

(defn load-state [root task]
  (let [file (state-file root task)]
    (if (fs/regular-file? file)
      (read-edn file)
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

(defn update-board! [root config lane]
  (let [name (:task config)
        file (board-tasks-file root)
        now (now)
        rows (if (fs/regular-file? file)
               (->> (str/split-lines (slurp (str file)))
                    (remove str/blank?))
               [])
        existing (some #(when (= name (first (board-columns %))) %) rows)
        created (if existing (get (board-columns existing) 2 now) now)
        card (str/join "\t" [name lane created now name 0])
        kept (remove #(= name (first (board-columns %))) rows)]
    (fs/create-dirs (board-dir root))
    (spit (str file) (str (str/join "\n" (concat kept [card])) "\n"))
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

(defn record-resolution! [root config id resolution]
  (let [file (fs/path (resolved-approval-dir root) (str id ".edn"))]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (pr-str {:id id
                   :task (:task config)
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

(defn write-stage-approval! [root config proposal]
  (let [file (stage-approval-file root (:id proposal))
        card (card-name root config (:files proposal))]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (str "from: agent\n"
               "to: operator\n"
               "kind: stage-spec\n"
               "task: " card "\n"
               "task_id: " card "\n"
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

(defn recorded? [state name tree]
  (= tree (get-in state [:stages name :tree])))

(defn blocked-by [config state name tree]
  (->> (predecessors config name)
       (remove :optional)
       (remove #(recorded? state (:name %) tree))
       (map :name)
       vec))

(defn run-command [root command]
  (println "$" (if (string? command) command (str/join " " command)))
  (let [options {:dir (str root) :out :inherit :err :inherit}
        process (if (string? command)
                  (process/process options "sh" "-c" command)
                  (apply process/process (concat [options] command)))
        result @process]
    (when-not (zero? (:exit result))
      (exit! (or (:exit result) 1)
             (str "Stage command failed: " command)))))

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

(defn spec-hash [root files]
  (sha256 (.getBytes
           (str/join "\n"
                     (map #(str % "\n" (file-hash (fs/path root %)))
                          files))
           "UTF-8")))

(defn spec-body [root files]
  (str/join "\n\n"
            (for [file files]
              (str "--- " file " ---\n" (slurp (str (fs/path root file)))))))

(defn spec-approved? [root config state]
  (let [stage (some #(when (= "spec" (:name %)) %) (:stages config))
        spec (or (:spec stage) (:file stage))
        files (when spec (spec-files root spec))
        hash (when (seq files) (spec-hash root files))]
    (boolean
     (and hash
          (some #(and (= :approved (:status %))
                      (= hash (:hash %)))
                (vals (:proposals state)))))))

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
         (println "  note: :snapshots widened to the whole tree")
         ["."])
       (= input :whole-tree) ["."]
       (string? input) [input]
       :else
       (do
         (println "  note: unknown stamp input" (pr-str input)
                  "- widened to the whole tree")
         ["."])))
   (or (:stamp stage) [:whole-tree])))

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

(defn record-stage [state name tree evidence inputs]
  (assoc-in state [:stages name]
            {:tree tree
             :at (now)
             :evidence evidence
             :inputs (vec (distinct inputs))}))

(defn check-spec [root state stage tree]
  (let [spec (or (:spec stage) (:file stage))]
    (when-not spec
      (exit! 1 "The spec stage needs :spec <file> or a vector of paths"))
    (let [files (spec-files root spec)
          hash (when (seq files) (spec-hash root files))
          approved (->> (vals (:proposals state))
                        (filter #(and (= :approved (:status %))
                                      (= hash (:hash %))))
                        first)]
      (when (empty? files)
        (exit! 1 (str "No spec files found for " (pr-str spec))))
      (when-not approved
        (exit! 1 (str "No current approved proposal for "
                      (str/join ", " files) " at " hash)))
      (println "spec approved:" (:id approved))
      (record-stage state "spec" tree
                    [{:proposal (:id approved) :hash hash :files files}]
                    files))))

(defn check! [root config state name]
  (let [stage (stage-by-name config name)
        tree (worktree-tree root)
        blocked (blocked-by config state name tree)]
    (when (seq blocked)
      (exit! 1 (str "Stage " name " is blocked by: "
                    (str/join ", " blocked))))
    (let [state
          (if (= :operator (:approval stage))
            (check-spec root state stage tree)
            (do
              (doseq [command (:runs stage)]
                (run-command root command))
              (let [patterns (:evidence stage)
                    missing (missing-evidence root patterns)]
                (when (seq missing)
                  (exit! 1 (str "Evidence missing: "
                                (str/join ", " missing))))
                (record-stage state name tree
                              (collect-evidence root patterns)
                              (resolve-inputs root stage)))))]
      (save-state root (:task config) state)
      (update-board! root config name)
      (println (str "stage " name ": done at tree " tree)))))

(defn pending-proposals [state]
  (->> (:proposals state)
       (filter (fn [[_ proposal]] (= :pending (:status proposal))))
       (sort-by key)))

(defn propose! [root config state patterns replace?]
  (let [files (spec-files root patterns)]
    (when (empty? files)
      (exit! 1 (str "No spec files found for " (str/join ", " patterns))))
    (let [id (str "spec-" (System/currentTimeMillis))
          pending (pending-proposals state)]
      (when (and (seq pending) (not replace?))
        (exit! 1
               (str "Spec proposal(s) already pending: "
                    (str/join ", " (map first pending))
                    ". Approve or reject them, or pass --replace to supersede them.")))
      (let [state (if replace?
                    (reduce (fn [result [pending-id proposal]]
                              (record-resolution! root config pending-id "superseded")
                              (assoc-in result [:proposals pending-id]
                                        (assoc proposal
                                               :status :superseded
                                               :superseded-at (now)
                                               :superseded-by id)))
                            state pending)
                    state)
            proposal {:id id
                      :patterns (vec patterns)
                      :files files
                      :file (first files)
                      :hash (spec-hash root files)
                      :body (spec-body root files)
                      :at (now)
                      :status :pending}
            state (assoc-in state [:proposals id] proposal)]
        (save-state root (:task config) state)
        (remove-stage-approvals! root)
        (update-board! root config "spec")
        (write-stage-approval! root config proposal)
        (println (str "spec proposal " id))
        (println (str "files: " (str/join ", " files)))
        (println (str "sha256: " (:hash proposal)))
        (println "---")
        (println (:body proposal))
        (println "---")
        (println (str "Ask the operator to approve this exact text with: "
                      "stage approve " id " --words \"<their words>\""))
        (println "The proposal is also in the project dashboard and the phone's approvals room.")))))

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
            state (record-stage state "spec" tree
                                [{:proposal id :hash (:hash proposal)
                                  :files files}]
                                files)]
        (save-state root (:task config) state)
        (record-resolution! root config id "approved")
        (remove-stage-approvals! root)
        (update-board! root config "spec")
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
      (record-resolution! root config id "sent_back")
      (remove-stage-approvals! root)
      (update-board! root config "spec")
      (println (str "sent back " id)))))

(defn stage-status [config state stage tree]
  (let [name (:name stage)
        record (get-in state [:stages name])
        blocked (blocked-by config state name tree)]
    (cond
      (and record (= tree (:tree record))) "done"
      record "stale"
      (:optional stage) "n/a"
      (seq blocked) "blocked"
      :else "todo")))

(defn stage-command [stage]
  (if (seq (:runs stage))
    (str/join "; " (map #(if (string? %) % (str/join " " %)) (:runs stage)))
    (if (= :operator (:approval stage))
      "(operator approval)"
      "")))

(defn list! [root config state]
  (let [tree (worktree-tree root)]
    (println (str "task: " (:task config)))
    (println (str "tree: " tree))
    (println "Each stage is a gate on the current vertical slice; tests, cleanup and hardening are not later tasks.")
    (doseq [stage (:stages config)]
      (println (format "%-10s %-8s %s"
                       (:name stage)
                       (stage-status config state stage tree)
                       (stage-command stage)))
      (let [inputs (resolve-inputs root stage)]
        (when (seq inputs)
          (println (str "           inputs: " (str/join ", " inputs))))))))

(defn status-json [root config state]
  (let [tree (worktree-tree root)
        head (head-tree root)]
    (json/generate-string
     {:task (:task config)
      :tree tree
      :head-tree head
      :clean (= tree head)
      :spec_approved (spec-approved? root config state)
      :complete (get-in state [:complete :tree])
      :stages (mapv (fn [stage]
                      {:name (:name stage)
                       :status (stage-status config state stage tree)
                       :command (:runs stage)
                       :evidence (:evidence stage)
                       :inputs (vec (resolve-inputs root stage))
                       :recorded-at (get-in state [:stages (:name stage) :at])})
                    (:stages config))})))

(defn complete! [root config state]
  (let [tree (worktree-tree root)
        head (head-tree root)]
    (when-not head
      (exit! 1 "No HEAD commit; commit the project first"))
    (when-not (= tree head)
      (let [status (str/trim (:out (git root "status" "--porcelain")))]
        (exit! 1 "The working tree is not committed:"
               (when (seq status) status))))
    (let [missing (->> (:stages config)
                       (remove :optional)
                       (remove #(recorded? state (:name %) tree))
                       (map :name)
                       vec)]
      (when (seq missing)
        (exit! 1 (str "Stages not current: " (str/join ", " missing))))
      (save-state root (:task config)
                  (assoc state :complete {:tree tree :at (now)}))
      (update-board! root config "done")
      (println (str "complete: " (:task config) " at tree " tree)))))

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
          state (load-state root (:task config))]
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
