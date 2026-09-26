#!/usr/bin/env bb

(ns swarm-tool
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def catalog
  {"gherkin-parser" {:source "github.com/unclebob/Acceptance-Pipeline-Specification"
                     :bb-task "gherkin-parser"}
   "ir-dry-checker" {:source "github.com/unclebob/Acceptance-Pipeline-Specification"
                     :bb-task "gherkin-ir-dry-checker"}
   "gherkin-mutator" {:source "github.com/unclebob/Acceptance-Pipeline-Specification"
                      :bb-task "gherkin-mutator"}
   "crap4clj" {:source "github.com/unclebob/crap4clj" :bb-task "crap4clj"
               :needs ["cloverage"]}
   "dry4clj" {:source "github.com/unclebob/dry4clj" :bb-task "dry4clj"}
   "clj-mutate" {:source "github.com/unclebob/clj-mutate" :bb-task "clj-mutate"
                 :needs ["cloverage"]}
   "cloverage" {:mvn "cloverage/cloverage" :main "cloverage.coverage"
                :paths ["src" "spec" "test"]
                :extra-deps {"speclj/speclj" "3.13.0"}
                :args ["-p" "src" "-s" "spec" "-s" "test" "-r" "speclj"]}
   "speclj" {:mvn "speclj/speclj" :main "speclj.main" :version "3.13.0"
             :paths ["src" "spec" "test"]
             :args ["-c" "spec"]}
   "speclj-structure-check" {:source "github.com/unclebob/speclj-structure-check"
                             :bb-task "check"}
   "crap4go" {:source "github.com/unclebob/crap4go"
              :go-package "github.com/unclebob/crap4go/cmd/crap4go"}
   "dry4go" {:source "github.com/unclebob/dry4go"
             :go-package "github.com/unclebob/dry4go/cmd/dry4go"}
   "mutate4go" {:source "github.com/unclebob/mutate4go"
                :go-package "github.com/unclebob/mutate4go/cmd/mutate4go"}
   "crap4java" {:source "github.com/unclebob/crap4java" :bb-task "crap4java"}
   "dry4java" {:source "github.com/unclebob/dry4java" :bb-task "dry4java"}
   "mutate4java" {:source "github.com/unclebob/mutate4java" :bb-task "mutate4java"}
   ;; forgelet: the Kotlin recipe is ours. The registry says how each language's tools are
   ;; installed, and upstream's names Go, Clojure and Java; a Kotlin project needs
   ;; slopguard built from source, and the commit is pinned because it has no release tags
   ;; yet - one line to change at a tag. The Go entries above went upstream as the bug
   ;; they were: a Go tool cannot be installed as a Babashka task. After a refresh from
   ;; upstream, compare this registry against the languages our projects are written in
   ;; rather than assuming the merge kept them.
   "slopguard" {:source "github.com/JeevanThandi/slopguard-kotlin"
                :gradle-install "app:installDist"
                :commit "40204ef8f382ed02ca5b143a55dfab1671840383"}})

(def usage-text
  (str "Usage:\n"
       "  swarm_tool.sh require <tool>\n"
       "  swarm_tool.sh ensure <tool>\n\n"
       "Tools: " (str/join ", " (sort (keys catalog)))))

(defn usage []
  (binding [*out* *err*]
    (println usage-text)))

(defn exit! [status message]
  (binding [*out* *err*]
    (when message
      (println message)))
  (System/exit status))

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn roles-at? [root]
  (and root (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))))

