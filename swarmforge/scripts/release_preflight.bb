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
       "  --no-maven        skip what asks outside this directory: the resolved-dependency\n"
       "                    check and the lookup of the newest release plugin\n"
       "\n"
       "It checks what a release must not start without: a clean tree, a version that is\n"
       "still a -SNAPSHOT at HEAD, a release tag nobody has taken, no dependency on a\n"
       "-SNAPSHOT this project does not build itself, the with-release-tests profile the\n"
       "release activates, and the wiring the release runs through - a <scm> block, which\n"
       "mvn release:prepare needs before it does anything, and, when swarmforge/deploy.conf\n"
       "names a target, a <distributionManagement> repository and the project's own token\n"
       "helper. The snapshot check is the one this exists for - mvn versions:set rewrites\n"
       "this project's own version and looks at nothing else, so a release can ship\n"
       "depending on a snapshot of a published producer without a word, which has happened\n"
       "here before.\n"
       "\n"
       "It changes nothing. When it passes it prints the commands to run, with the versions\n"
       "the card named - this never derives them - and the gate the release activates goes\n"
       "through -Darguments, because the plugin's own clean verify is a forked Maven that\n"
       "does not inherit a -P from this command line.\n"
       "\n"
       "It refuses nothing over the release plugin, and warns instead: a pom that pins no\n"
       "version still runs - Maven's own super-POM supplies one - so the release runs\n"
       "whatever version that Maven carries rather than one the project chose, and the\n"
        "warning names the newest Maven Central has, which is what to pin.\n"
        "\n"
       "The perform command it prints carries -DlocalCheckout=true: a release passes\n"
       "-DpushChanges=false, so the tag prepare made exists only in this checkout, and\n"
       "release:perform would otherwise clone the SCM URL looking for a tag the remote has\n"
       "not got yet.\n"))

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

(defn pom-text
  "The project's own pom, as text: the wiring checks below are about what a release
  runs through, and the root pom is where a project declares that."
  [root]
  (let [pom (fs/path root "pom.xml")]
    (when (fs/regular-file? pom) (slurp (str pom)))))

