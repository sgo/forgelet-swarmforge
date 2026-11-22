(ns swarmforge.agent-runner-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

(load-file (str (fs/path (fs/cwd) "swarmforge" "scripts" "agent-runner.bb")))

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

(defn settle-with-pane!
  [initial-pane]
  (let [pane (atom initial-pane)
        sent (atom [])
        ready-text "Ask Claude to do anything"]
    (binding [agent-runner/*settle-timeout-ms* 100
              agent-runner/*settle-iteration-ms* 10
              agent-runner/*settle-prompt-delay-ms* 10]
      (with-redefs [agent-runner/visible-pane (fn [_] @pane)
                    agent-runner/ready?
                    (fn [_ _] (re-find (re-pattern ready-text) @pane))
                    agent-runner/pending-request (constantly nil)
                    agent-runner/tmux
                    (fn [_ & args]
                      (swap! sent conj (vec args))
                      (when (= "send-keys" (first args))
                        (reset! pane ready-text))
                      {:exit 0})]
        {:ready? (agent-runner/settle-agent-session! "/fixture")
         :sent @sent}))))

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
