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
       "## Spec corrections after the first approval\n\n"
       "The first spec proposal for each card requires the operator's approval. After\n"
       "that approval, corrections to the task note or Gherkin for the same unfinished\n"
       "card are approved automatically by the stage tool; do not create another\n"
       "approval request. Completing the card resets this policy for the next card.\n"
       policy-end))

(defn deploy-stage [command]
  {:name "deploy"
   :runs [[command]]
   :evidence []
   :stamp [:sources "scripts/publish-snapshot.sh"
           "scripts/github-packages-token.sh" "swarmforge/deploy.conf"]})

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

(defn configured-deploy-target [root]
  (let [file (fs/path root "swarmforge" "deploy.conf")]
    (when (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (map str/trim)
           (remove str/blank?)
           (remove #(str/starts-with? % "#"))
           first))))

(defn existing-publish-command [root]
  (some #(when (fs/regular-file? (fs/path root %)) %)
        ["scripts/publish-snapshot.sh"
         "swarmforge/local-scripts/publish-snapshot.sh"]))

(defn ensure-deploy-stage [config stage]
  (if-let [existing (some #(when (= "deploy" (:name %)) %) (:stages config))]
    (if (:optional existing)
      (update config :stages
              (fn [stages]
                (mapv #(if (= "deploy" (:name %)) (dissoc % :optional) %) stages)))
      config)
    (update config :stages insert-before stage #{"release"})))

(defn policy-in-place? [file]
  (and (fs/regular-file? file)
       (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote policy-begin))
                           (slurp (str file)))))
       (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote policy-end))
                           (slurp (str file)))))))

(defn install-policy! [file]
  (let [text (if (fs/regular-file? file)
               (slurp (str file))
               (str "# " (fs/file-name file) "\n"))
        managed (re-pattern
                 (str "(?s)"
                      (java.util.regex.Pattern/quote policy-begin)
                      ".*?"
                      (java.util.regex.Pattern/quote policy-end)))
        updated (if (re-find managed text)
                  (str/replace text managed policy-block)
                  (str text
                       (when-not (str/ends-with? text "\n") "\n")
                       "\n" policy-block "\n"))]
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
        deploy-target (configured-deploy-target root)
        publish-command (existing-publish-command root)
        install-publisher? (and deploy-target (nil? publish-command))
        effective-publish-command (or publish-command
                                      "scripts/publish-snapshot.sh")
        config (when (fs/regular-file? stages-file)
                 (try
                   (edn/read-string (slurp (str stages-file)))
                   (catch Exception cause
                     (fail! (str "cannot read " stages-file ": "
                                 (.getMessage cause))))))
        _ (when-not config
            (fail! (str "stages.edn is missing under " root)))
        with-deploy (when config
                      (cond-> config
                        deploy-target
                        (ensure-deploy-stage
                         (deploy-stage effective-publish-command))))
        with-e2e (when with-deploy (ensure-e2e-stage with-deploy))
        upgraded (when with-e2e (ensure-docs-stage with-e2e))
        standard? (some #(= "qa" (:name %)) (:stages config))
        pending (cond-> []
                  (and with-deploy (not= config with-deploy))
                  (conj "add required deploy stage")
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
                  install-publisher? (conj "install snapshot publisher")
                  (some #(not (policy-in-place? %)) policy-files)
                  (conj "install single-agent correction policy")
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
        (when install-publisher?
          (doseq [file ["publish-snapshot.sh" "github-packages-token.sh"]]
            (let [source (fs/path template "scripts" file)
                  destination (fs/path root "scripts" file)]
              (fs/create-dirs (fs/parent destination))
              (fs/copy source destination {:replace-existing true})
              (fs/set-posix-file-permissions destination "rwxr-xr-x"))))
        (doseq [file policy-files]
          (when-not (policy-in-place? file)
            (install-policy! file)))
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
