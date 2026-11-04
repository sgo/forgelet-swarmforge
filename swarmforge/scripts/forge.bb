#!/usr/bin/env bb

(ns forge
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def pack-names ["two-pack" "four-pack" "six-pack"])
(def shared-articles ["engineering.prompt" "workflow.prompt" "handoffs.prompt" "privacy.prompt"])

(defn sh [& args]
  (apply process/sh args))

(defn shell-quote [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn forge? [root]
  (fs/directory? (fs/path root "projects")))

(defn packs-dir [root]
  (fs/path root "packs"))

(defn projects-dir [root]
  (fs/path root "projects"))

(defn pack-dir [root pack]
  (fs/path (packs-dir root) pack))

(defn project-dir [root name]
  (fs/path (projects-dir root) name))

(defn inferred-name
  ([input] (inferred-name input false))
  ([input github?]
   (let [trimmed (str/trim (or input ""))]
     (if github?
       (let [trimmed (str/replace trimmed #"\.git$" "")
             trimmed (str/replace trimmed #"^https?://github.com/" "")
             trimmed (str/replace trimmed #"^git@github.com:" "")]
         (or (last (str/split trimmed #"/")) trimmed))
       trimmed))))

(defn github-clone-url [name]
  (let [base (or (not-empty (System/getenv "SWARMFORGE_GITHUB_BASE"))
                 "https://github.com/")
        name (-> name str/trim
                 (str/replace #"\.git$" "")
                 (str/replace #"^https?://github.com/" "")
                 (str/replace #"^git@github.com:" ""))]
    (if (str/starts-with? base "http")
      (str (str/replace base #"/+$" "") "/" name ".git")
      (str (fs/path (str/replace base #"/+$" "") name)))))

(defn list-pack-names [root]
  (let [dir (packs-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (filter #(fs/regular-file? (fs/path % "swarmforge" "swarmforge.conf")))
           (map fs/file-name)
           sort
           vec)
      [])))

(defn pack-conf [root pack]
  (let [file (fs/path (pack-dir root pack) "swarmforge" "swarmforge.conf")]
    (when (fs/regular-file? file)
      (slurp (str file)))))

(defn managed-project-root? [project]
  (boolean
   (some #(fs/regular-file? (fs/path project ".swarmforge" %))
         ["single-agent" "pack" "roles.tsv"])))

(defn list-project-names [root]
  (let [dir (projects-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (filter managed-project-root?)
           (map fs/file-name)
           (remove #(str/starts-with? % "."))
           sort
           vec)
      [])))

(defn open-projects-file [root]
  (fs/path root ".swarmforge" "open-projects"))

(defn read-open-projects [root]
  (let [file (open-projects-file root)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (map str/trim)
           (remove str/blank?)
           vec)
      [])))

(defn write-open-projects! [root names]
  (let [file (open-projects-file root)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (apply str (map #(str % "\n") names)))))

(defn project-open? [root name]
  (boolean (some #{name} (read-open-projects root))))

(defn mark-open! [root name]
  (write-open-projects! root (vec (distinct (conj (read-open-projects root) name)))))

(defn mark-closed! [root name]
  (write-open-projects! root (vec (remove #{name} (read-open-projects root)))))

(defn pack-name-file [project]
  (fs/path project ".swarmforge" "pack"))

(defn stored-pack [project]
  (let [file (pack-name-file project)]
    (when (fs/regular-file? file)
      (str/trim (slurp (str file))))))

(defn write-pack-name! [project pack]
  (let [file (pack-name-file project)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str pack "\n"))))

(defn single-agent? [project]
  (fs/regular-file? (fs/path project ".swarmforge" "single-agent")))

(defn copy-tree-replace! [src dest]
  (when (fs/exists? dest)
    (fs/delete-tree dest))
  (fs/create-dirs (fs/parent dest))
  (fs/copy-tree src dest))

(defn copy-shared-scripts! [forge dest]
  (let [src (fs/path forge "swarmforge" "scripts")]
    (when-not (fs/directory? src)
      (throw (ex-info (str "Missing host scripts at " src) {:http-status 500})))
    (copy-tree-replace! src (fs/path dest "swarmforge" "scripts"))))

(defn copy-shared-articles! [forge dest]
  (doseq [name shared-articles]
    (let [src (fs/path forge "swarmforge" "constitution" "articles" name)]
      (when (fs/regular-file? src)
        (fs/create-dirs (fs/path dest "swarmforge" "constitution" "articles"))
        (fs/copy src (fs/path dest "swarmforge" "constitution" "articles" name)
                 {:replace-existing true})))))

(defn copy-pack-local! [pack-root dest keep-conf?]
  (let [roles (fs/path pack-root "swarmforge" "roles")
        conf (fs/path pack-root "swarmforge" "swarmforge.conf")
        constitution (fs/path pack-root "swarmforge" "constitution.prompt")
        articles (fs/path pack-root "swarmforge" "constitution" "articles")]
    (when (fs/directory? roles)
      (copy-tree-replace! roles (fs/path dest "swarmforge" "roles")))
    (when (and (not keep-conf?) (fs/regular-file? conf))
      (fs/create-dirs (fs/path dest "swarmforge"))
      (fs/copy conf (fs/path dest "swarmforge" "swarmforge.conf") {:replace-existing true}))
    (when (fs/regular-file? constitution)
      (fs/copy constitution (fs/path dest "swarmforge" "constitution.prompt") {:replace-existing true}))
    (when (fs/directory? articles)
      (fs/create-dirs (fs/path dest "swarmforge" "constitution" "articles"))
      (doseq [file (fs/list-dir articles)]
        (when (fs/regular-file? file)
          (let [name (fs/file-name file)]
            (when-not (some #{name} shared-articles)
              (fs/copy file (fs/path dest "swarmforge" "constitution" "articles" name)
                       {:replace-existing true}))))))))

(defn ensure-line-in-file! [file line]
  (let [lines (when (fs/exists? file) (set (str/split-lines (slurp (str file)))))]
    (when-not (contains? lines line)
      (spit (str file) (str line "\n") :append true))))

;; A pack may carry a `gitignore` file: the lines a project written in its
;; language needs git to leave alone, before the first build and after it - its
;; build output, so that never becomes the first commit, and the host's own
;; noise, which is the same in every language. They are merged into the
;; project's .gitignore rather than copied over it - a line the project added
;; later stays, and no line arrives twice.
(defn copy-pack-ignores! [pack-root dest]
  (let [src (fs/path pack-root "gitignore")]
    (when (fs/regular-file? src)
      (let [file (fs/path dest ".gitignore")]
        (fs/create-dirs dest)
        (fs/create-dirs (fs/parent file))
        (when-not (fs/exists? file)
          (spit (str file) ""))
        (doseq [raw (str/split-lines (slurp (str src)))]
          (let [line (str/trim raw)]
            (when-not (str/blank? line)
              (ensure-line-in-file! file line))))))))

(defn overlay-pack! [forge dest pack keep-conf?]
  (copy-shared-scripts! forge dest)
  (copy-shared-articles! forge dest)
  (copy-pack-local! (pack-dir forge pack) dest keep-conf?)
  (copy-pack-ignores! (pack-dir forge pack) dest))

(defn git-identity
  "The identity git would use for a commit here, or nil when the machine has
  none configured."
  []
  (let [asked (fn [key]
                (str/trim (:out (sh {:continue true} "git" "config" "--get" key))))
        name (asked "user.name")
        email (asked "user.email")]
    (when (and (not (str/blank? name)) (not (str/blank? email)))
      [name email])))

(defn init-git-if-needed! [dir]
  (when-not (fs/exists? (fs/path dir ".git"))
    (sh "git" "init" (str dir))
    (sh "git" "-C" (str dir) "branch" "-M" "master")
    (let [gitignore (fs/path dir ".gitignore")]
      (when-not (fs/exists? gitignore)
        (spit (str gitignore) ".swarmforge/\n.worktrees/\n")))
    (sh {:continue true} "git" "-C" (str dir) "add" ".")
    ;; Never write an identity into the project's config: the commits in this
    ;; repository are the operator's work, not the scaffolding tool's, and a
    ;; repository-local identity would silently claim every later commit too.
    ;; Only when the machine has no identity at all do we lend one, and then
    ;; for this single commit rather than for the repository. Upstream writes
    ;; SwarmForge/swarmforge@local into every scaffold it makes; this layer does
    ;; not, which is why the change lives here rather than in a pull request.
    (let [commit (if (git-identity)
                   (sh {:continue true} "git" "-C" (str dir)
                       "commit" "-q" "-m" "Initial swarmforge project")
                   (sh {:continue true} "git" "-C" (str dir)
                       "-c" "user.name=SwarmForge Scaffold"
                       "-c" "user.email=swarmforge@localhost"
                       "commit" "-q" "-m" "Initial swarmforge project"))]
      (when-not (zero? (:exit commit))
        (throw (ex-info (str "Scaffold commit failed in " dir ": "
                             (str/trim (str (:err commit) (:out commit))))
                        {:exit (:exit commit)}))))))

(defn clone-github! [url dest]
  (let [result (sh "git" "clone" "--" url (str dest))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Clone failed: " (str/trim (str (:err result) "\n" (:out result))))
                      {:http-status 400 :error "clone-failed"})))
    dest))

(defn instantiate! [forge {:keys [name github pack conf mission]}]
  (let [dir-name (inferred-name name (boolean github))
        dest (project-dir forge dir-name)]
    (when (str/blank? dir-name)
      (throw (ex-info "Missing project name" {:http-status 400})))
    (when (str/blank? pack)
      (throw (ex-info "Missing pack" {:http-status 400})))
    (when-not (fs/directory? (pack-dir forge pack))
      (throw (ex-info (str "Unknown pack: " pack) {:http-status 400})))
    (when (fs/exists? dest)
      (throw (ex-info (str "Project already exists: " dir-name)
                      {:http-status 409 :error "exists"})))
    (fs/create-dirs (projects-dir forge))
    (if github
      (clone-github! (github-clone-url name) dest)
      (fs/create-dirs dest))
    (overlay-pack! forge dest pack false)
    (when-not (str/blank? conf)
      (fs/create-dirs (fs/path dest "swarmforge"))
      (spit (str (fs/path dest "swarmforge" "swarmforge.conf")) conf))
    (when-not (nil? mission)
      (spit (str (fs/path dest "mission.md"))
            (if (str/ends-with? (or mission "") "\n") mission (str mission "\n"))))
    (write-pack-name! dest pack)
    (when-not github
      (init-git-if-needed! dest))
    {:name dir-name :path (str dest)}))

(defn ensure-single-agent-role! [project]
  (let [file (fs/path project ".swarmforge" "roles.tsv")
        rows (when (fs/regular-file? file)
               (->> (str/split-lines (slurp (str file)))
                    (remove str/blank?)
                    vec))]
    (cond
      (= 1 (count rows)) true
      (seq rows)
      (throw (ex-info (str "Single-agent project must name exactly one role: " project)
                      {:http-status 500 :error "invalid-single-agent-role"}))
      :else
      (let [branch (or (not-empty
                        (str/trim (:out (sh {:continue true}
                                            "git" "-C" (str project)
                                            "branch" "--show-current"))))
                       "master")]
        (fs/create-dirs (fs/parent file))
        (spit (str file)
              (format "agent\t%s\t%s\tswarmforge-agent\tAgent\tcodex\ttask\n"
                      branch project))
        true))))

(defn refresh! [forge name]
  (let [dest (project-dir forge name)
        pack (stored-pack dest)]
    (when-not (fs/directory? dest)
      (throw (ex-info (str "Unknown project: " name) {:http-status 404})))
    (if (single-agent? dest)
      (do
        (copy-shared-scripts! forge dest)
        (copy-shared-articles! forge dest)
        (ensure-single-agent-role! dest)
        (let [installer (fs/path dest "swarmforge" "scripts" "install-agent-hooks.sh")]
          (when (fs/regular-file? installer)
            (process/sh {:continue true} "bash" (str installer) (str dest))))
        {:name name :single-agent true})
      (do
        (when (str/blank? pack)
          (throw (ex-info (str "No pack recorded for " name) {:http-status 400})))
        (overlay-pack! forge dest pack true)
        {:name name :pack pack}))))

(defn skip-start? []
  (= "1" (System/getenv "SWARMFORGE_SKIP_START")))

(defn swarmforge-bb [forge]
  (str (fs/path forge "swarmforge" "scripts" "swarmforge.bb")))

(def runtime-session "swarmforge-runtime")

(defn runtime-socket [project]
  (fs/path project ".swarmforge" "runtime-tmux.sock"))

(defn runtime-metadata-file [project]
  (fs/path project ".swarmforge" "runtime.edn"))

(defn runtime-log-file [project]
  (fs/path project ".swarmforge" "start.log"))

(defn tmux-session?
  [socket session]
  (and (fs/exists? socket)
       (zero? (:exit (process/sh {:continue true}
                                 "tmux" "-S" (str socket)
                                 "has-session" "-t" (str "=" session))))))

(defn runtime-running? [project]
  (tmux-session? (runtime-socket project) runtime-session))

(defn runtime-timeout-ms []
  (try
    (Long/parseLong (or (System/getenv "SWARMFORGE_RUNTIME_READY_TIMEOUT_MS") "45000"))
    (catch Exception _
      45000)))

(defn dashboard-ready? [project]
  (let [file (fs/path project ".swarmforge" "dashboard-url")
        url (when (fs/regular-file? file)
              (not-empty (str/trim (slurp (str file)))))]
    (when url
      (try
        ;; Babashka's SCI does not expose HttpURLConnection's response-code
        ;; methods. Reading the local dashboard page tests the same boundary:
        ;; it succeeds only when the HTTP server answers.
        (slurp (str url "/"))
        true
        (catch Exception _
          false)))))

(defn agent-session-ready? [project]
  (tmux-session? (fs/path project ".swarmforge" "agent-tmux.sock")
                 "swarmforge-agent"))

(defn project-tmux-socket [project]
  (let [file (fs/path project ".swarmforge" "tmux-socket")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn pack-sessions-ready? [project]
  (let [file (fs/path project ".swarmforge" "sessions.tsv")
        socket (project-tmux-socket project)
        sessions (when (fs/regular-file? file)
                   (->> (str/split-lines (slurp (str file)))
                        (remove str/blank?)
                        (map #(nth (str/split % #"\t" -1) 2 nil))
                        (remove str/blank?)
                        vec))]
    (boolean
     (and socket
          (seq sessions)
          (every? #(tmux-session? socket %) sessions)))))

(defn runtime-status [project]
  (let [single? (single-agent? project)]
    {:kind (if single? :single-agent :pack)
     :session runtime-session
     :socket (str (runtime-socket project))
     :relay (runtime-running? project)
     :dashboard (if single? (dashboard-ready? project) nil)
     :agent (if single? (agent-session-ready? project) (pack-sessions-ready? project))}))

(defn runtime-ready? [project]
  (let [{:keys [relay dashboard agent]} (runtime-status project)]
    (boolean
     (and relay
          (if (single-agent? project) (and dashboard agent) agent)))))

(defn wait-for-runtime-ready! [project timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (runtime-ready? project) true
        (or (not (runtime-running? project))
            (> (System/currentTimeMillis) deadline)) false
        :else (do (Thread/sleep 100) (recur))))))

(defn sleep-inhibitor-prefix []
  (when-not (= "0" (System/getenv "SWARMFORGE_PREVENT_SLEEP"))
    (case (str/trim (:out (process/sh {:continue true} "uname" "-s")))
      "Darwin" (when (fs/which "caffeinate")
                 ["caffeinate" "-dims"])
      "Linux" (when (and (fs/which "systemd-inhibit")
                         (fs/which "systemctl"))
                ["systemd-inhibit"
                 "--what=sleep:idle"
                 "--who=SwarmForge"
                 "--why=SwarmForge project is active"])
      nil)))

(defn runtime-log-tail [project]
  (let [log (runtime-log-file project)]
    (when (fs/regular-file? log)
      (->> (str/split-lines (slurp (str log)))
           (take-last 20)
           (str/join "\n")))))

(defn process-alive? [pid]
  (zero? (:exit (process/sh {:continue true} "kill" "-0" pid))))

(defn wait-for-exit [pid timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (not (process-alive? pid)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(defn project-dashboard-pid [project]
  (let [file (fs/path project ".swarmforge" "pack_web.pid")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn project-dashboard-process?
  "The project's PID file may remain after the relay dies. Only signal it when
  the command still names this project's dashboard, so a reused PID cannot make
  Close kill an unrelated process."
  [project pid]
  (and (process-alive? pid)
       (let [command (:out (process/sh {:continue true}
                                      "ps" "-p" pid "-o" "command="))]
         (and (str/includes? command "pack_web")
              (str/includes? command
                             (str "--serve " project))))))

(defn stop-project-dashboard! [project]
  (when-let [pid (project-dashboard-pid project)]
    (when (project-dashboard-process? project pid)
      (process/sh {:continue true} "kill" "-TERM" pid)
      (when-not (wait-for-exit pid 5000)
        (when (project-dashboard-process? project pid)
          (process/sh {:continue true} "kill" "-KILL" pid)
          (wait-for-exit pid 1000)))))
  (fs/delete-if-exists (fs/path project ".swarmforge" "pack_web.pid"))
  (fs/delete-if-exists (fs/path project ".swarmforge" "dashboard-url")))

(defn write-runtime-metadata! [project]
  (let [file (runtime-metadata-file project)]
    (fs/create-dirs (fs/parent file))
    (spit (str file)
          (pr-str {:kind (if (single-agent? project) :single-agent :pack)
                   :session runtime-session
                   :socket (str (runtime-socket project))
                   :log (str (runtime-log-file project))
                   :started-at (str (java.time.Instant/now))}))))

(defn stop-runtime-relay! [project]
  (let [socket (runtime-socket project)]
    (when (runtime-running? project)
      (process/sh {:continue true}
                  "tmux" "-S" (str socket)
                  "kill-session" "-t" (str "=" runtime-session)))
    (let [deadline (+ (System/currentTimeMillis) 5000)]
      (loop []
        (cond
          (not (runtime-running? project)) true
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info (str "Runtime relay did not stop for " project)
                          {:kind :runtime-stop-failed
                           :socket (str socket)
                           :session runtime-session}))
          :else (do (Thread/sleep 100) (recur)))))))

(declare stop-project-runtime!)

(defn start-project-runtime! [forge name]
  (let [dest (project-dir forge name)
        project (fs/path dest)
        socket (runtime-socket project)
        log (runtime-log-file project)]
    (if (and (runtime-running? project)
             (runtime-ready? project))
      {:name name :started false :reused true :status (runtime-status project)}
      (do
        (when (runtime-running? project)
          ;; A relay without the runtime it promises is a failed start, not a
          ;; project that can be marked open. Remove it before trying once more.
          (stop-project-runtime! forge name))
        (let [single? (single-agent? project)
              script (if single?
                       (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))
                       (str (swarmforge-bb forge)))
              agent-command (or (not-empty (System/getenv "SWARMFORGE_AGENT_COMMAND"))
                                (str "codex -C " (shell-quote dest)
                                     " --no-alt-screen"
                                     " --dangerously-bypass-approvals-and-sandbox"))
              start-command (str/join
                             " "
                             (map shell-quote
                                  (if single?
                                    ["bb" script "--root" dest
                                     "--agent-command" agent-command]
                                    ["bb" script "--start-project" dest])))
              tail (if single?
                     start-command
                     (str start-command "; status=$?; "
                          "if [ $status -ne 0 ]; then exit $status; fi; "
                          "while tmux -S " (shell-quote (str socket))
                          " has-session 2>/dev/null; do sleep 2; done"))
              command (str/join " "
                                (map shell-quote
                                     (into (or (sleep-inhibitor-prefix) [])
                                           ["sh" "-c" tail])))
              inner (str "cd " (shell-quote dest)
                         " && exec " command
                         " >> " (shell-quote (str log)) " 2>&1")]
          (fs/create-dirs (fs/parent log))
          (write-runtime-metadata! project)
          (let [started (process/sh {:continue true}
                                    "tmux" "-S" (str socket)
                                    "new-session" "-d" "-s" runtime-session
                                    "-c" dest inner)]
            (when-not (zero? (:exit started))
              (fs/delete-if-exists (runtime-metadata-file project))
              (throw (ex-info (str "Could not start project runtime: "
                                   (str/trim (str (:err started) (:out started))))
                              {:kind :runtime-start-failed
                               :name name
                               :log (str log)})))
          (when-not (wait-for-runtime-ready! project (runtime-timeout-ms))
            (let [status (runtime-status project)
                  tail (runtime-log-tail project)]
              (try
                (stop-project-runtime! forge name)
                (catch Exception _))
              (throw (ex-info (str "Project runtime did not become ready: " name
                                   "\nstatus: " (pr-str status)
                                   (when-not (str/blank? tail)
                                     (str "\n" tail)))
                              {:kind :runtime-not-ready
                               :name name
                               :status status
                               :log (str log)}))))
          {:name name :started true :reused false
           :status (runtime-status project)}))))))

(defn stop-project-runtime! [forge name]
  (let [dest (str (project-dir forge name))
        project (fs/path dest)
        runner (str (fs/path project "swarmforge" "scripts" "agent-runner.bb"))]
    (if (single-agent? project)
      (do
        (process/sh {:continue true} "bb" runner "--root" dest "--stop")
        (process/sh {:continue true} "bb" runner "--root" dest "--stop-agent"))
      (let [script (swarmforge-bb forge)]
        (process/sh {:continue true} "bb" script "--stop-project" dest)))
    (stop-runtime-relay! project)
    (stop-project-dashboard! project)
    (fs/delete-if-exists (runtime-metadata-file project))
    {:name name :running false}))

(defn open-project! [forge name]
  (let [project (project-dir forge name)]
    (when-not (fs/directory? project)
      (throw (ex-info (str "Unknown project: " name) {:http-status 404})))
    (when (and (project-open? forge name)
               (or (skip-start?) (runtime-ready? project)))
      (throw (ex-info (str "Project already open: " name)
                      {:http-status 409 :error "already-open"})))
    ;; An open marker with no ready runtime is a crash to reconcile, not a
    ;; reason to leave the project permanently open and unreachable.
    (when (project-open? forge name)
      (stop-project-runtime! forge name))
    (refresh! forge name)
    (when-not (skip-start?)
      (start-project-runtime! forge name))
    (mark-open! forge name)
    {:name name :open true}))

(defn close-project! [forge name]
  (when-not (skip-start?)
    (stop-project-runtime! forge name))
  (mark-closed! forge name)
  {:name name :open false})

(defn close-all-projects! [forge]
  (doseq [name (read-open-projects forge)]
    (close-project! forge name)))
