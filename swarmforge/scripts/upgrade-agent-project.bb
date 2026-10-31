#!/usr/bin/env bb

(ns upgrade-agent-project
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def docs-stage
  {:name "docs"
   :runs [["scripts/check-documentation.sh"]]
   :evidence ["target/documentation/check.log"]
   :stamp [:features :sources "README.md" "docs" "ARCHITECTURE.md"
           "scripts/check-documentation.sh"
           "swarmforge/scripts/check-documentation.bb"]})

(def e2e-stage
  {:name "e2e"
   :runs [["scripts/run-qa-e2e.sh"]]
   :evidence ["target/e2e/check.log" "qa/e2e.edn" "qa/*.md"
              "scripts/run-qa-*.sh" "scripts/run-qa-e2e.sh"]
   :stamp [:features :sources "qa" "scripts/run-qa-e2e.sh"
           "swarmforge/scripts/check-e2e.bb"]})

(def policy-begin "<!-- forgelet:single-agent-correction-policy:begin -->")
(def policy-end "<!-- forgelet:single-agent-correction-policy:end -->")
(def policy-block
  (str policy-begin "\n"
       "## Spec approval\n\n"
       "A single-agent project approves its spec automatically by default and proceeds\n"
       "to implementation without an approval request. To restore explicit first-spec\n"
       "approval for every single-agent project in a forge, create\n"
       "`<forge>/swarmforge/local-spec-approval.conf` containing `spec=operator`.\n"
       "In that explicit mode, corrections to the same unfinished card's task note or\n"
       "Gherkin remain automatically approved after the first operator approval.\n"
       policy-end))

(def commit-policy-begin "<!-- forgelet:single-agent-commit-policy:begin -->")
(def commit-policy-end "<!-- forgelet:single-agent-commit-policy:end -->")
(def commit-policy-block
  (str commit-policy-begin "\n"
       "## Commit working checkpoints\n\n"
       "After the current spec is approved, commit the approved spec before\n"
       "implementation and commit coherent working checkpoints as they are completed.\n"
       "Do not wait for every gate or for deployment to make your first commit, and do\n"
       "not defer the whole card to one final pre-deploy commit. Keep each checkpoint\n"
       "reviewable and free of unrelated or generated files.\n\n"
       "The pre-commit hook checks only that the current spec bundle is approved. Stage\n"
       "evidence is tied to the Git tree, so committing unchanged contents does not\n"
       "invalidate a stage that has passed. Before deployment, commit and push the final\n"
       "reviewed tree.\n"
       commit-policy-end))

(def install-stage
  {:name "deploy"
   :runs [["scripts/install-snapshot.sh"]]
   :evidence []
   :stamp [:sources "scripts/install-snapshot.sh" "pom.xml" ".mvn"]})

(defn fail! [message]
  (binding [*out* *err*]
    (println (str "get-agent-project: " message)))
  (System/exit 1))

(defn option [args name]
  (loop [remaining args]
    (cond
      (empty? remaining) nil
      (= (first remaining) name) (or (second remaining)
                                    (fail! (str name " needs a value")))
      :else (recur (nnext remaining)))))

