#!/usr/bin/env bb

(ns agent-runner
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  agent-runner.bb --root <project> [--agent-command CMD] [--once]\n"
       "                  [--no-serve] [--interval SECONDS]\n"
       "  agent-runner.bb --root <project> --stop\n"
       "  agent-runner.bb --root <project> --stop-agent\n\n"
       "--stop closes the relay and the agent session; --stop-agent closes only\n"
       "the agent session.\n\n"
       "The relay owns one persistent tmux session, swarmforge-agent. Phone\n"
       "requests are pasted into that session; forge-attach opens the same\n"
       "session. The agent must end every turn with <<AGENT READY>>."))

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
         options {:interval 2}]
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

(declare stop-agent!)

(defn stop! [root]
  (when-let [pid (running-pid root)]
    (let [result (sh/sh "kill" pid)]
      (if (zero? (:exit result))
        (println (str "Stopped agent runner " pid))
        (println (str "Agent runner " pid " is not running")))))
  (stop-agent! root))

(defn dashboard-url [root]
  (let [file (fs/path root ".swarmforge" "dashboard-url")]
    (when (fs/regular-file? file)
      (not-empty (str/trim (slurp (str file)))))))

(defn dashboard-up? [url]
  (try
    (let [connection (.openConnection (java.net.URL. (str url "/api/state")))]
      (.setConnectTimeout connection 1000)
      (.setReadTimeout connection 1000)
      (with-open [_ (.getInputStream connection)] true))
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

(defn pending-request [root]
  (->> (request-files root)
       (map parse-request)
       (filter #(= "pending" (:status %)))
       first))

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

(defn pane [root]
  (let [result (tmux root "capture-pane" "-p" "-J" "-S" "-4000"
                     "-t" session-name)]
    (when (zero? (:exit result))
      (:out result))))

(defn visible-pane [root]
  (let [result (tmux root "capture-pane" "-p" "-J" "-t" session-name)]
    (when (zero? (:exit result))
      (:out result))))

(defn ready? [root]
  (str/includes? (or (pane root) "") ready-marker))

(defn default-agent-command [root]
  (str "codex -C " (sq (str root))
       " --no-alt-screen --dangerously-bypass-approvals-and-sandbox"))

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
    (settle-agent-session! root)))

(defn settle-agent-session! [root]
  (loop [waited 0]
    (let [text (or (visible-pane root) "")]
      (cond
        (or (str/includes? text ready-marker)
            (str/includes? text "Ask Codex to do anything")) true

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
  (let [marker (str "[phone " (:id request) "]")
        text (str marker " " (:body request))]
    (tmux root "set-buffer" "-b" "agent-request" text)
    (tmux root "paste-buffer" "-d" "-p" "-b" "agent-request"
          "-t" session-name)
    (tmux root "send-keys" "-t" session-name "C-m")
    marker))

(defn answer-since [text marker]
  (when-let [start (str/last-index-of text marker)]
    (let [after (subs text (+ start (count marker)))
          end (str/index-of after ready-marker)]
      (when end
        (str/trim (subs after 0 end))))))

(defn wait-for-answer! [root marker timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [text (or (pane root) "")
            answer (answer-since text marker)]
        (cond
          (some? answer) answer
          (> (System/currentTimeMillis) deadline)
          (str "The agent did not signal " ready-marker " within "
               (quot timeout-ms 1000) "s. Last pane lines:\n"
               (str/join "\n" (take-last 20 (str/split-lines text))))
          :else
          (do
            (Thread/sleep 250)
            (recur)))))))

(defn answer-request! [root request text]
  (let [answer-dir (fs/path root ".swarmforge" "agent-runner")
        answer-file (fs/path answer-dir (str (:id request) ".answer"))]
    (fs/create-dirs answer-dir)
    (spit (str answer-file) text)
    (let [script (fs/path root "swarmforge" "scripts" "pack_dashboard_request.bb")
          result (sh/sh "bb" (str script) "answer" (:id request) (str answer-file)
                        :dir (str root))]
      (when-not (zero? (:exit result))
        (exit! 1 (str "Could not answer " (:id request) ": "
                      (:err result) (:out result))))
      (println "answered" (:id request)))))

(defn run-once! [root agent-command]
  (start-agent-session! root agent-command)
  (settle-agent-session! root)
  (if-let [request (pending-request root)]
    (do
      (println "request" (:id request))
      (wait-until-ready! root 5000)
      (let [marker (send-request! root request)
            answer (wait-for-answer! root marker 600000)]
        (answer-request! root request answer)))
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
                (run-once! root agent-command)
                (loop []
                  (run-once! root agent-command)
                  (Thread/sleep (* 1000 (:interval options)))
                  (recur)))))))))))

(apply -main *command-line-args*)
