(ns swarmforge.update-exercise-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn tmp-dir []
  ;; A short base rather than TMPDIR: tmux refuses a socket path past the unix limit,
  ;; and TMPDIR's /var/folders path is most of that budget (see script_test.clj).
  (fs/create-temp-dir {:dir "/tmp" :prefix "sf-exercise."}))

(defn script [] (str (fs/path scripts-dir "update-exercise.sh")))

;; A forge the exercise can run against, and a composition that stands in for the
;; real helper: it changes one file the first time it is asked and nothing after,
;; which is what a converging upgrade looks like.
(defn fixture! []
  (let [root (tmp-dir)
        bare (tmp-dir)
        stub (tmp-dir)
        state (tmp-dir)]
    (write-file (fs/path root "swarmforge/scripts/one.sh") "one\n")
    (write-file (fs/path root "swarmforge/scripts/two.sh") "two\n")
    (fs/create-dirs (fs/path root "projects"))
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (run {:dir root} "git" "add" "-A")
    (run {:dir root} "git" "commit" "-q" "-m" "the forge")
    (run {:dir bare} "git" "clone" "-q" "--bare" (str root) "origin.git")
    (run {:dir root} "git" "remote" "add" "origin" (str (fs/path bare "origin.git")))
    (write-file (fs/path stub "get-swarm-forge")
                (str "#!/bin/sh\n"
                     "set -eu\n"
                     "count=\"$(cat \"$STUB_STATE/count\" 2>/dev/null || echo 0)\"\n"
                     "count=$((count + 1))\n"
                     "echo \"$count\" > \"$STUB_STATE/count\"\n"
                     "if [ \"$count\" = \"1\" ] || [ -n \"${STUB_ALWAYS_CHANGE:-}\" ]; then\n"
                     "  echo changed >> swarmforge/scripts/one.sh\n"
                     "fi\n"))
    (run {:dir stub} "chmod" "+x" "get-swarm-forge")
    {:root root :bare bare :stub stub :state state}))

(defn exercise
  ([fixture] (exercise fixture nil))
  ([{:keys [root stub state]} extra]
     (let [env (merge {"PATH" (str stub ":" (System/getenv "PATH"))
                     "STUB_STATE" (str state)}
                    extra)]
       (run {:dir root :env env :ok? false} (script) (str root)))))

(defn forget-the-stub! [{:keys [state]}]
  (fs/delete-if-exists (fs/path state "count")))

(deftest update-exercise-says-when-an-upgrade-would-do-something-unaccepted
  ;; Given a forge whose upgrade changes one file, and a baseline that accepts it
  ;; When the exercise runs
  ;; Then it passes, and it fails for each way an upgrade can do something nobody
  ;; accepted: an unlisted change, a stale baseline line, and a second compose
  ;; that does not converge
  (let [{:keys [root] :as fixture} (fixture!)]
    (try
      (write-file (fs/path root "swarmforge/update-exercise.baseline")
                  "# accepted, with the reason\nswarmforge/scripts/one.sh\tone file the layer changes\n")
      (let [accepted (exercise fixture)]
        (is (zero? (:exit accepted)) (:err accepted))
        (is (str/includes? (:out accepted) "an upgrade is a no-op here")
            (:out accepted)))

      (fs/delete (fs/path root "swarmforge/update-exercise.baseline"))
      (forget-the-stub! fixture)
      (let [unlisted (exercise fixture)]
        (is (not (zero? (:exit unlisted))))
        (is (str/includes? (:out unlisted)
                           "FAIL  swarmforge/scripts/one.sh changed and is not in the baseline")
            (:out unlisted)))

      (write-file (fs/path root "swarmforge/update-exercise.baseline")
                  (str "swarmforge/scripts/one.sh\tone file the layer changes\n"
                       "swarmforge/scripts/two.sh\ta file that no longer changes\n"))
      (forget-the-stub! fixture)
      (let [stale (exercise fixture)]
        (is (not (zero? (:exit stale))))
        (is (str/includes? (:out stale)
                           "FAIL  the baseline still excuses swarmforge/scripts/two.sh")
            (:out stale)))

      (write-file (fs/path root "swarmforge/update-exercise.baseline")
                  "swarmforge/scripts/one.sh\tone file the layer changes\n")
      (let [non-convergent (exercise fixture {"STUB_ALWAYS_CHANGE" "1"})]
        (is (not (zero? (:exit non-convergent))))
        (is (str/includes? (:out non-convergent)
                           "FAIL  an upgrade of an upgraded forge is not a no-op")
            (:out non-convergent)))

      ;; A forge already in step has nothing for the upgrade to commit, and the
      ;; exercise still runs its second compose and passes. It failed here before:
      ;; the commit with nothing staged gave up under `set -e`, on the one forge
      ;; that should pass most easily.
      ;;
      ;; The stub appends to one.sh on its first ask, so the counter is put past
      ;; that: a composition that changes nothing, twice.
      (write-file (fs/path (:state fixture) "count") "2\n")
      (fs/delete-if-exists (fs/path root "swarmforge/update-exercise.baseline"))
      (let [already-in-step (exercise fixture)]
        (is (zero? (:exit already-in-step)) (:err already-in-step))
        (is (str/includes? (:out already-in-step) "an upgrade is a no-op on this forge")
            (:out already-in-step)))
      (finally
        (fs/delete-tree root)
        (fs/delete-tree (:bare fixture))
        (fs/delete-tree (:stub fixture))
        (fs/delete-tree (:state fixture))))))
