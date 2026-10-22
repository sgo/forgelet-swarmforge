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

(defn -main [& args]
  (let [root (fs/absolutize (or (option args "--root") "."))
        template (fs/absolutize
                  (or (option args "--template")
                      (fail! "--template is required")))
        check-only? (flag? args "--check")
        stages-file (fs/path root "stages.edn")
        contract (fs/path root "docs" "manual-contract.edn")
        checker (fs/path root "swarmforge" "scripts" "check-documentation.bb")
        wrapper (fs/path root "scripts" "check-documentation.sh")
        template-contract (fs/path template "docs" "manual-contract.edn")
        template-wrapper (fs/path template "scripts" "check-documentation.sh")
        upgrade-source (fs/path (fs/parent (fs/absolutize *file*))
                                "check-documentation.bb")
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
        upgraded (when with-deploy (ensure-docs-stage with-deploy))
        pending (cond-> []
                  (and with-deploy (not= config with-deploy))
                  (conj "add required deploy stage")
                  (and upgraded (not= with-deploy upgraded))
                  (conj "add required docs stage")
                  install-publisher? (conj "install snapshot publisher")
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
        (doseq [item pending]
          (println (str "upgraded: " item)))
        (println "")
        (println "Next, review the upgraded project files. If the docs stage was")
        (println "added, complete its contract and documentation examples. Then run:")
        (println (str "  cd " root))
        (println "  export PATH=\"$PWD/.swarmforge/bin:$PWD/swarmforge/scripts:$PATH\"")
        (println "  bb swarmforge/scripts/stage.bb list --root .")))))

(apply -main *command-line-args*)
