(ns swarmforge.agent-runner-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(load-file (str (fs/path (fs/cwd) "swarmforge" "scripts" "agent-runner.bb")))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn write-stage-job!
  [root {:keys [id card-id heartbeat-age-ms pid process-start-identity
                consumed?]
         :or {heartbeat-age-ms 0
              consumed? false}}]
  (let [now (java.time.Instant/now)
        pid (or pid (.pid (java.lang.ProcessHandle/current)))
        start-identity (or process-start-identity
                           (stage-jobs/process-start-identity pid))
        job {:status "running"
             :role "agent"
             :project (str (fs/file-name root))
             :stage "code"
             :card card-id
             :card_name card-id
             :card_id card-id
             :requested_lane "spec"
             :pid pid
             :command_pid pid
             :process_start_identity start-identity
             :command_start_identity start-identity
             :command_fingerprint (str "fixture-" card-id)
             :created_at (str now)
             :heartbeat_at (str (.minusMillis now (long heartbeat-age-ms)))
             :progress "command 1/1 started"
             :timeout_seconds 60}]
    (write-file (stage-jobs/log-file root id) "[stage-job] command 1/1 started\n")
    (stage-jobs/atomic-write-edn!
     (stage-jobs/job-file root id)
     (cond-> (assoc job :id id)
       consumed? (assoc :consumed_at (str now))))
    (assoc job :id id)))

(defn write-delivered-request!
  [root request pane idle-ago-ms]
  (let [now (System/currentTimeMillis)]
    (agent-runner/write-delivery-state!
     root (:id request)
     {:delivered-at (- now idle-ago-ms)
      :last-activity-at (- now idle-ago-ms)
      :pane-digest (agent-runner/pane-digest pane)})))

(defn run-delivered-handler!
  [root request pane]
  (let [calls (atom [])]
    (with-redefs [agent-runner/visible-pane (constantly pane)
                  agent-runner/tmux
                  (fn [_ & args]
                    (swap! calls conj (vec args))
                    {:exit 0})]
      (let [out (with-out-str
                  (agent-runner/handle-delivered-request!
                   root request
                   {:idle-nudge-seconds 1
                    :nudge-cooldown-seconds 0}))]
        {:calls @calls
         :out out
         :state (agent-runner/delivery-state root (:id request))}))))

(def current-trust-prompt
  (str " Quick safety check: Is this a project you created or one you trust? (Like your\n"
       " own code, a well-known open source project, or work from your team). If not,\n"
       " take a moment to review what's in this folder first.\n"
       "\n"
       " Claude Code'll be able to read, edit, and execute files here.\n"
       "\n"
       " Security guide\n"
       "\n"
       " ❯ No, exit\n"
       "   Yes, I trust this folder\n"
       "\n"
       " Enter to confirm · Esc to cancel\n"))

(def old-trust-prompt
  (str "Trust this folder?\n"
       "\n"
       "❯ 1. Yes\n"
       "  2. No\n"))

(def unrelated-prompt
  (str "Quick safety check: Is this a project you created or one you trust?\n"
       "\n"
       "❯ 1. Open settings\n"
       "  2. Exit\n"))

(def logged-out-pane
  (str "❯ Try \"create a util logging.py that…\"\n"
       "  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents\n"
       "  Not logged in · run /login\n"))

