#!/usr/bin/env bb

(ns forge-runtime-upgrade
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption]
           [java.security MessageDigest]))

(when-let [stage-jobs-file (fs/path (fs/parent *file*) "stage_jobs.bb")]
  (when (fs/regular-file? stage-jobs-file)
    (load-file (str stage-jobs-file))))

(def manifest-version 1)
(def max-blockers 20)
(def max-changes 20)
(def max-upgrade-lines 20)
(def products #{"two-pack" "four-pack" "six-pack"
                "project-manager" "lieutenant"})
(def source-commit-re #"[0-9a-f]{40}")
(def digest-re #"sha256:[0-9a-f]{64}")
(def file-hash-re #"[0-9a-f]{64}")
(def shared-articles
  ["engineering.prompt" "workflow.prompt" "handoffs.prompt" "privacy.prompt"
   "stopping-without-finishing.prompt"])
(def operation-dir-name "runtime-upgrades")

(def usage-text
  (str "Usage:\n"
       "  forge_runtime_upgrade.sh status --forge <dir> --source-layer <dir>\n"
       "                                       [--source-commit <sha>] [--json]\n"
       "  forge_runtime_upgrade.sh apply --forge <dir> --source-layer <dir>\n"
       "                                      [--source-commit <sha>] [--json]\n"
       "\n"
       "The source layer is the published checkout that get-swarm-forge composes\n"
       "from. Status and apply always emit one JSON object. Apply refuses busy\n"
       "forges, serializes work under the target forge, and records bounded phases\n"
       "under .swarmforge/runtime-upgrades/.\n"))

(defn fail-usage! [message]
  (binding [*out* *err*]
    (println message)
    (print usage-text)
    (flush))
  (System/exit 64))

(defn option [args name]
  (loop [remaining args]
    (cond
      (empty? remaining) nil
      (= (first remaining) name)
      (or (second remaining) (fail-usage! (str name " needs a value")))
      :else (recur (next remaining)))))

(defn flag? [args name]
  (boolean (some #{name} args)))

(defn hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn sha256-bytes [bytes]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn sha256-text [text]
  (sha256-bytes (.getBytes (str text) "UTF-8")))

(defn sha256-file [file]
  (with-open [input (java.io.FileInputStream. (fs/file file))]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur))))
      (hex (.digest digest)))))

(defn safe-relative-path? [path]
  (let [path (str path)]
    (and (not (str/blank? path))
         (not (fs/absolute? path))
         (not (re-find #"[\p{Cc}\p{Cf}]" path))
         (not (some #{".."} (str/split path #"/"))))))

(defn regular-files-under [root relative]
  (let [path (fs/path root relative)]
    (if (fs/directory? path)
      (->> (file-seq (fs/file path))
           (filter fs/regular-file?)
           (map #(-> (fs/relativize root %)
                     str
                     (str/replace "\\" "/")))
           vec)
      (if (fs/regular-file? path) [relative] []))))

(defn layer-shared-files [source]
  (let [source (fs/absolutize source)
        files (concat
               ["swarm"
                "swarmforge/constitution.prompt"
                "swarmforge/roles/lieutenant.prompt"]
               (map #(str "swarmforge/constitution/articles/" %)
                    shared-articles)
               (regular-files-under source "swarmforge/scripts")
               (regular-files-under source "swarmforge/skills"))]
    (->> files
         (filter #(fs/regular-file? (fs/path source %)))
         distinct
         (filter safe-relative-path?)
         sort
         vec)))

(defn digest-files [files]
  (sha256-text
   (str/join "\n" (map (fn [[path hash]] (str path "\t" hash)) files))))

(defn composition-files [target source]
  (mapv (fn [relative]
          (let [file (fs/path target relative)]
            (when-not (fs/regular-file? file)
              (throw (ex-info "Composed shared file is missing"
                              {:kind :missing-composed-file
                               :path relative})))
            [relative (sha256-file file)]))
        (layer-shared-files source)))

(def manifest-excludes
  ["projects"
   "packs"
   "tmp"
   ".worktrees"
   ".swarmforge"
   "swarmforge/swarmforge.conf"
   "swarmforge/roles/local-lieutenant.prompt"])

(defn write-manifest! [root source product source-commit]
  (let [root (fs/absolutize root)
        files (composition-files root source)
        manifest {:version manifest-version
                  :product product
                  :source_commit source-commit
                  :digest (str "sha256:" (digest-files files))
                  :files files
                  :excludes manifest-excludes}
        file (fs/path root ".swarmforge" "runtime-manifest.edn")]
    (fs/create-dirs (fs/parent file))
    (let [tmp (fs/create-temp-file {:dir (fs/parent file)
                                    :prefix ".runtime-manifest."})]
      (spit (str tmp) (pr-str manifest))
      (fs/move tmp file {:replace-existing true :atomic-move true}))
    manifest))

(defn manifest-problem [manifest]
  (cond
    (not (map? manifest)) "installed manifest is malformed"
    (not= manifest-version (:version manifest)) "installed manifest version is unsupported"
    (not (products (:product manifest))) "installed manifest product is invalid"
    (not (re-matches source-commit-re (str (:source_commit manifest))))
    "installed manifest source commit is invalid"
    (not (re-matches digest-re (str (:digest manifest))))
    "installed manifest digest is invalid"
    (not (vector? (:files manifest))) "installed manifest file list is malformed"
    (not (every? (fn [entry]
                   (and (vector? entry)
                        (= 2 (count entry))
                        (safe-relative-path? (first entry))
                        (re-matches file-hash-re (str (second entry)))))
                 (:files manifest)))
    "installed manifest file list is malformed"
    (not= (:digest manifest)
          (str "sha256:" (digest-files (:files manifest))))
    "installed manifest digest does not match its file list"
    :else nil))

(defn read-manifest [root]
  (let [file (fs/path root ".swarmforge" "runtime-manifest.edn")]
    (if-not (fs/regular-file? file)
      {:problem "installed runtime manifest is missing"}
      (try
        (let [manifest (edn/read-string (slurp (str file)))]
          (if-let [problem (manifest-problem manifest)]
            {:problem problem}
            {:manifest manifest}))
        (catch Exception _
          {:problem "installed runtime manifest is malformed"})))))

(defn source-commit [source explicit]
  (let [explicit (some-> explicit str/trim not-empty)]
    (cond
      (and explicit (re-matches source-commit-re explicit)) explicit
      explicit nil
      :else
      (let [result (process/sh {:continue true}
                               "git" "-C" (str source)
                               "rev-parse" "HEAD")
            commit (str/trim (:out result))]
        (when (and (zero? (:exit result))
                   (re-matches source-commit-re commit))
          commit)))))

(defn command-env [extra]
  (merge (into {} (System/getenv)) extra))

(defn compose! [source target product commit check?]
  (let [composer (fs/path source "get-swarm-forge")]
    (when-not (and (fs/regular-file? composer) (fs/executable? composer))
      (throw (ex-info "Published composer is unavailable"
                      {:kind :composer-unavailable})))
    (fs/create-dirs target)
    (let [result (process/sh
                  {:continue true
                   :dir (str target)
                   :env (command-env
                         (cond-> {"SWARMFORGE_NO_SELF_UPDATE" "1"
                                  "SWARMFORGE_SOURCE_COMMIT" commit}
                           check? (assoc "SWARMFORGE_SKIP_BRIDGE" "1")))}
                  (str composer) product)]
      (when-not (zero? (:exit result))
        (throw (ex-info "Composition failed"
                        {:kind :compose-failed
                         :exit (:exit result)}))))))

(defn available-manifest! [forge source product commit check?]
  (let [check-dir (fs/path forge ".swarmforge" operation-dir-name
                           (str "check-" (random-uuid)))]
    (try
      (compose! source check-dir product commit check?)
      (write-manifest! check-dir source product commit)
      (let [read (read-manifest check-dir)]
        (when (:problem read)
          (throw (ex-info (:problem read) {:kind :available-manifest-invalid})))
        (:manifest read))
      (finally
        (fs/delete-tree check-dir)))))

(defn read-open-projects [forge]
  (let [file (fs/path forge ".swarmforge" "open-projects")]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (map str/trim)
           (remove str/blank?)
           distinct
           sort
           vec)
      [])))

(defn active-board-blockers [forge project-name]
  (let [project (fs/path forge "projects" project-name)
        file (fs/path project ".swarmforge" "board" "tasks.tsv")
        cards (if-not (fs/regular-file? file)
                []
                (->> (str/split-lines (slurp (str file)))
                     (remove str/blank?)
                     (keep (fn [line]
                             (let [[card lane] (str/split line #"\t" -1)]
                               (when (and (not (str/blank? card))
                                          (not (contains? #{"done" "queued"} lane)))
                                 {:project project-name
                                  :card card
                                  :kind "active-card"}))))
                     vec))
        stage-jobs
        (if (find-ns 'stage-jobs)
          (let [blocking-views @(requiring-resolve 'stage-jobs/blocking-views)
                public-view @(requiring-resolve 'stage-jobs/public-view)
                jobs-now @(requiring-resolve 'stage-jobs/now)]
            (->> (blocking-views project)
                 (keep (fn [view]
                         (when-let [job (public-view view (jobs-now))]
                           {:project project-name
                            :card (:card job)
                            :kind "stage-job"
                            :job (:id job)
                            :status (:status job)})))
                 vec))
          [])]
    (vec (concat cards stage-jobs))))

(defn idle-state [forge]
  (let [open (read-open-projects forge)
        blockers (mapcat
                  (fn [name]
                    (let [project (fs/path forge "projects" name)]
                      (if-not (fs/directory? project)
                        [{:project name :kind "open-project-missing"}]
                        (active-board-blockers forge name))))
                  open)
        shown (take max-blockers blockers)]
    {:idle (empty? blockers)
     :open_count (count open)
     :blockers (vec shown)
     :blockers_truncated (> (count blockers) (count shown))}))

(defn project-upgrade-check [forge source]
  (let [helper (fs/path source "get-agent-project")
        open (read-open-projects forge)]
    (if-not (and (fs/regular-file? helper) (fs/executable? helper))
      {:required [] :lines [] :checked false}
      (let [checked
            (reduce
             (fn [result name]
               (let [project (fs/path forge "projects" name)]
                 (if-not (fs/regular-file?
                          (fs/path project ".swarmforge" "single-agent"))
                   result
                   (let [result-at (process/sh
                                    {:continue true :dir (str forge)}
                                    (str helper) "--forge" (str forge)
                                    "--upgrade" name "--check")
                         exit (:exit result-at)]
                     (cond
                       (zero? exit) result
                       (= 1 exit)
                       (-> result
                           (update :required conj name)
                           (update :lines into
                                   (->> (str/split-lines (:out result-at))
                                        (map str/trim)
                                        (remove str/blank?))))
                       :else
                       (-> result
                           (assoc :checked false)
                           (update :errors (fnil conj []) name)))))))
             {:required [] :lines [] :checked true :errors []}
             open)]
        {:required (vec (take max-blockers (:required checked)))
         :lines (vec (take max-upgrade-lines (:lines checked)))
         :checked (:checked checked)
         :errors (vec (take max-blockers (:errors checked)))}))))

(defn change-list [installed available]
  (let [left (into {} (:files installed))
        right (into {} (:files available))
        paths (sort (into (set (keys left)) (set (keys right))))
        changes (keep (fn [path]
                        (let [a (get left path)
                              b (get right path)]
                          (cond
                            (nil? a) {:path path :change "added"}
                            (nil? b) {:path path :change "removed"}
                            (not= a b) {:path path :change "updated"})))
                      paths)]
    {:count (count changes)
     :items (vec (take max-changes changes))
     :truncated (> (count changes) max-changes)}))

(defn status-core [forge source explicit-commit]
  (let [forge (fs/absolutize forge)
        source (fs/absolutize source)
        installed-result (read-manifest forge)
        idle (idle-state forge)]
    (if (:problem installed-result)
      {:status "unknown"
       :reason (:problem installed-result)
       :installed nil
       :available nil
       :idle (:idle idle)
       :open_count (:open_count idle)
       :blockers (:blockers idle)
       :blockers_truncated (:blockers_truncated idle)
       :changes {:count 0 :items [] :truncated false}
       :project_upgrades_required []
       :project_upgrade_lines []}
      (let [installed (:manifest installed-result)
            commit (source-commit source explicit-commit)
            product (:product installed)]
        (if-not commit
          {:status "unavailable"
           :reason "published source commit is unavailable"
           :installed (select-keys installed [:digest :source_commit :product])
           :available nil
           :idle (:idle idle)
           :open_count (:open_count idle)
           :blockers (:blockers idle)
           :blockers_truncated (:blockers_truncated idle)
           :changes {:count 0 :items [] :truncated false}
           :project_upgrades_required []
           :project_upgrade_lines []}
          (try
            (let [available (available-manifest! forge source product commit true)
                  upgrades (project-upgrade-check forge source)
                  changes (change-list installed available)
                  status (cond
                           (or (false? (:checked upgrades))
                               (seq (:errors upgrades)))
                           "unavailable"
                           (= (:digest installed) (:digest available))
                           "current"
                           :else "behind")]
              {:status status
               :reason (when (= status "unavailable")
                         "project template upgrade check is unavailable")
               :installed (select-keys installed [:digest :source_commit :product])
               :available (select-keys available [:digest :source_commit :product])
               :idle (:idle idle)
               :open_count (:open_count idle)
               :blockers (:blockers idle)
               :blockers_truncated (:blockers_truncated idle)
               :changes changes
               :project_upgrades_required (vec (distinct (:required upgrades)))
               :project_upgrade_lines (vec (:lines upgrades))})
            (catch Exception _
              {:status "unavailable"
               :reason "published composition could not be checked"
               :installed (select-keys installed [:digest :source_commit :product])
               :available nil
               :idle (:idle idle)
               :open_count (:open_count idle)
               :blockers (:blockers idle)
               :blockers_truncated (:blockers_truncated idle)
               :changes {:count 0 :items [] :truncated false}
               :project_upgrades_required []
               :project_upgrade_lines []})))))))

(defn upgrade-dir [forge]
  (fs/path forge ".swarmforge" operation-dir-name))

(defn lock-file [forge]
  (fs/path (upgrade-dir forge) "upgrade.lock"))

(defn with-upgrade-lock [forge f]
  (let [file (lock-file forge)]
    (fs/create-dirs (fs/parent file))
    (with-open [channel (FileChannel/open
                         file
                         (into-array
                          [StandardOpenOption/CREATE
                           StandardOpenOption/WRITE]))]
      (let [lock (try
                   (.tryLock channel)
                   (catch Exception _ nil))]
        (if-not lock
          (f nil)
          (f lock))))))

(defn operation-id []
  (str "runtime-upgrade-" (random-uuid)))

(defn write-operation! [forge id result]
  (let [file (fs/path (upgrade-dir forge) "operations" (str id ".edn"))]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (pr-str (assoc result
                         :operation_id id
                         :updated_at (str (java.time.Instant/now)))))
    file))

(defn run-command! [command & args]
  (apply process/sh {:continue true} command args))

(defn wrapper [forge]
  (str (fs/path forge "swarmforge" "scripts" "swarmforge.sh")))

(defn close-project! [forge name]
  (let [result (run-command! (wrapper forge)
                             "--close-project" (str forge) name)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Project close failed" {:kind :close-project-failed
                                              :project name})))))

(defn open-project! [forge name]
  (let [result (run-command! (wrapper forge)
                             "--open-project" (str forge) name)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Project open failed" {:kind :open-project-failed
                                             :project name})))))

(defn stop-host! [forge]
  (let [result (run-command! (wrapper forge)
                             "--stop-forge-runtime" (str forge))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Forge runtime stop failed"
                      {:kind :stop-forge-failed})))))

(defn host-healthy? [forge]
  (zero? (:exit (run-command! (wrapper forge)
                              "--health" (str forge)))))

(defn start-host! [forge]
  (let [result (run-command! (wrapper forge) (str forge))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Forge runtime start failed"
                      {:kind :start-forge-failed})))))

(defn project-healthy? [forge name]
  (zero? (:exit (run-command! (wrapper forge)
                              "--health-project" (str forge) name))))

(defn upgrade-project! [forge source name]
  (let [helper (fs/path source "get-agent-project")
        result (run-command! (str helper) "--forge" (str forge)
                             "--upgrade" name)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Project template upgrade failed"
                      {:kind :project-upgrade-failed
                       :project name})))))

(defn reopen! [forge names start-host?]
  (when start-host?
    (start-host! forge))
  (doseq [name names]
    (open-project! forge name)))

(defn recover-open! [forge names start-host?]
  (when (and start-host? (not (host-healthy? forge)))
    (start-host! forge))
  (let [present (set (read-open-projects forge))]
    (doseq [name names
            :when (not (contains? present name))]
      (open-project! forge name))))

(defn execute-upgrade!
  [forge source commit id before phase record! recovery-context]
  (let [names (read-open-projects forge)
        host-up? (host-healthy? forge)]
    (reset! recovery-context {:names names :host-up? host-up?})

    (reset! phase :stop)
    (record! {:operation_id id :status "running" :phase "stop"
              :installed (:installed before)
              :available (:available before)})
    (doseq [name names]
      (close-project! forge name))
    (when host-up?
      (stop-host! forge))

    (when (= "behind" (:status before))
      (reset! phase :compose)
      (record! {:operation_id id :status "running" :phase "compose"
                :installed (:installed before)
                :available (:available before)})
      (compose! source forge (:product (:installed before)) commit false)
      (write-manifest! forge source (:product (:installed before)) commit)
      (let [after (read-manifest forge)
            digest (get-in after [:manifest :digest])]
        (when-not (= digest (get-in before [:available :digest]))
          (throw (ex-info "Composed digest did not match the checked target"
                          {:kind :compose-digest-mismatch})))))

    (reset! phase :project-upgrades)
    (record! {:operation_id id :status "running" :phase "project-upgrades"
              :installed (:installed before)
              :available (:available before)})
    (doseq [name (:project_upgrades_required before)]
      (upgrade-project! forge source name))

    (reset! phase :reopen)
    (record! {:operation_id id :status "running" :phase "reopen"
              :installed (:installed before)
              :available (:available before)})
    (reopen! forge names host-up?)

    (reset! phase :verify)
    (let [installed (read-manifest forge)
          digest (get-in installed [:manifest :digest])
          expected (get-in before [:available :digest])]
      (when-not (= digest expected)
        (throw (ex-info "Installed digest did not match the checked target"
                        {:kind :installed-digest-mismatch})))
      (when (and host-up? (not (host-healthy? forge)))
        (throw (ex-info "Forge health verification failed"
                        {:kind :host-health-failed})))
      (doseq [name names]
        (when-not (project-healthy? forge name)
          (throw (ex-info "Project health verification failed"
                          {:kind :project-health-failed
                           :project name}))))
      (let [result {:operation_id id
                    :status "current"
                    :phase "complete"
                    :completed true
                    :installed {:digest expected
                                :source_commit commit
                                :product (:product (:installed before))}
                    :available (:available before)
                    :reopened (vec names)
                    :project_upgrades_required
                    (:project_upgrades_required before)}]
        (record! result)
        result))))

(defn apply-operation! [forge source commit id]
  (let [phase (atom :preflight)
        recovery-context (atom nil)
        operation (atom {:operation_id id
                         :status "running"
                         :phase "preflight"
                         :updated_at (str (java.time.Instant/now))})
        record! (fn [value]
                  (reset! operation value)
                  (write-operation! forge id value))]
    (try
      (let [before (status-core forge source commit)]
        (when-not (#{"behind" "current"} (:status before))
          (record! (assoc before :phase "preflight" :completed false))
          (throw (ex-info "Upgrade preflight failed"
                          {:kind :preflight-failed
                           :exit 3
                           :result before})))
        (when-not (:idle before)
          (record! (assoc before
                          :status "busy"
                          :phase "preflight"
                          :completed false))
          (throw (ex-info "Upgrade requires every open project to be idle"
                          {:kind :busy
                           :exit 3
                           :result before})))
        (if (and (= "current" (:status before))
                 (empty? (:project_upgrades_required before)))
          (let [result {:operation_id id
                        :status "current"
                        :phase "complete"
                        :completed true
                        :installed (:installed before)
                        :available (:available before)
                        :reopened []
                        :project_upgrades_required []}]
            (record! result)
            result)
          (execute-upgrade! forge source commit id before
                            phase record! recovery-context)))
      (catch Exception error
        (let [data (ex-data error)
              code (if (or (= 3 (:exit data))
                           (= :preflight @phase))
                     3
                     4)
              recovery (when (and @recovery-context
                                  (contains? #{:stop :compose :project-upgrades}
                                             @phase))
                         (try
                           (recover-open! forge
                                          (:names @recovery-context)
                                          (:host-up? @recovery-context))
                           "complete"
                           (catch Exception _ "failed")))
              result (or (:result data)
                         {:operation_id id
                          :status "failed"
                          :phase (name @phase)
                          :completed false
                          :reason (or (:kind data) :upgrade-failed)})]
          (record! (cond-> result recovery (assoc :recovery recovery)))
          {:result (cond-> (assoc result
                                  :status (if (= code 3)
                                            "preflight-failed"
                                            "failed")
                                  :phase (name @phase)
                                  :completed false)
                     recovery (assoc :recovery recovery))
           :exit code})))))

(defn public-status [status]
  (update status :status identity))

(defn status-shell [status reason]
  {:status status
   :reason reason
   :installed nil
   :available nil
   :idle false
   :open_count 0
   :blockers []
   :blockers_truncated false
   :changes {:count 0 :items [] :truncated false}
   :project_upgrades_required []
   :project_upgrade_lines []})

(defn status-exit [status]
  (if (contains? #{"current" "behind" "checking"} (:status status)) 0 2))

(defn status-command! [forge source explicit-commit]
  (with-upgrade-lock
    forge
    (fn [lock]
      (if-not lock
        {:result (status-shell
                  "checking"
                  "another runtime upgrade operation holds the forge lock")
         :exit 0}
        (try
          {:result (public-status
                    (status-core forge source explicit-commit))
           :exit nil}
          (catch Exception _
            {:result (status-shell
                      "unknown"
                      "runtime status could not be checked")
             :exit 2}))))))

(defn apply-command! [forge source explicit-commit]
  (with-upgrade-lock
    forge
    (fn [lock]
      (if-not lock
        {:result (status-shell
                  "checking"
                  "another runtime upgrade operation holds the forge lock")
         :exit 3}
        (let [resolved (try
                         (source-commit source explicit-commit)
                         (catch Exception _ nil))]
          (if-not resolved
            {:result (status-shell
                      "unavailable"
                      "published source commit is unavailable")
             :exit 3}
            (let [applied (apply-operation! forge source resolved (operation-id))]
              (if (contains? applied :result)
                applied
                {:result applied :exit 0}))))))))

(defn manifest-command! [forge source product commit]
  (when-not (products product)
    (fail-usage! "Unknown product"))
  (when-not (and commit (re-matches source-commit-re commit))
    (fail-usage! "A canonical 40-character source commit is required"))
  (write-manifest! forge source product commit)
  (println "runtime manifest written"))

(defn -main [& args]
  (let [mode (first args)
        forge (or (option args "--forge") (option args "--root"))
        source (option args "--source-layer")
        explicit-commit (option args "--source-commit")]
    (case mode
      "status"
      (do
        (when-not (and forge source)
          (fail-usage! "status needs --forge and --source-layer"))
        (let [{:keys [result exit]} (status-command! forge source explicit-commit)]
          (println (json/generate-string result {:pretty false}))
          (System/exit (or exit (status-exit result)))))

      "apply"
      (do
        (when-not (and forge source)
          (fail-usage! "apply needs --forge and --source-layer"))
        (let [{:keys [result exit]} (apply-command! forge source explicit-commit)]
          (println (json/generate-string result {:pretty false}))
          (System/exit (or exit 0))))

      "manifest"
      (manifest-command! forge source (option args "--product") explicit-commit)

      (do
        (print usage-text)
        (flush)
        (System/exit 64)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