(defn git-common-dir []
  (let [git (sh/sh "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit git))
      (let [path (fs/path (str/trim (:out git)))]
        (str (if (fs/absolute? path) path (fs/absolutize path)))))))

(defn project-root []
  (or (let [parent (some-> (git-common-dir) fs/parent str)]
        (when (roles-at? parent) parent))
      (let [git (sh/sh "git" "rev-parse" "--show-toplevel")
            root (when (zero? (:exit git)) (str/trim (:out git)))]
        (when (roles-at? root) root))
      (when (roles-at? (fs/cwd)) (fs/cwd))
      (exit! 1 "Cannot find SwarmForge project root")))

(defn canonical-tool [tool]
  (str/lower-case (or tool "")))

(defn tool-spec [tool]
  (or (get catalog (canonical-tool tool))
      (exit! 1 (str "Unknown tool: " tool "\n\n" usage-text))))

(defn bin-dir [root]
  (fs/path root ".swarmforge" "bin"))

(defn wrapper-path [root tool]
  (fs/path (bin-dir root) (canonical-tool tool)))

(defn source-dir [root source]
  (if-let [override (not-empty (System/getenv "SWARMFORGE_TOOL_SRC"))]
    (fs/path override)
    (fs/path root ".swarmforge" "tools" (last (str/split source #"/")))))

(defn go-bin-dir [root]
  (fs/path root ".swarmforge" "go-bin"))

(defn go-package-name [spec]
  (last (str/split (or (:go-package spec) "") #"/")))

(defn go-binary-path [root spec]
  (fs/path (go-bin-dir root) (go-package-name spec)))

;; A Go tool counts as installed only when its wrapper and the binary the
;; wrapper execs are both present; the binary lives in .swarmforge/go-bin so
;; `go install` never overwrites the wrapper in .swarmforge/bin.
(defn tool-installed? [root tool]
  (let [spec (get catalog (canonical-tool tool))]
    (and (fs/executable? (wrapper-path root tool))
         (or (nil? (:go-package spec))
             (fs/executable? (go-binary-path root spec))))))

(defn needed-tools [tool]
  (vec (or (:needs (tool-spec tool)) [])))

(defn missing-tool [root tool]
  (first (remove #(tool-installed? root %)
                 (cons tool (needed-tools tool)))))

(defn require-tool! [tool]
  (tool-spec tool)
  (let [root (project-root)
        missing (missing-tool root tool)]
    (if missing
      (exit! 1 (str "MISSING: " missing "\nRun: swarm_tool.sh ensure " missing))
      (do (println "OK:" tool (str (wrapper-path root tool)))
          (System/exit 0)))))

(defn clone-source! [dir source]
  (fs/create-dirs (fs/parent dir))
  (let [url (str "https://" source ".git")
        result (sh/sh "git" "clone" "--depth" "1" url (str dir))]
    (when-not (zero? (:exit result))
      (exit! 1 (str "Failed to clone " url "\n" (:err result) (:out result))))))

(defn ensure-source! [root source]
  (let [dir (source-dir root source)]
    (when-not (fs/exists? (fs/path dir "bb.edn"))
      (when (System/getenv "SWARMFORGE_TOOL_SRC")
        (exit! 1 (str "SWARMFORGE_TOOL_SRC is missing bb.edn: " dir)))
      (clone-source! dir source))
    dir))

(defn mutate-rewrite-bash []
  (str "args=()\n"
       "scan=\n"
       "while [ $# -gt 0 ]; do\n"
       "  case \"$1\" in\n"
       "    --mutate-all) shift ;;\n"
       "    --scan|--update-manifest) scan=1; args+=(\"$1\"); shift ;;\n"
       "    --max-workers) shift; [ $# -gt 0 ] && shift ;;\n"
       "    *) args+=(\"$1\"); shift ;;\n"
       "  esac\n"
       "done\n"
       "if [ -z \"$scan\" ]; then args+=(--max-workers 4); fi\n"
       "set -- \"${args[@]}\"\n"))

(defn gherkin-rewrite-bash []
  (str "args=()\n"
       "while [ $# -gt 0 ]; do\n"
       "  case \"$1\" in\n"
       "    --level)\n"
       "      if [ \"${2:-}\" = full ]; then args+=(--level hard); else args+=(\"$1\" \"$2\"); fi\n"
       "      shift; [ $# -gt 0 ] && shift ;;\n"
       "    --workers) shift; [ $# -gt 0 ] && shift ;;\n"
       "    *) args+=(\"$1\"); shift ;;\n"
       "  esac\n"
       "done\n"
       "args+=(--workers 4)\n"
       "set -- \"${args[@]}\"\n"))

(defn rewrite-bash [tool]
  (cond
    (#{"clj-mutate" "mutate4go" "mutate4java"} tool) (mutate-rewrite-bash)
    (= "gherkin-mutator" tool) (gherkin-rewrite-bash)
    :else ""))

(defn write-wrapper! [path body]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (str "#!/usr/bin/env bash\n" body))
  (fs/set-posix-file-permissions path "rwxr-xr-x")
  path)

(defn write-bb-wrapper! [root tool bb-task src-dir]
  (let [target (wrapper-path root tool)
        config (str (fs/path src-dir "bb.edn"))]
    (write-wrapper!
     target
     (str (rewrite-bash tool)
          "exec bb --config " (sq config) " " bb-task " \"$@\"\n"))))

(defn edn-paths [paths]
  (str/join " " (map pr-str (or paths []))))

(defn coord-dep [coord version]
  (str coord " {:mvn/version " (pr-str version) "}"))

(defn edn-deps [spec]
  (let [main (coord-dep (:mvn spec) (or (:version spec) "RELEASE"))
        extra (map (fn [[coord version]] (coord-dep coord version))
                   (or (:extra-deps spec) {}))]
    (str/join " " (cons main extra))))

(defn write-mvn-wrapper! [root tool spec]
  (let [target (wrapper-path root tool)
        deps (str "{:paths [" (edn-paths (:paths spec)) "] :deps {" (edn-deps spec) "}}")
        args (str/join " " (or (:args spec) []))]
    (write-wrapper!
     target
     (str (rewrite-bash tool)
          "exec clojure -Sdeps " (sq deps) " -M -m " (:main spec)
          (when (seq args) (str " " args))
          " \"$@\"\n"))))

(defn install-go-package! [root spec]
  (let [pkg (:go-package spec)
        bin-dir (go-bin-dir root)]
    (when-not (fs/which "go")
      (exit! 1 (str "Go toolchain not found on PATH. Install Go, then run: swarm_tool.sh ensure "
                    (go-package-name spec))))
    (fs/create-dirs bin-dir)
    ;; clojure.java.shell replaces the environment rather than merging it, so
    ;; pass the current environment through with GOBIN added; otherwise go
    ;; cannot find GOPATH, HOME, or PATH.
    (let [env (assoc (into {} (System/getenv)) "GOBIN" (str bin-dir))]
      (let [result (sh/sh "go" "install" (str pkg "@" (or (:go-version spec) "latest"))
                          :env env)]
        (when-not (zero? (:exit result))
          (exit! 1 (str "Failed to go install " pkg "\n" (:err result) (:out result))))))
    (str (go-binary-path root spec))))

;; Go tools are upstream Go modules, not babashka tasks, so they get a
;; wrapper that execs a binary installed by `go install` at ensure time. The
;; mutate-rewrite-bash prefix still applies, since it is plain argument
;; handling (drop --mutate-all, cap workers) rather than anything bb-specific.
(defn write-go-wrapper! [root tool spec]
  (let [bin (install-go-package! root spec)
        target (wrapper-path root tool)]
    (write-wrapper!
     target
     (str (rewrite-bash tool)
          "bin=" (sq bin) "\n"
          "if [ ! -x \"$bin\" ]; then\n"
          "  echo \"swarm_tool: missing $bin; run: swarm_tool.sh ensure " tool "\" >&2\n"
          "  exit 1\n"
          "fi\n"
          "exec \"$bin\" \"$@\"\n"))))

;; Kotlin tools are built by their own Gradle wrapper, from source: slopguard ships a CLI
;; (`:app:installDist`) and brings the Kotlin compiler embeddable it parses with. Nothing here is a
;; metric of our own making; it is the tool the constitution names, built the way its README says.
;;
;; Two things a Gradle source needs that a babashka one does not. Its sources are recognised by their
;; own wrapper rather than by bb.edn, so the clone is checked for a gradlew. And a third-party project's
;; wrapper usually lags the newest JDK — slopguard's 8.10.2 refuses Java 25 outright — so the build runs
;; under a JDK the wrapper supports, chosen deliberately below rather than inherited from the ambient
;; java. Set SWARMFORGE_GRADLE_JAVA_HOME to override the choice.
(defn gradle-java-home []
  (let [sdk (fs/path (System/getenv "HOME") ".sdkman" "candidates" "java")
        supported? #(re-matches #"(?:1[7-9]|2[0-3])\..*" (str (fs/file-name %)))]
    (or (not-empty (System/getenv "SWARMFORGE_GRADLE_JAVA_HOME"))
        (some (fn [dir] (when (supported? dir) (str dir)))
              (reverse (when (fs/directory? sdk) (sort-by str (fs/list-dir sdk)))))
        (not-empty (System/getenv "JAVA_HOME")))))

(defn ensure-gradle-source! [root source]
  (let [dir (source-dir root source)]
    (when-not (fs/exists? (fs/path dir "gradlew"))
      (clone-source! dir source))
    dir))

(defn install-gradle-package! [root spec]
  (let [src (ensure-gradle-source! root (:source spec))
        task (str ":" (:gradle-install spec))
        launcher (fs/path src "app" "build" "install" "slopguard-kotlin" "bin" "slopguard-kotlin")]
    (when-not (fs/which "java")
      (exit! 1 (str "JDK not found on PATH (17 or newer). Install it, then run: swarm_tool.sh ensure slopguard")))
    (when-let [commit (:commit spec)]
      (let [result (sh/sh "git" "-C" (str src) "checkout" "--quiet" commit)]
        (when-not (zero? (:exit result))
          (exit! 1 (str "Failed to check out " commit " in " src "\n" (:err result))))))
    ;; clojure.java.shell replaces the environment rather than merging it, so pass
    ;; the current environment through with JAVA_HOME set, the way the Go recipe
    ;; adds GOBIN; otherwise the build sees an unsupported JDK and fails cryptically.
    (let [java-home (gradle-java-home)
          env (if java-home (assoc (into {} (System/getenv)) "JAVA_HOME" java-home)
                  (into {} (System/getenv)))
          result (sh/sh (str (fs/path src "gradlew")) task :dir (str src) :env env)]
      (when-not (zero? (:exit result))
        (exit! 1 (str "Failed to build " (:source spec)
                      (when java-home (str " (JAVA_HOME=" java-home ")"))
                      "\n" (:err result) (:out result)))))
    (str launcher)))

(defn write-gradle-wrapper! [root tool spec]
  (let [bin (install-gradle-package! root spec)
        target (wrapper-path root tool)]
    (write-wrapper!
     target
     (str "bin=" (sq bin) "\n"
          "if [ ! -x \"$bin\" ]; then\n"
          "  echo \"swarm_tool: missing $bin; run: swarm_tool.sh ensure " tool "\" >&2\n"
          "  exit 1\n"
          "fi\n"
          "exec \"$bin\" \"$@\"\n"))))

(defn install-one! [tool]
  (let [spec (tool-spec tool)
        root (project-root)
        name (canonical-tool tool)
        target (cond
                 (:go-package spec) (write-go-wrapper! root name spec)
                 (:gradle-install spec) (write-gradle-wrapper! root name spec)
                 (:bb-task spec) (write-bb-wrapper! root name (:bb-task spec)
                                                    (ensure-source! root (:source spec)))
                 :else (write-mvn-wrapper! root name spec))]
    (println "INSTALLED:" name (str target))))

(defn ensure-tool! [tool]
  (tool-spec tool)
  (doseq [dep (needed-tools tool)]
    (ensure-tool! dep))
  (install-one! tool))

(defn -main [& args]
  (when (some #{"--help" "-h"} args)
    (usage)
    (System/exit 0))
  (when (not= 2 (count args))
    (usage)
    (System/exit 1))
  (let [[command tool] args]
    (case command
      "require" (require-tool! tool)
      "ensure" (ensure-tool! tool)
      (do (usage)
          (System/exit 1)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