(defn settle-with-pane!
  ([initial-pane] (settle-with-pane! initial-pane "Ask Claude to do anything"))
  ([initial-pane ready-text]
   (let [pane (atom initial-pane)
         sent (atom [])]
     (binding [agent-runner/*settle-timeout-ms* 100
               agent-runner/*settle-iteration-ms* 10
               agent-runner/*settle-prompt-delay-ms* 10]
       (with-redefs [agent-runner/visible-pane (fn [_] @pane)
                     agent-runner/ready?
                     (fn [_ _] (str/includes? @pane ready-text))
                     agent-runner/pending-request (constantly nil)
                     agent-runner/tmux
                     (fn [_ & args]
                       (swap! sent conj (vec args))
                       (when (= "send-keys" (first args))
                         (reset! pane ready-text))
                       {:exit 0})]
         {:ready? (agent-runner/settle-agent-session! "/fixture")
          :sent @sent})))))

(deftest only-the-known-claude-folder-trust-prompts-are-selected
  (testing "the current two-line wording selects its visible affirmative action"
    (is (= ["Down" "C-m"]
           (agent-runner/folder-trust-choice current-trust-prompt)))
    (is (= {:ready? true
            :sent [["send-keys" "-t" "swarmforge-agent" "Down" "C-m"]]}
           (settle-with-pane! current-trust-prompt))))

  (testing "the retained legacy wording keeps selecting the affirmative action"
    (is (= ["1" "C-m"]
           (agent-runner/folder-trust-choice old-trust-prompt)))
    (is (= {:ready? true
            :sent [["send-keys" "-t" "swarmforge-agent" "1" "C-m"]]}
           (settle-with-pane! old-trust-prompt))))

  (testing "the current Claude composer placeholder is a ready marker"
    (is (= {:ready? true :sent []}
           (settle-with-pane! "❯ Try \"refactor <filepath>\""
                              "Try \"refactor <filepath>\"")))
    (is (= {:ready? true :sent []}
           (settle-with-pane! "❯ Try \"how do I log an error?\""
                              "Try \"how do I log an error?\"")))
    (is (= {:ready? true :sent []}
           (settle-with-pane! "❯ Try \"create a util logging.py that…"
                              "Try \"create a util logging.py that…")))
    (with-redefs [agent-runner/visible-pane
                  (constantly "❯ Try \"create a util logging.py that…\n")]
      (is (agent-runner/ready? "/fixture")))
    (with-redefs [agent-runner/visible-pane
                  (constantly "❯ Try \"fix lint errors\"\n")]
      (is (agent-runner/ready? "/fixture")))
    (with-redefs [agent-runner/visible-pane
                  (constantly "Ask Claude to do anything\n")]
      (is (agent-runner/ready? "/fixture")))
    (with-redefs [agent-runner/visible-pane
                  (constantly "Ask Codex to do anything\n")]
      (is (agent-runner/ready? "/fixture")))
    (with-redefs [agent-runner/visible-pane
                  (constantly "Try \"not a Claude prompt\"\n")]
      (is (not (agent-runner/ready? "/fixture")))))

  (testing "the exact logged-out banner fails immediately without keystrokes"
    (with-redefs [agent-runner/visible-pane (constantly logged-out-pane)]
      (is (not (agent-runner/ready? "/fixture")))
      (is (str/includes?
           (agent-runner/claude-login-required-diagnostic logged-out-pane)
           "Claude is not logged in")))
    (is (= {:ready? false :sent []}
           (settle-with-pane! logged-out-pane))))

  (testing "an unrelated safety prompt remains unsent and fails"
    (is (nil? (agent-runner/folder-trust-choice unrelated-prompt)))
    (is (= {:ready? false :sent []}
           (settle-with-pane! unrelated-prompt))))

  (testing "the current question without its affirmative action is not trusted"
    (let [prompt (str "Quick safety check: Is this a project you created or one you trust?\n"
                      "Claude Code'll be able to read, edit, and execute files here.\n"
                      "\n"
                      "❯ 1. Review files\n")]
      (is (nil? (agent-runner/folder-trust-choice prompt)))
      (is (= {:ready? false :sent []}
             (settle-with-pane! prompt))))))

(deftest logged-out-sessions-stay-attachable-and-do-not-deliver-work
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-login-required."})
        marker (agent-runner/login-required-file root)]
    (try
      (fs/create-dirs (fs/path root ".swarmforge"))
      (write-file (fs/path root ".swarmforge" "single-agent") "agent\n")
      (with-redefs [agent-runner/session? (constantly true)
                    agent-runner/name-agent-window! (fn [_] nil)
                    agent-runner/settle-agent-session-state!
                    (constantly :login-required)
                    agent-runner/visible-pane (constantly logged-out-pane)
                    agent-runner/stop-agent!
                    (fn [_] (throw (ex-info "must keep session" {})))]
        (is (= :login-required (agent-runner/start-agent-session! root "claude")))
        (is (fs/regular-file? marker)))

      (testing "pending work is held while login is required"
        (with-redefs [agent-runner/start-agent-session!
                      (constantly :login-required)
                      agent-runner/pending-request
                      (constantly {:id "held-request"})
                      agent-runner/deliver-request!
                      (fn [_ _] (throw (ex-info "must not deliver" {})))]
          (let [output (with-out-str
                         (agent-runner/run-once! root "claude" {}))]
            (is (str/includes? output
                               "work requests are held until Claude login completes")))))

      (testing "the same pane becomes ready after authentication"
        (let [pane (atom logged-out-pane)]
          (with-redefs [agent-runner/visible-pane (fn [_] @pane)
                        agent-runner/pending-request (constantly nil)
                        agent-runner/tmux
                        (fn [& _] (throw (ex-info "no keystrokes" {})))]
            (binding [agent-runner/*settle-timeout-ms* 100
                      agent-runner/*settle-iteration-ms* 10]
              (is (= :login-required
                     (agent-runner/settle-agent-session-state! root)))
              (reset! pane "❯ Try \"write a test for <filepath>\"\n")
              (is (= :ready
                     (agent-runner/settle-agent-session-state! root)))
              (agent-runner/clear-login-required! root)
              (is (not (fs/exists? marker)))))))

      (testing "unrelated prompts still take the generic failed path"
        (with-redefs [agent-runner/visible-pane (constantly unrelated-prompt)
                      agent-runner/pending-request (constantly nil)
                      agent-runner/tmux
                      (fn [& _] (throw (ex-info "no keystrokes" {})))]
          (binding [agent-runner/*settle-timeout-ms* 100
                    agent-runner/*settle-iteration-ms* 10]
            (is (= :failed
                   (agent-runner/settle-agent-session-state! root))))))
      (finally
        (fs/delete-tree root)))))

(deftest continuation-nudges-respect-canonical-stage-job-state
  (let [root (fs/create-temp-dir {:dir "/tmp" :prefix "sf-runner-stage-job."})
        idle-pane "Ask Codex to do anything\n"]
    (try
      (testing "a fresh matching job suppresses the old idle-threshold nudge"
        (let [selected (write-stage-job!
                        root
                        {:id "stage-job-code-runner-active"
                         :card-id "card-active"})
              request-file
              (fs/path root ".swarmforge" "dashboard" "requests" "pending"
                       "req-active.request")]
          (write-file request-file
                      (str "id: req-active\n"
                           "status: pending\n\n"
                           "New routed card: active-card\n"
                           "Task ID: card-active\n\n"
                           "# Active card\n"))
          (let [request (agent-runner/parse-request request-file)]
            (is (= "card-active" (:task_id request)))
            (write-delivered-request! root request idle-pane 1000)
            (let [{:keys [calls out state]} (run-delivered-handler!
                                              root request idle-pane)]
              (is (str/includes? out "waiting for stage job"))
              (is (str/includes? out "(heartbeat fresh)"))
              (is (empty? calls))
              (is (= "waiting"
                     (get-in state [:stage-job-hold :state])))
              (is (= (:id selected)
                     (get-in state [:stage-job-hold :job-id]))))

            (write-delivered-request! root request idle-pane 3600000)
            (let [{:keys [calls out]} (run-delivered-handler!
                                       root request idle-pane)]
              (is (empty? calls))
              (is (not (str/includes? out "nudging delivered request"))))

            (testing "the selected request becomes eligible after consumption"
              (stage-jobs/atomic-write-edn!
               (stage-jobs/job-file root (:id selected))
               (assoc (stage-jobs/load-job root (:id selected))
                      :consumed_at (str (java.time.Instant/now))))
              (run-delivered-handler! root request idle-pane)
              (write-delivered-request! root request idle-pane 3600000)
              (let [{:keys [calls out]} (run-delivered-handler!
                                         root request idle-pane)]
                (is (seq calls))
                (is (str/includes? out "nudging delivered request")))))))

      (testing "stale heartbeat blocks generic continuation"
        (let [request {:id "req-stale" :task_id "card-stale"}]
          (write-stage-job!
           root
           {:id "stage-job-code-runner-stale"
            :card-id "card-stale"
            :heartbeat-age-ms 60000})
          (write-delivered-request! root request idle-pane 1000)
          (let [{:keys [calls out state]} (run-delivered-handler!
                                            root request idle-pane)]
            (is (empty? calls))
            (is (str/includes? out "continuation nudges blocked"))
            (is (str/includes? out " is unknown "))
            (is (not (str/includes? out "nudging delivered request")))
            (is (= "blocked"
                   (get-in state [:stage-job-hold :state])))
            (is (false? (get-in state [:stage-job-hold :heartbeat-fresh]))))))

      (testing "a reused process identity is not treated as the active job"
        (let [request {:id "req-reused" :task_id "card-reused"}]
          (write-stage-job!
           root
           {:id "stage-job-code-runner-reused"
            :card-id "card-reused"
            :process-start-identity "not-the-original-process"})
          (write-delivered-request! root request idle-pane 1000)
          (let [{:keys [calls out]} (run-delivered-handler!
                                     root request idle-pane)]
            (is (empty? calls))
            (is (str/includes? out "continuation nudges blocked"))
            (is (not (str/includes? out "nudging delivered request"))))))

      (testing "a missing process blocks generic continuation"
        (let [request {:id "req-missing" :task_id "card-missing"}]
          (write-stage-job!
           root
           {:id "stage-job-code-runner-missing"
            :card-id "card-missing"
            :pid 999999999
            :process-start-identity "missing-process"})
          (write-delivered-request! root request idle-pane 1000)
          (let [{:keys [calls out]} (run-delivered-handler!
                                     root request idle-pane)]
            (is (empty? calls))
            (is (str/includes? out "continuation nudges blocked"))
            (is (not (str/includes? out "nudging delivered request"))))))

      (testing "a matching-project job for another card does not suppress"
        (let [request {:id "req-other-card" :task_id "card-selected"}]
          (write-stage-job!
           root
           {:id "stage-job-code-runner-other-card"
            :card-id "card-other"})
          (write-delivered-request! root request idle-pane 1000)
          (let [{:keys [calls out]} (run-delivered-handler!
                                     root request idle-pane)]
            (is (seq calls))
            (is (str/includes? out "nudging delivered request")))))

      (testing "an open clarification still wins over stage-job state"
        (let [request {:id "req-clarification" :task_id "card-clarification"}
              clarification-id "clar-active"]
          (write-stage-job!
           root
           {:id "stage-job-code-runner-clarification"
            :card-id "card-clarification"
            :heartbeat-age-ms 60000})
          (write-file
           (fs/path root ".swarmforge" "dashboard" "requests" "pending"
                    (str clarification-id ".request"))
           (str "id: " clarification-id "\n"
                "status: open\n"
                "author: project-agent\n"
                "direction: project-agent\n"
                "kind: clarification\n"
                "clarification_id: " clarification-id "\n"
                "attention_state: unseen\n\n"
                "Which option should be used?\n"))
          (write-delivered-request! root request idle-pane 1000)
          (let [{:keys [calls out]} (run-delivered-handler!
                                     root request idle-pane)]
            (is (empty? calls))
            (is (str/includes? out "waiting for clarification"))
            (is (not (str/includes? out "nudging delivered request"))))))
      (finally
        (fs/delete-tree root)))))