(defn flag? [args name]
  (boolean (some #(= name %) args)))

(defn write-stages! [file config]
  (with-open [writer (java.io.StringWriter.)]
    (binding [*out* writer
              pprint/*print-right-margin* 100]
      (pprint/pprint config))
    (spit (str file) (str writer))))

(defn insert-before [items item names]
  (let [index (first
               (keep-indexed (fn [index value]
                               (when (contains? names (:name value)) index))
                             items))]
    (if (some? index)
      (vec (concat (take index items) [item] (drop index items)))
      (conj (vec items) item))))

(defn ensure-docs-stage [config]
  (if-let [existing (some #(when (= "docs" (:name %)) %) (:stages config))]
    (if (:optional existing)
      (update config :stages
              (fn [stages]
                (mapv #(if (= "docs" (:name %)) (dissoc % :optional) %) stages)))
      config)
    (update config :stages insert-before docs-stage #{"deploy" "release"})))

(defn ensure-e2e-stage [config]
  (if-let [existing (some #(when (= "e2e" (:name %)) %) (:stages config))]
    (if (:optional existing)
      (update config :stages
              (fn [stages]
                (mapv #(if (= "e2e" (:name %)) (dissoc % :optional) %) stages)))
      config)
    (if (some #(= "qa" (:name %)) (:stages config))
      (update config :stages insert-before e2e-stage #{"docs" "deploy" "release"})
      config)))

(defn configured-release-target [root]
  (some (fn [name]
          (let [file (fs/path root "swarmforge" name)]
            (when (fs/regular-file? file)
              (->> (str/split-lines (slurp (str file)))
                   (map str/trim)
                   (remove str/blank?)
                   (remove #(str/starts-with? % "#"))
                   first))))
        ["release.conf" "deploy.conf"]))

(defn ensure-deploy-stage [config]
  (if-let [existing (some #(when (= "deploy" (:name %)) %) (:stages config))]
    (update config :stages
            (fn [stages]
              (mapv #(if (= "deploy" (:name %))
                       (merge (dissoc % :optional) install-stage)
                       %)
                    stages)))
    (update config :stages insert-before install-stage #{"release"})))

(defn excluded-maven-config? [root file]
  (let [parts (set (map str (fs/components (fs/relativize root file))))]
    (boolean (some parts ["target" "tmp" ".worktrees" ".git" ".swarmforge"]))))

(defn maven-config-files [root]
  (->> (cons (fs/path root ".mvn" "maven.config")
             (fs/glob root "**/.mvn/maven.config" {:hidden true}))
       distinct
       (remove #(excluded-maven-config? root %))
       (filter fs/regular-file?)
       vec))

(defn local-repo-line? [line]
  (str/starts-with? (str/trim line) "-Dmaven.repo.local="))

(defn local-repo-config? [file]
  (some local-repo-line? (str/split-lines (slurp (str file)))))

(defn remove-local-repo! [file]
  (let [kept (->> (str/split-lines (slurp (str file)))
                  (remove local-repo-line?))]
    (spit (str file)
          (if (seq kept) (str (str/join "\n" kept) "\n") ""))))

(defn managed-block-in-place? [file begin end block]
  (when (fs/regular-file? file)
    (let [text (slurp (str file))
          managed (re-pattern
                   (str "(?s)"
                        (java.util.regex.Pattern/quote begin)
                        ".*?"
                        (java.util.regex.Pattern/quote end)))]
      (and (= 1 (count (re-seq (re-pattern
                               (java.util.regex.Pattern/quote begin)) text)))
           (= 1 (count (re-seq (re-pattern
                               (java.util.regex.Pattern/quote end)) text)))
           (= block (re-find managed text))))))

(defn install-managed-block! [file begin end block]
  (let [text (if (fs/regular-file? file)
               (slurp (str file))
               (str "# " (fs/file-name file) "\n"))
        managed (re-pattern
                 (str "(?s)"
                      (java.util.regex.Pattern/quote begin)
                      ".*?"
                      (java.util.regex.Pattern/quote end)))
        updated (if (re-find managed text)
                  (str/replace text managed block)
                  (str text
                       (when-not (str/ends-with? text "\n") "\n")
                       "\n" block "\n"))]
    (fs/create-dirs (fs/parent file))
    (spit (str file) updated)))

(defn -main [& args]
  (let [root (fs/absolutize (or (option args "--root") "."))
        template (fs/absolutize
                  (or (option args "--template")
                      (fail! "--template is required")))
        check-only? (flag? args "--check")
        stages-file (fs/path root "stages.edn")
        contract (fs/path root "docs" "manual-contract.edn")
        checker (fs/path root "swarmforge" "scripts" "check-documentation.bb")
        e2e-checker (fs/path root "swarmforge" "scripts" "check-e2e.bb")
        e2e-contract (fs/path root "qa" "e2e.edn")
        e2e-wrapper (fs/path root "scripts" "run-qa-e2e.sh")
        wrapper (fs/path root "scripts" "check-documentation.sh")
        template-contract (fs/path template "docs" "manual-contract.edn")
        template-e2e-contract (fs/path template "qa" "e2e.edn")
        template-e2e-wrapper (fs/path template "scripts" "run-qa-e2e.sh")
        template-wrapper (fs/path template "scripts" "check-documentation.sh")
        policy-files [(fs/path root "AGENTS.md")
                      (fs/path root "STAGES.md")]
        upgrade-source (fs/path (fs/parent (fs/absolutize *file*))
                                "check-documentation.bb")
        e2e-upgrade-source (fs/path (fs/parent (fs/absolutize *file*))
                                    "check-e2e.bb")
        maven? (or (fs/regular-file? (fs/path root "pom.xml"))
                   (fs/regular-file? (fs/path root ".mvn" "maven.config")))
        release-target (configured-release-target root)
        release-file (fs/path root "swarmforge" "release.conf")
        legacy-deploy-file (fs/path root "swarmforge" "deploy.conf")
        rename-release-file? (and (fs/regular-file? legacy-deploy-file)
                                  (not (fs/regular-file? release-file)))
        install-script (fs/path root "scripts" "install-snapshot.sh")
        token-helper (fs/path root "scripts" "github-packages-token.sh")
        template-install-script (fs/path template "scripts" "install-snapshot.sh")
        template-token-helper (fs/path template "scripts" "github-packages-token.sh")
        repo-configs (when maven? (maven-config-files root))
        config (when (fs/regular-file? stages-file)
                 (try
                   (edn/read-string (slurp (str stages-file)))
                   (catch Exception cause
                     (fail! (str "cannot read " stages-file ": "
                                 (.getMessage cause))))))
        _ (when-not config
            (fail! (str "stages.edn is missing under " root)))
        with-deploy (if (and config maven?)
                      (ensure-deploy-stage config)
                      config)
        with-e2e (when with-deploy (ensure-e2e-stage with-deploy))
        upgraded (when with-e2e (ensure-docs-stage with-e2e))
        standard? (some #(= "qa" (:name %)) (:stages config))
        pending (cond-> []
                  (and maven? (not= config with-deploy))
                  (conj "install local-snapshot deploy stage")
                  (and upgraded (not= with-e2e upgraded))
                  (conj "add required docs stage")
                  (and standard? (not= with-deploy with-e2e))
                  (conj "add required e2e stage")
                  (and standard? (not (fs/regular-file? e2e-contract)))
                  (conj "create qa/e2e.edn")
                  (and standard?
                       (or (not (fs/regular-file? e2e-wrapper))
                           (not= (slurp (str e2e-wrapper))
                                 (slurp (str template-e2e-wrapper)))))
                  (conj "install run-qa-e2e.sh")
                  (and standard?
                       (or (not (fs/regular-file? e2e-checker))
                           (not= (slurp (str e2e-checker))
                                 (slurp (str e2e-upgrade-source)))))
                  (conj "install check-e2e.bb")
                  (and maven?
                       (or (not (fs/regular-file? install-script))
                           (not= (slurp (str install-script))
                                 (slurp (str template-install-script)))))
                  (conj "install install-snapshot.sh")
                  (and release-target
                       (or (not (fs/regular-file? token-helper))
                           (not= (slurp (str token-helper))
                                 (slurp (str template-token-helper)))))
                  (conj "install release token helper")
                  rename-release-file? (conj "rename deploy.conf to release.conf")
                  (some local-repo-config? repo-configs)
                  (conj "use the shared Maven repository")
                  (some #(not (managed-block-in-place?
                               % policy-begin policy-end policy-block)) policy-files)
                  (conj "install single-agent correction policy")
                  (some #(not (managed-block-in-place?
                               % commit-policy-begin commit-policy-end
                               commit-policy-block)) policy-files)
                  (conj "install single-agent commit policy")
                  (not (fs/regular-file? checker)) (conj "install check-documentation.bb")
                  (or (not (fs/regular-file? wrapper))
                      (not= (slurp (str wrapper)) (slurp (str template-wrapper))))
                  (conj "install check-documentation.sh")
                  (not (fs/regular-file? contract)) (conj "create docs/manual-contract.edn"))]
    (when (empty? pending)
      (println (str "get-agent-project: " root " has the current single-agent structure"))
      (System/exit 0))
    (if check-only?
      (do
        (doseq [item pending]
          (println (str "upgrade needed: " item)))
        (System/exit 1))
      (do
        (when (and config (not= config upgraded))
          (write-stages! stages-file upgraded))
        (when rename-release-file?
          (fs/move legacy-deploy-file release-file))
        (when (and maven?
                   (or (not (fs/regular-file? install-script))
                       (not= (slurp (str install-script))
                             (slurp (str template-install-script)))))
          (fs/create-dirs (fs/parent install-script))
          (fs/copy template-install-script install-script {:replace-existing true})
          (fs/set-posix-file-permissions install-script "rwxr-xr-x"))
        (when (and release-target
                   (or (not (fs/regular-file? token-helper))
                       (not= (slurp (str token-helper))
                             (slurp (str template-token-helper)))))
          (fs/create-dirs (fs/parent token-helper))
          (fs/copy template-token-helper token-helper {:replace-existing true})
          (fs/set-posix-file-permissions token-helper "rwxr-xr-x"))
        (doseq [file repo-configs
                :when (local-repo-config? file)]
          (remove-local-repo! file))
        (doseq [file policy-files]
          (when-not (managed-block-in-place?
                     file policy-begin policy-end policy-block)
            (install-managed-block! file policy-begin policy-end policy-block))
          (when-not (managed-block-in-place?
                     file commit-policy-begin commit-policy-end
                     commit-policy-block)
            (install-managed-block!
             file commit-policy-begin commit-policy-end commit-policy-block)))
        (when-not (= (when (fs/regular-file? checker) (slurp (str checker)))
                     (slurp (str upgrade-source)))
          (fs/create-dirs (fs/parent checker))
          (fs/copy upgrade-source checker {:replace-existing true}))
        (when-not (= (when (fs/regular-file? wrapper) (slurp (str wrapper)))
                     (slurp (str template-wrapper)))
          (fs/create-dirs (fs/parent wrapper))
          (fs/copy template-wrapper wrapper {:replace-existing true})
          (fs/set-posix-file-permissions wrapper "rwxr-xr-x"))
        (when-not (fs/regular-file? contract)
          (fs/create-dirs (fs/parent contract))
          (fs/copy template-contract contract))
        (when standard?
          (when-not (fs/regular-file? e2e-contract)
            (fs/create-dirs (fs/parent e2e-contract))
            (fs/copy template-e2e-contract e2e-contract))
          (when-not (= (when (fs/regular-file? e2e-wrapper)
                         (slurp (str e2e-wrapper)))
                       (slurp (str template-e2e-wrapper)))
            (fs/create-dirs (fs/parent e2e-wrapper))
            (fs/copy template-e2e-wrapper e2e-wrapper {:replace-existing true})
            (fs/set-posix-file-permissions e2e-wrapper "rwxr-xr-x"))
          (when-not (= (when (fs/regular-file? e2e-checker)
                         (slurp (str e2e-checker)))
                       (slurp (str e2e-upgrade-source)))
            (fs/create-dirs (fs/parent e2e-checker))
            (fs/copy e2e-upgrade-source e2e-checker {:replace-existing true})))
        (doseq [item pending]
          (println (str "upgraded: " item)))
        (println "")
        (println "Next, review the upgraded project files. If the docs stage was")
        (println "added, complete its contract and documentation examples. Then run:")
        (println (str "  cd " root))
        (println "  export PATH=\"$PWD/.swarmforge/bin:$PWD/swarmforge/scripts:$PATH\"")
        (println "  bb swarmforge/scripts/stage.bb list --root .")))))

(apply -main *command-line-args*)
