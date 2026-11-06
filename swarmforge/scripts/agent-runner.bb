#!/usr/bin/env bb

(ns agent-runner
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  agent-runner.bb --root <project> [--agent-command CMD] [--once]\n"
       "                  [--no-serve] [--interval SECONDS]\n"
       "                  [--idle-nudge-seconds SECONDS]\n"
       "                  [--nudge-cooldown-seconds SECONDS]\n"
       "  agent-runner.bb --root <project> --stop\n"
       "  agent-runner.bb --root <project> --stop-agent\n\n"
       "--stop closes the relay and the agent session; --stop-agent closes only\n"
       "the agent session.\n\n"
       "The relay owns one persistent tmux session, swarmforge-agent. Phone\n"
       "requests are pasted into that session with an Answer with instruction;\n"
       "the agent writes ./tmp/answer.txt and answers through the dashboard\n"
       "tool. forge-attach opens the same session. The agent must end every\n"
       "turn with <<AGENT READY>>.\n\n"
       "A request is pasted once. If its agent goes idle without answering, the\n"
       "relay sends a short continuation nudge by request id after the idle\n"
       "interval, rather than repasting the original task."))

(defn usage []
  (println usage-text))

(defn exit! [status & messages]
  (doseq [message messages]
    (when message
      (binding [*out* *err*]
        (println message))))
  (System/exit status))

