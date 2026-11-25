(ns swarmforge.runtime-upgrade-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))
(def helper (str (fs/path scripts-dir "forge_runtime_upgrade.sh")))
(def source-commit (apply str (repeat 40 "a")))
(def temp-dirs (atom []))

(use-fixtures :once
  (fn [tests]
    (try
      (tests)
      (finally
        (doseq [dir @temp-dirs]
          (fs/delete-tree dir))))))

(defn tmp-dir []
  (let [dir (fs/create-temp-dir {:dir "/tmp" :prefix "sf-runtime-upgrade."})]
    (swap! temp-dirs conj dir)
    dir))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply clojure.java.shell/sh
                      (concat args [:dir (str dir)
                                    :env (merge {"PATH" (System/getenv "PATH")}
                                                env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      result)))
    result))

(defn parse-json [result]
  (json/parse-string (:out result) true))

(defn wait-until [predicate timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

(defn lock-available? [forge]
  (let [file (fs/path forge ".swarmforge" "runtime-upgrades" "upgrade.lock")]
    (fs/create-dirs (fs/parent file))
    (with-open [channel (java.nio.channels.FileChannel/open
                         file
                         (into-array [java.nio.file.StandardOpenOption/CREATE
                                      java.nio.file.StandardOpenOption/WRITE]))]
      (when-let [lock (try
                        (.tryLock channel)
                        (catch Exception _ nil))]
        (.close lock)
        true))))

(defn lock-holder-script! [forge]
  (let [file (fs/path forge "hold-upgrade-lock.bb")]
    (write-file
     file
     (str "#!/usr/bin/env bb\n"
          "(require '[babashka.fs :as fs])\n"
          "(let [[forge ready hold-ms] *command-line-args*\n"
          "      file (fs/path forge \".swarmforge/runtime-upgrades/upgrade.lock\")]\n"
          "  (fs/create-dirs (fs/parent file))\n"
          "  (with-open [channel (java.nio.channels.FileChannel/open\n"
          "                       file\n"
          "                       (into-array [java.nio.file.StandardOpenOption/CREATE\n"
          "                                    java.nio.file.StandardOpenOption/WRITE]))]\n"
          "    (let [lock (.lock channel)]\n"
          "      (spit ready \"locked\\n\")\n"
          "      (Thread/sleep (Long/parseLong hold-ms))\n"
          "      (.close lock))))\n"))
    (fs/set-posix-file-permissions file "rwxr-xr-x")
    file))

(defn source-fixture! []
  (let [source (tmp-dir)]
    (doseq [name ["engineering.prompt" "workflow.prompt" "handoffs.prompt"
                  "privacy.prompt" "stopping-without-finishing.prompt"]]
      (write-file (fs/path source "swarmforge/constitution/articles" name)
                  (str name "\n")))
    (write-file (fs/path source "swarmforge/constitution.prompt") "Constitution.\n")
    (write-file (fs/path source "swarmforge/roles/lieutenant.prompt") "Lieutenant.\n")
    (write-file (fs/path source "swarm") "#!/bin/sh\n")
    (write-file (fs/path source "swarmforge/scripts/runtime-marker")
                "${FAKE_RUNTIME_CONTENT:-v1}\n")
    (write-file (fs/path source "swarmforge/skills/shared/SKILL.md") "shared\n")
    (fs/create-dirs (fs/path source "swarmforge/scripts"))
    (doseq [name ["forge_runtime_upgrade.bb" "forge_runtime_upgrade.sh"
                  "stage_jobs.bb" "matrix-bridge.sh"]]
      (fs/copy (fs/path scripts-dir name)
               (fs/path source "swarmforge/scripts" name)
               {:replace-existing true}))
    (fs/set-posix-file-permissions
     (fs/path source "swarmforge/scripts/forge_runtime_upgrade.sh") "rwxr-xr-x")
    (let [composer (fs/path source "get-swarm-forge")]
      (write-file
       composer
       (str "#!/bin/sh\n"
            "set -eu\n"
            "if [ \"${FAKE_COMPOSE_FAIL:-0}\" = 1 ]; then exit 7; fi\n"
            "case \"$PWD\" in */runtime-upgrades/check-*) ;; "
            "*) if [ \"${FAKE_COMPOSE_FAIL_TARGET:-0}\" = 1 ]; then exit 8; fi ;; esac\n"
            "mkdir -p swarmforge/scripts swarmforge/constitution/articles swarmforge/roles\n"
            "cp " (str (fs/path source "swarmforge/scripts/forge_runtime_upgrade.bb"))
            " swarmforge/scripts/forge_runtime_upgrade.bb\n"
            "cp " (str (fs/path source "swarmforge/scripts/forge_runtime_upgrade.sh"))
            " swarmforge/scripts/forge_runtime_upgrade.sh\n"
            "cp " (str (fs/path source "swarmforge/scripts/stage_jobs.bb"))
            " swarmforge/scripts/stage_jobs.bb\n"
            "cp " (str (fs/path source "swarmforge/scripts/matrix-bridge.sh"))
            " swarmforge/scripts/matrix-bridge.sh\n"
            "chmod +x swarmforge/scripts/forge_runtime_upgrade.sh\n"
            "printf '%s\\n' \"${SWARMFORGE_SKIP_BRIDGE:-0}\""
            " >> \"${SWARMFORGE_TEST_BRIDGE_LOG:-/dev/null}\"\n"
            "if [ -z \"${SWARMFORGE_SKIP_BRIDGE:-}\" ]; then "
            "printf '#!/bin/sh\\n# bridge installed adapter\\n'"
            " > swarmforge/scripts/matrix-bridge.sh; fi\n"
            "printf '%s\\n' \"${FAKE_RUNTIME_CONTENT:-v1}\" > swarmforge/scripts/runtime-marker\n"
            "printf '#!/bin/sh\\n' > swarm\n"
            "printf 'Constitution.\\n' > swarmforge/constitution.prompt\n"
            "printf 'Lieutenant.\\n' > swarmforge/roles/lieutenant.prompt\n"
            "for name in engineering.prompt workflow.prompt handoffs.prompt "
            "privacy.prompt stopping-without-finishing.prompt; do "
            "printf '%s\\n' \"$name\" > "
            "\"swarmforge/constitution/articles/$name\"; done\n"
            "mkdir -p swarmforge/skills/shared\n"
            "printf 'shared\\n' > swarmforge/skills/shared/SKILL.md\n"
            "if [ \"${FAKE_BRIDGE_POLICY_MISMATCH_ON_APPLY:-0}\" = 1 ]; then\n"
            "  case \"$PWD\" in */runtime-upgrades/check-*) ;; "
            "*) unset SWARMFORGE_SKIP_BRIDGE ;; esac\n"
            "fi\n"
            "bb " (str (fs/path source "swarmforge/scripts/forge_runtime_upgrade.bb"))
            " manifest --forge \"$PWD\" --source-layer " source
            " --product \"$1\" --source-commit \"$SWARMFORGE_SOURCE_COMMIT\"\n"))
      (fs/set-posix-file-permissions composer "rwxr-xr-x"))
    (let [upgrader (fs/path source "get-agent-project")]
      (write-file
       upgrader
       (str "#!/bin/sh\n"
            "set -eu\n"
            "project=\"\"\n"
            "check=0\n"
            "while [ $# -gt 0 ]; do\n"
            "  case \"$1\" in --forge) forge=\"$2\"; shift 2 ;; "
            "--upgrade) project=\"$2\"; shift 2 ;; --check) check=1; shift ;; "
            "*) shift ;; esac\n"
            "done\n"
            "marker=\"$forge/projects/$project/.swarmforge/template-current\"\n"
            "if [ \"$check\" = 1 ]; then\n"
            "  [ -f \"$marker\" ] && exit 0\n"
            "  printf '%s\\n' 'upgrade needed: refresh project template'\n"
            "  exit 1\n"
            "fi\n"
            "touch \"$marker\"\n"
            "printf '%s\\n' 'upgraded: refresh project template'\n"))
      (fs/set-posix-file-permissions upgrader "rwxr-xr-x"))
    source))

(def wrapper
  (str "#!/bin/sh\n"
       "set -eu\n"
       "if [ \"$#\" = 1 ]; then\n"
       "  root=\"$1\"\n"
       "  mkdir -p \"$root/.swarmforge\"\n"
       "  printf '%s\\n' \"$1|$PWD\" >> \"$root/.swarmforge/upgrade-commands.log\"\n"
       "  touch \"$root/.swarmforge/test-host-healthy\"\n"
       "  exit 0\n"
       "fi\n"
       "root=\"$2\"\n"
       "project=\"${3:-}\"\n"
       "mkdir -p \"$root/.swarmforge\"\n"
       "printf '%s|%s\\n' \"$*\" \"$PWD\" >> \"$root/.swarmforge/upgrade-commands.log\"\n"
       "case \"$1\" in\n"
       "  --health)\n"
       "    [ -f \"$root/.swarmforge/test-host-healthy\" ]\n"
       "    ;;\n"
       "  --health-project)\n"
       "    [ -f \"$root/projects/$project/.swarmforge/test-healthy\" ]\n"
       "    ;;\n"
       "  --close-project)\n"
       "    awk -v name=\"$project\" '$0 != name' \"$root/.swarmforge/open-projects\" "
       "> \"$root/.swarmforge/open-projects.tmp\"\n"
       "    mv \"$root/.swarmforge/open-projects.tmp\" \"$root/.swarmforge/open-projects\"\n"
       "    ;;\n"
       "  --open-project)\n"
       "    grep -qxF \"$project\" \"$root/.swarmforge/open-projects\" || "
       "printf '%s\\n' \"$project\" >> \"$root/.swarmforge/open-projects\"\n"
       "    touch \"$root/projects/$project/.swarmforge/test-healthy\"\n"
       "    ;;\n"
       "  --stop-forge-runtime)\n"
       "    rm -f \"$root/.swarmforge/test-host-healthy\"\n"
       "    ;;\n"
       "  *)\n"
       "    touch \"$root/.swarmforge/test-host-healthy\"\n"
       "    ;;\n"
       "esac\n"))

(defn forge-fixture! []
  (let [source (source-fixture!)
        forge (tmp-dir)]
    (write-file (fs/path forge "swarmforge/scripts/swarmforge.sh") wrapper)
    (fs/set-posix-file-permissions
     (fs/path forge "swarmforge/scripts/swarmforge.sh") "rwxr-xr-x")
    (write-file (fs/path forge ".swarmforge/open-projects") "alpha\nbeta\n")
    (write-file (fs/path forge ".swarmforge/test-host-healthy") "yes\n")
    (write-file (fs/path forge ".swarmforge/tailscale.serve") "serve unchanged\n")
    (write-file (fs/path forge "other-family-sentinel") "other\n")
    (doseq [name ["alpha" "beta"]]
      (write-file (fs/path forge "projects" name ".swarmforge/single-agent") "agent\n")
      (write-file (fs/path forge "projects" name ".swarmforge/test-healthy") "yes\n")
      (write-file (fs/path forge "projects" name ".swarmforge/board/tasks.tsv")
                  "finished\tdone\tnow\tnow\tid\t0\n")
      (write-file (fs/path forge "projects" name "AGENTS.md") (str name " old\n"))
      (write-file (fs/path forge "projects" name "local-untracked.txt")
                  (str name " local\n")))
    (write-file (fs/path forge "projects/alpha/.swarmforge/dashboard/requests/pending/req-1.request")
                "id: req-1\nstatus: pending\n\nKeep this request.\n")
    (run {:dir forge
          :env {"FAKE_RUNTIME_CONTENT" "v1"
                "SWARMFORGE_SOURCE_COMMIT" source-commit}}
         (str (fs/path source "get-swarm-forge")) "project-manager")
    (write-file (fs/path forge ".swarmforge/matrix-bridge.json")
                "{\"state_dir\":\"bridge-state\",\"rooms\":{\"alpha\":\"!room\"}}\n")
    (write-file (fs/path forge "projects/forgelet-bridge/.git/HEAD")
                "ref: refs/heads/master\n")
    (write-file (fs/path forge "projects/forgelet-bridge/build/acceptance/bin/forgelet-bridge")
                "BRIDGE-BINARY\n")
    (write-file (fs/path forge "projects/forgelet-bridge/bridge-state/rooms.json")
                "{\"alpha\":\"!room\"}\n")
    (write-file (fs/path forge "swarmforge/scripts/matrix-bridge.sh")
                "#!/bin/sh\n# bridge installed adapter\n")
    (fs/set-posix-file-permissions
     (fs/path forge "swarmforge/scripts/matrix-bridge.sh") "rwxr-xr-x")
    {:source source :forge forge}))

(def bridge-fixture-paths
  [".swarmforge/matrix-bridge.json"
   "projects/forgelet-bridge/.git/HEAD"
   "projects/forgelet-bridge/build/acceptance/bin/forgelet-bridge"
   "projects/forgelet-bridge/bridge-state/rooms.json"
   "swarmforge/scripts/matrix-bridge.sh"])

(defn bridge-fixture-bytes [forge]
  (into {}
        (map (fn [relative]
               [relative (vec (fs/read-all-bytes (fs/path forge relative)))])
             bridge-fixture-paths)))

(defn latest-operation [forge]
  (let [files (->> (fs/list-dir
                    (fs/path forge ".swarmforge/runtime-upgrades/operations"))
                   (sort-by str))
        latest (last files)]
    (edn/read-string (slurp (str latest)))))

(defn status! [{:keys [source forge]} env]
  (run {:dir forge :ok? false
        :env (merge {"SWARMFORGE_SOURCE_COMMIT" source-commit
                     "FAKE_RUNTIME_CONTENT" "v1"} env)}
       helper "status" "--forge" (str forge)
       "--source-layer" (str source) "--source-commit" source-commit))

(defn apply! [{:keys [source forge]} env]
  (run {:dir forge :ok? false
        :env (merge {"SWARMFORGE_SOURCE_COMMIT" source-commit
                     "FAKE_RUNTIME_CONTENT" "v1"} env)}
       helper "apply" "--forge" (str forge)
       "--source-layer" (str source) "--source-commit" source-commit))

(deftest get-swarm-forge-writes-the-authoritative-runtime-manifest
  (let [base (tmp-dir)
        packs (tmp-dir)
        target (tmp-dir)]
    (doseq [name ["swarmforge.sh" "handoffd.bb" "done_with_current.sh"
                  "route_card.sh" "route_card.bb" "install-agent-credentials.sh"
                  "forge_runtime_upgrade.bb" "forge_runtime_upgrade.sh"]]
      (let [source (if (#{"forge_runtime_upgrade.bb"
                          "forge_runtime_upgrade.sh"} name)
                     (fs/path scripts-dir name)
                     nil)]
        (if source
          (fs/copy source (fs/path base "swarmforge/scripts" name)
                   {:replace-existing true})
          (write-file (fs/path base "swarmforge/scripts" name) (str name "\n")))))
    (fs/set-posix-file-permissions
     (fs/path base "swarmforge/scripts/forge_runtime_upgrade.sh") "rwxr-xr-x")
    (doseq [name ["engineering.prompt" "workflow.prompt" "handoffs.prompt"
                  "privacy.prompt" "stopping-without-finishing.prompt"]]
      (write-file (fs/path base "swarmforge/constitution/articles" name)
                  (str name "\n")))
    (write-file (fs/path base "swarmforge/constitution.prompt") "Constitution.\n")
    (write-file (fs/path base "swarmforge/roles/lieutenant.prompt") "Lieutenant.\n")
    (write-file (fs/path base "swarm") "#!/bin/sh\n")
    (doseq [name ["two-pack" "four-pack" "six-pack"]]
      (write-file (fs/path packs name "swarm") "#!/bin/sh\n")
      (write-file (fs/path packs name "swarmforge/swarmforge.conf")
                  "window specifier grok master\n")
      (write-file (fs/path packs name "swarmforge/roles/specifier.prompt")
                  "specifier\n"))
    (let [composed
          (run {:dir target
                :env {"SWARMFORGE_BASE_DIR" (str base)
                      "SWARMFORGE_PACKS_DIR" (str packs)
                      "SWARMFORGE_SKIP_BRIDGE" "1"
                      "SWARMFORGE_NO_SELF_UPDATE" "1"
                      "SWARMFORGE_SOURCE_COMMIT" source-commit}}
               (str (fs/path repo-root "get-swarm-forge"))
               "project-manager")
          manifest-file (fs/path target ".swarmforge/runtime-manifest.edn")]
      (is (zero? (:exit composed)) (str (:err composed) (:out composed)))
      (is (fs/regular-file? manifest-file))
      (let [manifest (clojure.edn/read-string (slurp (str manifest-file)))]
        (is (= source-commit (:source_commit manifest)))
        (is (= "project-manager" (:product manifest)))
        (is (str/starts-with? (:digest manifest) "sha256:"))
        (is (some #(= "swarmforge/scripts/forge_runtime_upgrade.bb" (first %))
                  (:files manifest)))))))

(deftest runtime-status-current-behind-unknown-and-local-differences
  (let [{:keys [source forge] :as fixture} (forge-fixture!)]
    (let [current (status! fixture {})
          body (parse-json current)]
      (is (zero? (:exit current)) (:err current))
      (is (= "current" (:status body)))
      (is (= source-commit (get-in body [:installed :source_commit])))
      (is (not (str/includes? (:out current) (str forge))))
      (is (not (str/includes? (:out current) (str source)))))

    ;; Forge-local files and accepted exclusions do not alter runtime identity.
    (write-file (fs/path forge "swarmforge/roles/local-lieutenant.prompt") "local\n")
    (write-file (fs/path forge "swarmforge/swarmforge.conf") "Lieutenant claude\n")
    (write-file (fs/path forge "swarmforge/skills/forge-owned/SKILL.md") "local\n")
    (is (= "current" (:status (parse-json (status! fixture {})))))

    (write-file (fs/path source "swarmforge/scripts/runtime-marker") "v2\n")
    (let [behind (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"})
          body (parse-json behind)]
      (is (zero? (:exit behind)) (:err behind))
      (is (= "behind" (:status body)))
      (is (pos? (get-in body [:changes :count])))
      (is (some #(= "swarmforge/scripts/runtime-marker" (:path %))
                (get-in body [:changes :items])))
      (is (= ["alpha" "beta"]
             (:project_upgrades_required body)))
      (is (= "v2\n" (slurp (str (fs/path source "swarmforge/scripts/runtime-marker"))))))

    (write-file (fs/path forge ".swarmforge/runtime-manifest.edn") "{:broken")
    (let [unknown (status! fixture {})
          body (parse-json unknown)]
      (is (not (zero? (:exit unknown))))
      (is (= "unknown" (:status body)))
      (is (str/includes? (:reason body) "manifest")))))

(deftest runtime-status-reports-unavailable-source
  (let [{:keys [source] :as fixture} (forge-fixture!)]
    (write-file (fs/path source "get-swarm-forge") "#!/bin/sh\nexit 7\n")
    (fs/set-posix-file-permissions (fs/path source "get-swarm-forge") "rwxr-xr-x")
    (let [result (status! fixture {})
          body (parse-json result)]
      (is (not (zero? (:exit result))))
      (is (= "unavailable" (:status body)))
      (is (not (str/includes? (:reason body) (str source)))))))

(deftest current-runtime-apply-is-a-no-op
  (let [{:keys [forge] :as fixture} (forge-fixture!)]
    (doseq [name ["alpha" "beta"]]
      (write-file (fs/path forge "projects" name ".swarmforge/template-current")
                  "current\n"))
    (let [applied (apply! fixture {})
          body (parse-json applied)]
      (is (zero? (:exit applied)) (str (:err applied) (:out applied)))
      (is (= "current" (:status body)))
      (is (= [] (:reopened body)))
      (is (not (fs/exists?
                (fs/path forge ".swarmforge/upgrade-commands.log")))))))

(deftest runtime-upgrade-refuses-busy-and-changed-busy-state
  (let [{:keys [source forge] :as fixture} (forge-fixture!)]
    (write-file (fs/path source "swarmforge/scripts/runtime-marker") "v2\n")
    (write-file (fs/path forge "projects/alpha/.swarmforge/board/tasks.tsv")
                "active\tcode\tnow\tnow\tid\t0\n")
    (let [busy (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"})
          body (parse-json busy)]
      (is (= "behind" (:status body)))
      (is (= false (:idle body)))
      (is (= "alpha" (get-in body [:blockers 0 :project])))
      (is (= "active" (get-in body [:blockers 0 :card]))))

    ;; The state changes after the host saw an idle plan. Apply must recheck it.
    (write-file (fs/path forge "projects/alpha/.swarmforge/board/tasks.tsv")
                "finished\tdone\tnow\tnow\tid\t0\n")
    (is (= true (:idle (parse-json (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"})))))
    (write-file (fs/path forge "projects/beta/.swarmforge/board/tasks.tsv")
                "beta-active\tspec\tnow\tnow\tid\t0\n")
    (let [refused (apply! fixture {"FAKE_RUNTIME_CONTENT" "v2"})
          body (parse-json refused)]
      (is (= 3 (:exit refused)))
      (is (= "preflight-failed" (:status body)))
      (is (= false (:idle body)))
      (is (= ["alpha" "beta"]
             (->> (slurp (str (fs/path forge ".swarmforge/open-projects")))
                  str/split-lines
                  (remove str/blank?)
                  vec)))
      (is (= "v1\n"
             (slurp (str (fs/path forge "swarmforge/scripts/runtime-marker")))))
      (is (not (fs/exists? (fs/path forge ".swarmforge/upgrade-commands.log")))))))

(deftest runtime-upgrade-applies-idle-family-and-preserves-state
  (let [{:keys [source forge] :as fixture} (forge-fixture!)
        tailscale-before (slurp (str (fs/path forge ".swarmforge/tailscale.serve")))
        other-before (slurp (str (fs/path forge "other-family-sentinel")))]
    (write-file (fs/path source "swarmforge/scripts/runtime-marker") "v2\n")
    (let [status (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"})
          before (parse-json status)
          applied (apply! fixture {"FAKE_RUNTIME_CONTENT" "v2"})
          result (parse-json applied)]
      (is (= "behind" (:status before)))
      (is (zero? (:exit applied)) (str (:err applied) (:out applied)))
      (is (= "current" (:status result)) (str (:err applied) (:out applied)))
      (is (= "complete" (:phase result)))
      (is (= true (:completed result)))
      (is (= ["alpha" "beta"] (:reopened result)))
      (is (= (:digest (:available before)) (get-in result [:installed :digest])))
      (is (= "v2\n"
             (slurp (str (fs/path forge "swarmforge/scripts/runtime-marker")))))
      (is (= ["alpha" "beta"]
             (->> (slurp (str (fs/path forge ".swarmforge/open-projects")))
                  str/split-lines
                  (remove str/blank?)
                  sort
                  vec)))
      (doseq [name ["alpha" "beta"]]
        (is (fs/exists?
             (fs/path forge "projects" name ".swarmforge/template-current")))
        (is (= (str name " local\n")
               (slurp (str (fs/path forge "projects" name
                                    "local-untracked.txt")))))
        (is (fs/exists?
             (fs/path forge "projects" name ".swarmforge/test-healthy"))))
      (is (fs/exists?
           (fs/path forge "projects/alpha/.swarmforge/dashboard/requests/pending/req-1.request")))
      (is (= tailscale-before
             (slurp (str (fs/path forge ".swarmforge/tailscale.serve")))))
      (is (= other-before
             (slurp (str (fs/path forge "other-family-sentinel")))))
      (let [commands (str/split-lines
                      (slurp (str (fs/path forge ".swarmforge/upgrade-commands.log"))))]
        (is (some #(str/starts-with? % "--stop-forge-runtime ") commands))
        (is (= 2 (count (filter #(str/starts-with? % "--close-project ") commands))))
        (is (= 2 (count (filter #(str/starts-with? % "--open-project ") commands)))))
      (is (= "current"
             (:status (parse-json (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"}))))))))

(deftest runtime-upgrade-uses-one-policy-and-preserves-the-installed-bridge
  (let [{:keys [source forge] :as fixture} (forge-fixture!)
        policy-log (fs/path forge ".swarmforge/bridge-policy.log")
        bridge-before (bridge-fixture-bytes forge)]
    (write-file (fs/path source "swarmforge/scripts/runtime-marker") "v2\n")
    (let [status (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"
                                   "SWARMFORGE_TEST_BRIDGE_LOG" (str policy-log)})
          before (parse-json status)
          applied (apply! fixture {"FAKE_RUNTIME_CONTENT" "v2"
                                   "SWARMFORGE_TEST_BRIDGE_LOG" (str policy-log)})
          result (parse-json applied)
          operation (latest-operation forge)]
      (is (= "behind" (:status before)) (str (:err status) (:out status)))
      (is (zero? (:exit applied)) (str (:err applied) (:out applied)))
      (is (= "current" (:status result)))
      (is (= (:digest (:available before))
             (get-in result [:installed :digest])))
      (let [policies (->> (slurp (str policy-log))
                          str/split-lines
                          (remove str/blank?)
                          vec)]
        (is (<= 3 (count policies)))
        (is (= ["1"] (distinct policies))))
      (is (= bridge-before (bridge-fixture-bytes forge)))
      (doseq [policy [(:bridge_policy before)
                      (:bridge_policy result)
                      (:bridge_policy operation)]]
        (is (= "preserve-installed-bridge-v1" (:id policy)))
        (is (= "preserve-existing" (:mode policy)))
        (is (= true (:skip_build_and_install policy)))
        (is (= ["swarmforge/scripts/matrix-bridge.sh"]
               (:preserve_files policy))))
      (is (= "current"
             (:status
              (parse-json
               (status! fixture {"FAKE_RUNTIME_CONTENT" "v2"}))))))))

(deftest runtime-upgrade-rejects-a-bridge-policy-mismatch-before-digest-check
  (let [{:keys [source forge] :as fixture} (forge-fixture!)
        policy-log (fs/path forge ".swarmforge/bridge-policy.log")
        bridge-before (bridge-fixture-bytes forge)]
    (write-file (fs/path source "swarmforge/scripts/runtime-marker") "v2\n")
    (let [status (status! fixture
                          {"FAKE_RUNTIME_CONTENT" "v2"
                           "SWARMFORGE_TEST_BRIDGE_LOG" (str policy-log)})
          applied (apply! fixture
                          {"FAKE_RUNTIME_CONTENT" "v2"
                           "SWARMFORGE_TEST_BRIDGE_LOG" (str policy-log)
                           "FAKE_BRIDGE_POLICY_MISMATCH_ON_APPLY" "1"})
          result (parse-json applied)
          operation (latest-operation forge)]
      (is (= "behind" (:status (parse-json status))))
      (is (= 4 (:exit applied)))
      (is (= "failed" (:status result)))
      (is (= "compose" (:phase result)))
      (is (= "bridge-policy-mismatch" (:reason result)))
      (is (not= "compose-digest-mismatch" (:reason result)))
      (is (= "bridge-policy-mismatch" (name (:reason operation))))
      (is (= "complete" (:recovery result)))
      (is (= bridge-before (bridge-fixture-bytes forge))))))

(deftest runtime-upgrade-failure-recovers-and-is-retryable
  (let [{:keys [source forge] :as fixture} (forge-fixture!)]
    (write-file (fs/path source "swarmforge/scripts/runtime-marker") "v2\n")
    (let [failed (apply! fixture {"FAKE_RUNTIME_CONTENT" "v2"
                                  "FAKE_COMPOSE_FAIL_TARGET" "1"})
          body (parse-json failed)]
      (is (= 4 (:exit failed)))
      (is (= "failed" (:status body)))
      (is (= "compose" (:phase body)))
      (is (= "complete" (:recovery body)))
      (is (= ["alpha" "beta"]
             (->> (slurp (str (fs/path forge ".swarmforge/open-projects")))
                  str/split-lines
                  (remove str/blank?)
                  sort
                  vec)))
      (is (fs/exists? (fs/path forge ".swarmforge/test-host-healthy"))))

    (let [retried (apply! fixture {"FAKE_RUNTIME_CONTENT" "v2"})
          body (parse-json retried)]
      (is (zero? (:exit retried)) (str (:err retried) (:out retried)))
      (is (= "current" (:status body)) (str (:err retried) (:out retried)))
      (is (= "v2\n"
             (slurp (str (fs/path forge "swarmforge/scripts/runtime-marker"))))))))

(deftest runtime-upgrade-status-reports-checking-under-the-host-lock
  (let [{:keys [forge] :as fixture} (forge-fixture!)
        lock-file (fs/path forge ".swarmforge/runtime-upgrades/upgrade.lock")]
    (fs/create-dirs (fs/parent lock-file))
    (with-open [channel (java.nio.channels.FileChannel/open
                         lock-file
                         (into-array [java.nio.file.StandardOpenOption/CREATE
                                      java.nio.file.StandardOpenOption/WRITE]))]
      (.tryLock channel)
      (let [result (status! fixture {})
            body (parse-json result)]
        (is (zero? (:exit result)))
        (is (= "checking" (:status body)))))))

(deftest runtime-upgrade-status-releases-lock-on-every-process-exit-path
  (let [{:keys [source forge] :as fixture} (forge-fixture!)
        holder-script (lock-holder-script! forge)
        ready (fs/path forge "lock-holder.ready")
        holder (atom nil)]
    (try
      (testing "a successful status releases its own lock"
        (is (= "current" (:status (parse-json (status! fixture {})))))
        (is (lock-available? forge))
        (is (= "current" (:status (parse-json (status! fixture {}))))
            "repeated reads do not observe a stale lock"))

      (testing "an overlapping status reports checking until the lock holder exits"
        (reset! holder
                (process/process
                 {:dir (str forge) :out :string :err :string}
                 "bb" (str holder-script) (str forge) (str ready) "500"))
        (is (wait-until #(fs/regular-file? ready) 2000))
        (let [busy (status! fixture {})]
          (is (zero? (:exit busy)) (:err busy))
          (is (= "checking" (:status (parse-json busy)))))
        (is (zero? (:exit @@holder)) (:err @@holder))
        (is (wait-until #(lock-available? forge) 1000)))

      (testing "an error status exits and releases the lock"
        (write-file (fs/path source "get-swarm-forge") "#!/bin/sh\nexit 7\n")
        (fs/set-posix-file-permissions
         (fs/path source "get-swarm-forge") "rwxr-xr-x")
        (let [failed (status! fixture {})]
          (is (not (zero? (:exit failed))))
          (is (= "unavailable" (:status (parse-json failed)))))
        (is (lock-available? forge)))

      (testing "a timed-out status process cannot retain the lock"
        (write-file (fs/path source "get-swarm-forge")
                    "#!/bin/sh\nsleep 5\n")
        (fs/set-posix-file-permissions
         (fs/path source "get-swarm-forge") "rwxr-xr-x")
        (let [timed (process/process
                     {:dir (str forge) :out :string :err :string}
                     helper "status" "--forge" (str forge)
                     "--source-layer" (str source)
                     "--source-commit" source-commit)]
          (is (wait-until #(not (lock-available? forge)) 2000))
          (process/destroy-tree timed)
          @timed
          (is (wait-until #(lock-available? forge) 2000))))
      (finally
        (when-let [holder @holder]
          (process/destroy-tree holder)
          (try @holder (catch Exception _ nil)))))))