(defn scm-connection
  "The pom's SCM connection, when it has one. `mvn release:prepare` - the command
  this preflight prints, and the one a Java release is cut with - refuses before it
  does anything when there is none, because a release tags what the SCM block
  points at. `-DpushChanges=false` does not lift the requirement."
  [text]
  (when-let [block (second (re-find #"(?s)<scm>(.*?)</scm>" (or text "")))]
    (not-empty (str/trim (or (second (re-find #"(?s)<developerConnection>\s*([^<\s]+)\s*</developerConnection>" block))
                             (second (re-find #"(?s)<connection>\s*([^<\s]+)\s*</connection>" block))
                             "")))))

(defn distribution-repositories
  "The repositories the pom's distributionManagement names: what release:perform
  deploys through. Read inside that block, so a consuming <repositories> entry is
  never mistaken for a place this project publishes."
  [text]
  (let [block (second (re-find #"(?s)<distributionManagement>(.*?)</distributionManagement>" (or text "")))]
    (->> (re-seq #"(?s)<repository>(.*?)</repository>" (or block ""))
         (map (fn [[_ repository]]
                (let [field (fn [name]
                              (some-> (re-find (re-pattern (str "(?s)<" name ">\\s*([^<\\s]+)\\s*</" name ">")) repository)
                                      second
                                      str/trim
                                      not-empty))]
                  {:id (field "id") :url (field "url")})))
         (filter (fn [{:keys [id url]}] (and id url)))
         vec)))

(defn token-helper
  "The project's own token helper for a deploy target, when it has one. A project's
  manual puts it under scripts/, named for the target; a forge's own helpers
  directory counts too, because a project that has moved it there is still wired."
  [root target]
  (let [name (str target "-token.sh")]
    (some (fn [dir]
            (let [file (fs/path root dir name)]
              (when (fs/regular-file? file) (str dir "/" name))))
          ["scripts" "swarmforge/local-scripts"])))

(defn profile-ids
  "The profile ids a pom declares. A release activates the gate by name, and
  `-P` for a profile that does not exist is a warning to Maven rather than an
  error - the build runs, with no gate at all, which is the silent case this
  check exists for."
  [text]
  (->> (re-seq #"(?s)<profile>(.*?)</profile>" (or text ""))
       (keep (fn [[_ block]] (some-> (re-find #"(?s)<id>\s*([^<\s]+)\s*</id>" block) second str/trim)))
       set))

(def release-gate "with-release-tests")

(defn declared-plugin-version
  "The version a pom declares for the release plugin, when it declares one. A pom
  that names none still runs: Maven's own super-POM supplies a version, so the
  release runs whatever that Maven installation carries rather than a version the
  project chose."
  [text]
  (->> (re-seq #"(?s)<plugin>(.*?)</plugin>" (or text ""))
       (filter (fn [[_ block]] (str/includes? block "maven-release-plugin")))
       (keep (fn [[_ block]]
               (some-> (re-find #"(?s)<version>\s*([^<\s]+)\s*</version>" block) second str/trim not-empty)))
       first))

(defn newest-release-plugin
  "The newest release plugin Maven Central carries, or nil when it cannot be asked.
  Public metadata and no token; this is the number to pin, and a release that runs
  offline is simply not told it."
  []
  (try
    (let [{:keys [out exit]} (process/sh {:continue true} "curl" "-sS" "--max-time" "5"
                                          "https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-release-plugin/maven-metadata.xml")]
      (when (zero? exit)
        (not-empty (some-> (re-find #"(?s)<release>\s*([^<\s]+)\s*</release>" (or out "")) second str/trim))))
    (catch Exception _ nil)))

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
    (let [target (deploy-target root)
          text (pom-text root)
          scm (scm-connection text)
          dist (distribution-repositories text)
          helper (when target (token-helper root target))
          pinned (declared-plugin-version text)
          newest (when (and (not pinned) (not (some #{"--no-maven"} args))) (newest-release-plugin))
          warnings (cond-> []
                     (not pinned)
                     (conj (str "the release plugin is not pinned, so mvn release:prepare runs whatever "
                                "version Maven's own super-POM carries rather than one this project chose"
                                (when newest (str "; Maven Central's newest is " newest
                                                  ", which is what to pin in <build><pluginManagement>")))))
          failures (cond-> []
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
                     (conj (str "tag v" release-version " already exists"))
                     (not scm)
                     (conj (str "the pom has no <scm> block with a connection or a developerConnection, and "
                                "mvn release:prepare - the command this preflight prints - stops before it "
                                "does anything without one: \"Missing required setting: scm connection or "
                                "developerConnection must be specified\""))
                     (and target (empty? dist))
                     (conj (str "swarmforge/deploy.conf names " target " but the pom's <distributionManagement> "
                                "names no repository with an id and a url, so release:perform would have "
                                "nowhere to deploy"))
                     (and target (not helper))
                     (conj (str "swarmforge/deploy.conf names " target " but the project has no token helper "
                                "at scripts/" target "-token.sh (or swarmforge/local-scripts/"
                                target "-token.sh)"))
                     (not (contains? (profile-ids text) release-gate))
                     (conj (str "the pom declares no " release-gate " profile, which is the gate a release "
                                "activates; -P for a profile that does not exist is a warning to Maven and not "
                                "an error, so the release would run no gate and publish untested artifacts")))]
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
        (println (str "  release wiring: " (if scm
                                             (str "scm " scm)
                                             "NO <scm> BLOCK - mvn release:prepare would refuse")))
        (println (str "  deploy wiring: " (if (seq dist)
                                            (str/join ", " (map #(str (:id %) " -> " (:url %)) dist))
                                            (if target
                                              "NO <distributionManagement> REPOSITORY"
                                              "none - and no deploy target, so none is needed"))))
        (when target
          (println (str "  token helper: " (or helper
                                              (str "NOT FOUND - looked at scripts/" target "-token.sh")))))
        (println (str "  release gate: " (if (contains? (profile-ids text) release-gate)
                                           release-gate
                                           (str "NO " release-gate " PROFILE"))))
        (println (str "  release plugin: " (if pinned
                                             (str pinned " (pinned in the pom)")
                                             "UNPINNED - Maven's super-POM supplies the version")))
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
                (println (str "  - " failure)))
              (doseq [warning warnings]
                (println (str "  warning: " warning))))
            (System/exit 1))
          (do
            (println "")
            (println "release preflight: READY")
            (doseq [warning warnings]
              (println (str "  warning: " warning)))
            (println (str "  (the gate rides -Darguments: the plugin's clean verify forks and inherits no -P)"))
            (println (str "  (localCheckout: the tag is local while -DpushChanges=false, and perform clones the SCM)"))
            (println (str "  mvn release:prepare -DreleaseVersion=" release-version
                          (when next-version (str " -DdevelopmentVersion=" next-version))
                          " -Dtag=v" release-version " -DpushChanges=false"
                          " -Darguments=\"-P" release-gate "\""))
            (println (str "  mvn release:perform -DlocalCheckout=true -Darguments=\"-DskipTests\""
                          (when-not target
                            "   # only when swarmforge/deploy.conf names a target")))
            (println "  then push the branch and the tag, as the release steps say")
            (System/exit 0)))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