(defn parse-args [args]
  (loop [remaining args
         options {:interval 2
                  :idle-nudge-seconds 300
                  :nudge-cooldown-seconds 900}]
    (if (empty? remaining)
      options
      (let [argument (first remaining)]
        (cond
          (= argument "--root")
          (if-let [value (second remaining)]
            (recur (nnext remaining) (assoc options :root value))
            (exit! 1 "--root needs a value"))

          (= argument "--agent-command")
          (if-let [value (second remaining)]
            (recur (nnext remaining) (assoc options :agent-command value))
            (exit! 1 "--agent-command needs a value"))

          (= argument "--interval")
          (if-let [value (second remaining)]
            (recur (nnext remaining) (assoc options :interval (Long/parseLong value)))
            (exit! 1 "--interval needs a value"))

          (= argument "--idle-nudge-seconds")
          (if-let [value (second remaining)]
            (recur (nnext remaining)
                   (assoc options :idle-nudge-seconds (Long/parseLong value)))
            (exit! 1 "--idle-nudge-seconds needs a value"))

          (= argument "--nudge-cooldown-seconds")
          (if-let [value (second remaining)]
            (recur (nnext remaining)
                   (assoc options :nudge-cooldown-seconds (Long/parseLong value)))
            (exit! 1 "--nudge-cooldown-seconds needs a value"))

          (= argument "--once")
          (recur (next remaining) (assoc options :once true))

          (= argument "--no-serve")
          (recur (next remaining) (assoc options :no-serve true))

          (= argument "--stop")
          (recur (next remaining) (assoc options :stop true))

          (= argument "--stop-agent")
          (recur (next remaining) (assoc options :stop-agent true))

          (some #(= argument %) ["-h" "--help"])
          (assoc options :help true)

          :else
          (exit! 1 (str "Unknown argument: " argument)))))))

(defn root-dir [options]
  (let [root (fs/absolutize (or (:root options) "."))]
    (when-not (fs/directory? root)
      (exit! 1 (str "Project root not found: " root)))
    (when-not (fs/regular-file? (fs/path root ".swarmforge" "single-agent"))
      (exit! 1 (str "Not a single-agent project: " root)))
    root))

(defn pid-file [root]
  (fs/path root ".swarmforge" "agent-runner.pid"))

(defn running-pid [root]
  (let [file (pid-file root)]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn runner-process? [root pid]
  (and (zero? (:exit (sh/sh {:continue true} "kill" "-0" pid)))
       (let [command (:out (sh/sh {:continue true}
                                  "ps" "-p" pid "-o" "command="))]
         (and (str/includes? command "agent-runner")
              (str/includes? command (str "--root " root))))))

(declare stop-agent!)

(defn stop! [root]
  (when-let [pid (running-pid root)]
    (if (runner-process? root pid)
      (let [result (sh/sh {:continue true} "kill" pid)]
        (if (zero? (:exit result))
          (println (str "Stopped agent runner " pid))
          (println (str "Agent runner " pid " is not running"))))
      (println (str "Ignored stale agent runner PID " pid)))
    (fs/delete-if-exists (pid-file root)))
  (stop-agent! root))

(defn dashboard-url [root]
  (let [file (fs/path root ".swarmforge" "dashboard-url")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn dashboard-up? [url]
  (try
    ;; This predicate runs inside Babashka's SCI, which does not permit the
    ;; HttpURLConnection response/timeout methods. Reading the project page is
    ;; the same liveness boundary the bridge uses, and a dead local address
    ;; fails without waiting.
    (slurp (str url "/"))
    true
    (catch Exception _ false)))

(defn start-dashboard! [root]
  (let [script (fs/path root "swarmforge" "scripts" "pack_web.bb")]
    (when-not (fs/regular-file? script)
      (exit! 1 (str "pack_web.bb not found in " root)))
    (let [dashboard (process/process
                     {:dir (str root) :out :inherit :err :inherit}
                     "bb" (str script) "--serve" (str root))]
      (loop [waited 0]
        (cond
          (dashboard-url root) dashboard
          (>= waited 100)
          (do
            (process/destroy-tree dashboard)
            (exit! 1 "pack_web did not write .swarmforge/dashboard-url"))
          :else
          (do
            (Thread/sleep 200)
            (recur (inc waited))))))))

(defn ensure-dashboard! [root options]
  (when-not (:no-serve options)
    (let [url (dashboard-url root)]
      (if (and url (dashboard-up? url))
        nil
        (start-dashboard! root)))))

(defn request-files [root]
  (let [dir (fs/path root ".swarmforge" "dashboard" "requests" "pending")]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(str/ends-with? (fs/file-name %) ".request"))
           (sort-by str)
           vec)
      [])))

(defn parse-request [file]
  (let [text (slurp (str file))
        [headers body] (str/split text #"\n\n" 2)
        entries (into {}
                      (for [line (str/split-lines headers)
                            :let [[key value] (str/split line #":\s*" 2)]
                            :when (and key value)]
                        [key value]))]
    {:id (or (get entries "id")
             (str/replace (fs/file-name file) #"\.request$" ""))
     :status (get entries "status")
     :body (or body "")}))

(defn pending-requests [root]
  (->> (request-files root)
       (map parse-request)
       (filter #(= "pending" (:status %)))
       vec))

(defn pending-request [root]
  (first (pending-requests root)))

(def session-name "swarmforge-agent")
(def ready-marker "<<AGENT READY>>")

(defn sq [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn agent-socket [root]
  (fs/path root ".swarmforge" "agent-tmux.sock"))

(defn socket-file [root]
  (fs/path root ".swarmforge" "tmux-socket"))

(defn delete-socket-file! [root]
  (let [file (socket-file root)]
    (when (fs/exists? file)
      (if (fs/regular-file? file)
        (fs/delete-if-exists file)
        (sh/sh "unlink" (str file))))))

(defn tmux [root & args]
  (apply sh/sh "tmux" "-S" (str (agent-socket root)) args))

(defn session? [root]
  (zero? (:exit (tmux root "has-session" "-t" session-name))))

(defn visible-pane [root]
  (let [result (tmux root "capture-pane" "-p" "-J" "-t" session-name)]
    (when (zero? (:exit result))
      (:out result))))

(defn busy? [text]
  (let [text (str/lower-case (or text ""))]
    (or (str/includes? text "esc to interrupt")
        (str/includes? text "waiting for background terminal"))))

(declare request-in-composer?)

(defn ready?
  ([root] (ready? root nil))
  ([root request-id]
  ;; Scrollback is not readiness. Once a previous turn left its ready marker
  ;; above the visible screen, that old marker says nothing about this moment;
  ;; Codex's current pane says it is busy explicitly while a turn runs. A
  ;; request already in the idle composer replaces the placeholder that would
  ;; otherwise say "Ask Codex to do anything", so that request is readiness too.
  (let [text (or (visible-pane root) "")]
    (and (not (busy? text))
         (or (str/includes? text ready-marker)
             (str/includes? text "Ask Codex to do anything")
             (and request-id
                  (request-in-composer? root request-id)))))))

(defn default-agent-command [root]
  (let [roles-file (fs/path root ".swarmforge" "roles.tsv")
        agents-file (or (some #(when (fs/regular-file? %)
                                 (fs/path % "agents.conf"))
                              [(fs/path (fs/parent *file*))])
                        (fs/path root "swarmforge" "scripts" "agents.conf"))
        supported (when (fs/regular-file? agents-file)
                    (->> (str/split-lines (slurp (str agents-file)))
                         (remove str/blank?)
                         set))
        row (when (fs/regular-file? roles-file)
              (some->> (str/split-lines (slurp (str roles-file)))
                       (remove str/blank?)
                       first
                       (#(str/split % #"\t"))))
        agent (nth row 5 nil)]
    (when-not row
      (exit! 1 (str "Single-agent project has no agent role: " root)))
    (when-not (contains? supported agent)
      (exit! 1 (str "Unsupported or missing agent '" agent "' in " roles-file)))
    (case agent
      "codex" (str "codex -C " (sq (str root))
                   " --no-alt-screen --dangerously-bypass-approvals-and-sandbox")
      "claude" "env CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 claude --permission-mode bypassPermissions -n 'SwarmForge Agent'"
      "copilot" (str "copilot -C " (sq (str root))
                     " --no-alt-screen --name 'SwarmForge Agent' --yolo")
      "grok" (str "grok --cwd " (sq (str root))
                  " --permission-mode bypassPermissions --minimal"))))

(defn name-agent-window! [root]
  (let [window (str session-name ":0")
        project (str (fs/file-name root))]
    ;; The project is a stable label in the attached status line. Without
    ;; automatic-rename off, tmux replaces it with whatever process is active
    ;; in the pane - normally "codex", which says nothing about the project.
    (tmux root "set-window-option" "-t" window "automatic-rename" "off")
    (tmux root "set-window-option" "-t" window "allow-rename" "off")
    (tmux root "rename-window" "-t" window project)))

(declare settle-agent-session!)

(defn start-agent-session! [root command]
  (when-not (session? root)
    (fs/create-dirs (fs/path root ".swarmforge"))
    (when (and (fs/exists? (socket-file root))
               (not (fs/regular-file? (socket-file root))))
      (delete-socket-file! root))
    (spit (str (socket-file root)) (str (agent-socket root) "\n"))
    (let [result (tmux root "new-session" "-d" "-s" session-name
                       "-c" (str root)
                       (str "export PATH=" (sq (str (fs/path root ".swarmforge" "bin")
                                                    ":"
                                                    (fs/path root "swarmforge" "scripts")
                                                    ":"
                                                    (or (System/getenv "PATH") "")))
                            "; exec " command))]
      (when-not (zero? (:exit result))
        (exit! 1 "Could not start the agent session: " (:err result))))
    (settle-agent-session! root))
  ;; Also correct a session that predates the project-name behavior. The
  ;; persistent session survives a relay restart, so naming only a new session
  ;; would leave an upgraded project showing "codex" until it was killed.
  (name-agent-window! root))

(defn settle-agent-session! [root]
  (loop [waited 0]
    (let [text (or (visible-pane root) "")]
      (cond
        (ready? root (some-> (pending-request root) :id)) true

        (str/includes? text "Trust this folder?")
        (do
          (tmux root "send-keys" "-t" session-name "1" "C-m")
          (Thread/sleep 500)
          (recur (+ waited 500)))

        (and (str/includes? text "Update available")
             (str/includes? text "1. Update now"))
        (do
          (tmux root "send-keys" "-t" session-name "3" "C-m")
          (Thread/sleep 500)
          (recur (+ waited 500)))

        (>= waited 30000) false

        :else
        (do
          (Thread/sleep 250)
          (recur (+ waited 250)))))))

(defn stop-agent! [root]
  (if (session? root)
    (do
      (tmux root "kill-session" "-t" session-name)
      (println "Stopped agent session" session-name))
    (println "Agent session is not running"))
  (delete-socket-file! root))

(defn wait-until-ready! [root timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (ready? root) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 250) (recur))))))

(defn send-request! [root request]
  (let [id (:id request)
        text (str "[phone " id "] " (:body request) "\n\n"
                  "Answer with: "
                  (fs/path root "swarmforge" "scripts" "pack_dashboard_request.sh")
                  " answer " id " ./tmp/answer.txt\n"
                  "Write only the operator-facing answer to ./tmp/answer.txt. "
                  "Your terminal output is not sent to the phone.")]
    (tmux root "set-buffer" "-b" "agent-request" text)
    (tmux root "paste-buffer" "-d" "-p" "-b" "agent-request"
          "-t" session-name)
    ;; Give the terminal UI time to consume the bracketed paste before sending
    ;; Enter. Sending both back-to-back lets the paste handler swallow Enter and
    ;; leaves the request silently waiting in the composer.
    (Thread/sleep 200)
    (tmux root "send-keys" "-t" session-name "C-m")
    id))

(defn composer-tail [root]
  (let [lines (vec (str/split-lines (or (visible-pane root) "")))
        prompt-index (last
                      (keep-indexed
                       (fn [index line]
                         (when (str/starts-with? (str/triml line) "›")
                           index))
                       lines))]
    (if (some? prompt-index)
      (str/join "\n" (subvec lines prompt-index))
      "")))

(def collapsed-paste-pattern #"\[Pasted Content \d+ chars?\]")

(defn collapsed-paste? [text]
  (boolean (re-find collapsed-paste-pattern (or text ""))))

(defn sole-pending-request? [root id]
  (= [id] (mapv :id (pending-requests root))))

(defn request-in-composer? [root id]
  (let [tail (composer-tail root)]
    (or (str/includes? tail (str "[phone " id "]"))
        (and (collapsed-paste? tail)
             (sole-pending-request? root id)))))

(defn delivery-state-dir [root]
  (fs/path root ".swarmforge" "agent-runner" "requests"))

(defn delivery-state-file [root id]
  (fs/path (delivery-state-dir root)
           (str (str/replace (str id) #"[^0-9A-Za-z._-]" "_") ".edn")))

(defn delivery-state [root id]
  (let [file (delivery-state-file root id)]
    (when (fs/regular-file? file)
      (try
        (edn/read-string (slurp (str file)))
        (catch Exception _ nil)))))

(defn write-delivery-state! [root id state]
  (let [file (delivery-state-file root id)
        temporary (fs/path (fs/parent file)
                           (str (fs/file-name file) ".tmp"))]
    (fs/create-dirs (fs/parent file))
    (spit (str temporary) (str (pr-str state) "\n"))
    (fs/move temporary file {:replace-existing true})))

(defn pane-digest [text]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (or text "") "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn note-activity! [root id state text]
  (let [now (System/currentTimeMillis)
        digest (pane-digest text)
        busy (busy? text)
        changed (not= digest (:pane-digest state))
        last-activity (long (or (:last-activity-at state) 0))]
    (if (or (and busy (>= now (+ last-activity 30000)))
            (and (not busy) changed))
      (let [updated (assoc state
                           :pane-digest digest
                           :last-activity-at now)]
        (write-delivery-state! root id updated)
        updated)
      state)))

(defn mark-delivered! [root request text]
  (let [now (System/currentTimeMillis)
        state {:delivered-at now
               :last-activity-at now
               :pane-digest (pane-digest text)}]
    (write-delivery-state! root (:id request) state)
    state))

(defn request-visible? [root id]
  (str/includes? (or (visible-pane root) "")
                 (str "[phone " id "]")))

(defn wait-until-submitted! [root id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (not (request-in-composer? root id)) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do
                (Thread/sleep 200)
                (recur))))))

(defn deliver-request! [root request]
  (let [id (:id request)]
    (if (request-in-composer? root id)
      ;; The earlier paste reached the composer but its Enter did not submit it.
      ;; Submitting the text already there is safer than pasting a second copy.
      (tmux root "send-keys" "-t" session-name "C-m")
      (send-request! root request))
    (wait-until-submitted! root id 5000)))

(defn request-done? [root id]
  (fs/regular-file?
   (fs/path root ".swarmforge" "dashboard" "requests" "done"
            (str id ".request"))))

(defn continuation-text [root id]
  (str "[phone " id "] Continuation nudge. The routed card is still open. "
       "Continue from the current card, task note and stage state; do not "
       "restart completed work. If blocked, use the clarification channel. "
       "When finished, answer with: "
       (fs/path root "swarmforge" "scripts" "pack_dashboard_request.sh")
       " answer " id " ./tmp/answer.txt. Write only the operator-facing "
       "answer to ./tmp/answer.txt."))

(defn send-continuation! [root id]
  (let [text (continuation-text root id)]
    (tmux root "set-buffer" "-b" "agent-nudge" text)
    (tmux root "paste-buffer" "-d" "-p" "-b" "agent-nudge"
          "-t" session-name)
    (Thread/sleep 200)
    (tmux root "send-keys" "-t" session-name "C-m")
    (wait-until-submitted! root id 5000)))

(defn handle-delivered-request! [root request options]
  (let [id (:id request)
        text (or (visible-pane root) "")
        state (note-activity! root id (delivery-state root id) text)
        now (System/currentTimeMillis)
        last-activity (long (or (:last-activity-at state) now))
        last-nudge (long (or (:last-nudge-at state) 0))
        idle-ms (* 1000 (long (:idle-nudge-seconds options)))
        cooldown-ms (* 1000 (long (:nudge-cooldown-seconds options)))]
    (if (and (not (busy? text))
             (>= (- now last-activity) idle-ms)
             (>= (- now last-nudge) cooldown-ms))
      (do
        (println "nudging delivered request after inactivity:" id)
        (send-continuation! root id)
        (write-delivery-state! root id
                               (assoc state
                                      :last-activity-at (System/currentTimeMillis)
                                      :last-nudge-at now)))
      (println "request" id "already delivered; monitoring the agent"))))

(defn run-once! [root agent-command options]
  (start-agent-session! root agent-command)
  (settle-agent-session! root)
  (if-let [request (pending-request root)]
    (let [id (:id request)
          state (delivery-state root id)]
      (if-not (ready? root id)
        (do
          (when state
            (note-activity! root id state (or (visible-pane root) "")))
          (println "request" id "waits: the agent is still in a turn"))
        (do
          (cond
            (request-in-composer? root id)
            (do
              (println "submitting request already in the composer:" id)
              (when (deliver-request! root request)
                (mark-delivered! root request (or (visible-pane root) ""))))

            state
            (handle-delivered-request! root request options)

            (request-visible? root id)
            (do
              (println "request already visible in the transcript:" id)
              (mark-delivered! root request (or (visible-pane root) "")))

            :else
            (do
              (println "request" id)
              (if (deliver-request! root request)
                (do
                  (mark-delivered! root request (or (visible-pane root) ""))
                  (println "delivered" id))
                (println "paste did not submit; the next pass will submit it:"
                         id)))))))
    (println "no pending requests")))

(defn -main [& args]
  (let [options (parse-args args)]
    (cond
      (:help options) (usage)
      :else
      (let [root (root-dir options)]
        (if (:stop options)
          (stop! root)
          (if (:stop-agent options)
            (stop-agent! root)
          (let [agent-command (or (:agent-command options)
                                  (not-empty (System/getenv "SWARMFORGE_AGENT_COMMAND"))
                                  (default-agent-command root))]
            (fs/create-dirs (fs/path root ".swarmforge"))
            (spit (str (pid-file root))
                  (str (.pid (java.lang.ProcessHandle/current)) "\n"))
            (let [dashboard (ensure-dashboard! root options)]
              (.addShutdownHook
               (Runtime/getRuntime)
               (Thread.
                (fn []
                  (when dashboard
                    (process/destroy-tree dashboard)
                    (fs/delete-if-exists
                     (fs/path root ".swarmforge" "pack_web.pid")))
                  (fs/delete-if-exists (pid-file root)))))
              (if (:once options)
                (run-once! root agent-command options)
                (loop []
                  (run-once! root agent-command options)
                  (Thread/sleep (* 1000 (:interval options)))
                  (recur)))))))))))

(apply -main *command-line-args*)
