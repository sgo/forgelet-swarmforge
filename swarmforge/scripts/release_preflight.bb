#!/usr/bin/env bb

(ns release-preflight
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Check whether a Java/Maven project may be released, before it is.\n"
       "\n"
       "Usage:\n"
       "  release_preflight.sh [<release-version> [<next-development-version>]] [options]\n"
       "\n"
       "  --project <root>  the project to check (default: the current directory)\n"
       "  --no-maven        skip the resolved-dependency check, which asks Maven\n"
       "\n"
       "It checks what a release must not start without: a clean tree, a version that is\n"
       "still a -SNAPSHOT at HEAD, a release tag nobody has taken, and no dependency on a\n"
       "-SNAPSHOT this project does not build itself. The last one is the failure this\n"
       "exists for - mvn versions:set rewrites this project's own version and looks at\n"
       "nothing else, so a release can ship depending on a snapshot of a published\n"
       "producer without a word, which has happened here before.\n"
       "\n"
       "It changes nothing. When it passes it prints the commands to run, with the versions\n"
       "the card named - this never derives them.\n"))

(defn usage []
  (println usage-text))

(defn exit! [status message]
  (binding [*out* *err*]
    (println message))
  (System/exit status))

(defn flag-value [args flag]
  (second (drop-while #(not= flag %) args)))

(defn positional [args]
  (remove #(or (str/starts-with? % "--")
               (= % (flag-value args "--project")))
          args))

(defn project-root [args]
  (let [given (str (or (flag-value args "--project") (fs/absolutize ".")))]
    (when-not (fs/regular-file? (fs/path given "pom.xml"))
      (exit! 1 (str "release_preflight: no pom.xml in " given
                    " - this checks a Java/Maven project")))
    (fs/canonicalize given)))

(defn git [root & args]
  (let [result (apply process/sh (concat [{:continue true}] ["git" "-C" (str root)] args))]
    {:exit (:exit result) :out (str/trim (:out result))}))

(defn pom-version
  "The project's own version: the first <version> that is not its parent's."
  [pom]
  (let [text (str/replace (slurp (str pom)) #"(?s)<parent>.*?</parent>" "")
        [_ version] (re-find #"(?s)<version>\s*([^<\s]+)\s*</version>" text)]
    version))

(defn poms
  "Every pom of the reactor, excluding build output, worktrees and scratch. The check
  is on the path below this project, not on the absolute one: a project can live under
  /tmp and still be the project being released."
  [root]
  (->> (cons (fs/path root "pom.xml") (fs/glob root "**/pom.xml"))
       (remove (fn [pom]
                 (let [parts (map str (fs/components (fs/relativize root pom)))]
                   (boolean (some #{"target" ".worktrees" "tmp" ".git"} parts)))))
       distinct
       vec))

(defn artifact-ids [pom-files]
  (->> pom-files
       ;; The project's own artifact is the first <artifactId> once the parent's is out
       ;; of the way; every other one in the file belongs to a dependency or a plugin,
       ;; and counting those as ours is how a dependency on another project's snapshot
       ;; would slip through this check as "built here".
       (keep #(some-> (re-find #"(?s)<artifactId>\s*([^<\s]+)\s*</artifactId>"
                               (str/replace (slurp (str %)) #"(?s)<parent>.*?</parent>" ""))
                      second))
       set))

(defn snapshot-dependency-coordinates
  "Coordinates this project depends on at a -SNAPSHOT version, excluding the ones it
  builds itself - a module of the reactor is expected to be a snapshot before a release."
  [pom-files own-artifacts]
  (->> pom-files
       (mapcat (fn [pom]
                 (->> (re-seq #"(?s)<dependency>(.*?)</dependency>" (slurp (str pom)))
                      (map second))))
       (keep (fn [block]
               (let [group (some-> (re-find #"(?s)<groupId>\s*([^<\s]+)\s*</groupId>" block) second)
                     artifact (some-> (re-find #"(?s)<artifactId>\s*([^<\s]+)\s*</artifactId>" block) second)
                     version (some-> (re-find #"(?s)<version>\s*([^<\s]+)\s*</version>" block) second)]
                 (when (and group artifact version
                            (str/includes? version "SNAPSHOT")
                            (not (own-artifacts artifact)))
                   (str group ":" artifact ":" version)))))
       distinct
       sort
       vec))

(defn maven-resolved-snapshots
  "The same question asked of Maven, which also sees versions held in properties. Returns
  {:checked true :snapshots [...]} or {:checked false :why \"...\"}."
  [root own-artifacts]
  (let [out (fs/create-temp-file {:prefix "release-preflight." :suffix ".txt"})]
    (try
      (let [result (process/sh {:continue true :dir (str root)}
                               "mvn" "-B" "-q" "dependency:list"
                               (str "-DoutputFile=" (str out)))]
        (if (zero? (:exit result))
          (let [text (str (:out result) "\n" (when (fs/exists? out) (slurp (str out))))
                coords (->> (re-seq #"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):[A-Za-z0-9_.-]+:([A-Za-z0-9_.-]+)" text)
                            (map (fn [[_ group artifact version]] [group artifact version]))
                            (filter (fn [[_ artifact version]]
                                      (and (str/includes? version "SNAPSHOT")
                                           (not (own-artifacts artifact)))))
                            (map (fn [[group artifact version]] (str group ":" artifact ":" version)))
                            distinct
                            sort
                            vec)]
            {:checked true :snapshots coords})
          {:checked false
           :why (str "mvn dependency:list did not run ("
                     (str/trim (last (str/split-lines (str (:err result)))))
                     ")")}))
      (catch Exception e
        {:checked false :why (str "mvn is not available here (" (.getMessage e) ")")})
      (finally
        (fs/delete-if-exists out)))))

(defn deploy-target [root]
  (let [file (fs/path root "swarmforge" "deploy.conf")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn -main [& args]
  (when (or (some #{"--help" "-h"} args) (empty? args))
    (when (empty? args) (usage) (System/exit 1))
    (usage)
    (System/exit 0))
  (let [root (project-root args)
        [release-version next-version] (positional args)
        pom (fs/path root "pom.xml")
        version (pom-version pom)
        status (git root "status" "--porcelain")
        dirty (->> (:out status) str/split-lines (remove str/blank?))]
    (println (str "release preflight: " root))
    (when-not (zero? (:exit status))
      (exit! 1 (str "  " root " is not a git repository (or git is missing); a release tags and\n"
                    "  pushes, so it has to be one")))
    (println (str "  working tree: " (if (empty? dirty)
                                       "clean"
                                       (str (count dirty) " uncommitted path(s): "
                                            (str/join ", " (take 5 dirty))))))
    (when-not version
      (exit! 1 "  the pom has no version of its own; this project's version is inherited"))
    (println (str "  version at HEAD: " version))
    (println (str "  deploy gate: " (or (deploy-target root)
                                        "no target in swarmforge/deploy.conf - this release will not deploy")))
    (let [failures (cond-> []
                     (seq dirty)
                     (conj (str "the tree is not clean; commit or drop those changes first - a release "
                                "starts from what HEAD holds"))
                     (not (str/ends-with? version "-SNAPSHOT"))
                     (conj (str "version " version " is not a -SNAPSHOT, so there is nothing to release "
                                "from; a release cuts the snapshot into its release version"))
                     (and release-version
                          (not= release-version (str/replace version #"-SNAPSHOT$" "")))
                     (conj (str "the card names release " release-version " but this project is at " version
                                "; the two must be the same version"))
                     (and next-version (not (str/ends-with? next-version "-SNAPSHOT")))
                     (conj (str "the next development version " next-version
                                " does not end in -SNAPSHOT, so development would resume on a release version"))
                     (and next-version (= next-version version))
                     (conj (str "the next development version is the version being released"))
                     (and release-version
                          (zero? (:exit (git root "rev-parse" "-q" "--verify"
                                             (str "refs/tags/v" release-version)))))
                     (conj (str "tag v" release-version " already exists")))]
      (let [release-version (or release-version (str/replace version #"-SNAPSHOT$" ""))
            own (artifact-ids (poms root))
            from-poms (snapshot-dependency-coordinates (poms root) own)
            resolved (when-not (some #{"--no-maven"} args)
                       (maven-resolved-snapshots root own))
            from-maven (if (and resolved (:checked resolved)) (:snapshots resolved) [])
            silent-snapshots (->> (concat from-poms from-maven) distinct sort vec)
            failures (into failures
                           (map #(str "dependency on a SNAPSHOT this project does not build: " %)
                                silent-snapshots))]
        (when (seq from-poms)
          (println (str "  snapshot dependencies (from the poms): " (str/join ", " from-poms))))
        (when (and resolved (:checked resolved) (seq from-maven))
          (println (str "  snapshot dependencies (resolved by Maven): " (str/join ", " from-maven))))
        (when (and resolved (not (:checked resolved)))
          (println (str "  snapshot dependencies: NOT CHECKED - " (:why resolved))))
        (when (and resolved (:checked resolved) (empty? from-maven) (empty? from-poms))
          (println "  snapshot dependencies: none"))
        (if (seq failures)
          (do
            (binding [*out* *err*]
              (println "")
              (println "release preflight: DO NOT RELEASE")
              (doseq [failure failures]
                (println (str "  - " failure))))
            (System/exit 1))
          (do
            (println "")
            (println "release preflight: READY")
            (println (str "  mvn release:prepare -DreleaseVersion=" release-version
                          (when next-version (str " -DdevelopmentVersion=" next-version))
                          " -Dtag=v" release-version " -DpushChanges=false"))
            (println (str "  mvn release:perform"
                          (when-not (deploy-target root)
                            "   # only when swarmforge/deploy.conf names a target")))
            (println "  then push the branch and the tag, as the release steps say")
            (System/exit 0)))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
