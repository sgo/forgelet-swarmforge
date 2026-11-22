(ns swarmforge.agent-runner-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(load-file (str (fs/path (fs/cwd) "swarmforge" "scripts" "agent-runner.bb")))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

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
