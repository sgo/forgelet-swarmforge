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
       "  agent-runner.bb --root <project> --stop\n\n"
       "The agent command runs in the project root with the dashboard request\n"
       "on stdin. Its stdout is the answer. The runner then runs `stage list`\n"
       "and answers the request with both."))

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

(defn stop! [root]
  (if-let [pid (running-pid root)]
    (let [result (sh/sh "kill" pid)]
      (if (zero? (:exit result))
        (println (str "Stopped agent runner " pid))
        (exit! 1 (str "Could not stop " pid ": " (:err result)))))
    (println "Agent runner is not running")))

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

(defn run-agent [root command body]
  (println "$" command)
  (let [result (sh/sh "sh" "-c" command :dir (str root) :in body)]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(defn agent-input [body]
  (str "You are the single agent for this project. Read STAGES.md and follow "
       "it. Work in this checkout; do not create worktrees or hand work to "
       "another role. Your stdout is the answer to the operator.\n\n"
       body))

(defn stage-list [root]
  (let [script (fs/path root "swarmforge" "scripts" "stage.bb")]
    (if (fs/regular-file? script)
      (let [result (sh/sh "bb" (str script) "list" "--root" (str root))]
        (str (:out result) (:err result)))
      "stage tool not found\n")))

(defn answer-text [result stage-status]
  (str (if (zero? (:exit result))
         (:out result)
         (str "Agent command failed with exit " (:exit result) "\n\n"
              (:out result) "\n" (:err result)))
       "\n\n--- stage list ---\n"
       stage-status))

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
  (if-let [request (pending-request root)]
    (do
      (println "request" (:id request))
      (let [result (run-agent root agent-command (agent-input (:body request)))
            text (answer-text result (stage-list root))]
        (answer-request! root request text)))
    (println "no pending requests")))

(defn -main [& args]
  (let [options (parse-args args)]
    (cond
      (:help options) (usage)
      :else
      (let [root (root-dir options)]
        (if (:stop options)
          (stop! root)
          (let [agent-command (or (:agent-command options)
                                  (not-empty (System/getenv "SWARMFORGE_AGENT_COMMAND"))
                                  "codex exec --full-auto")]
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
                  (recur))))))))))

(apply -main *command-line-args*)
