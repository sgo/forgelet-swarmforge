(ns swarmforge.hermetic
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn canonical [path]
  (str (try
         (fs/canonicalize path)
         (catch Exception _
           (fs/absolutize path)))))

(defn disposable-root? [dir]
  (let [path (canonical dir)]
    (some #(str/starts-with? path %)
          ["/tmp/sf-" "/private/tmp/sf-"
           "/tmp/swarmforge-" "/private/tmp/swarmforge-"])))

(defn flag-value [args flag]
  (some (fn [[option value]]
          (when (= option flag) value))
        (partition 2 1 args)))

(def root-flags
  ["--restart-role" "--restart-dead-roles" "--start-project" "--stop-project"
   "--open-project" "--close-project" "--test-start-pack-web"
   "--test-stop-pack-web" "--test-reset-pack-web-state"])

(defn lifecycle-targets [program options dir]
  (case program
    "get-swarm-forge" [dir]
    "get-agent-project" [(or (flag-value options "--forge") dir)]
    "convert-pack-project" [(or (flag-value options "--forge") dir)]
    "agent-runner.bb" [(or (flag-value options "--root") dir)]
    "pack_web.sh"
    (if-let [target (some #(flag-value options %)
                          ["--serve" "--test-open-project" "--test-close-project"
                           "--test-start-pack-web" "--test-stop-pack-web"
                           "--test-teardown"])]
      [target]
      [])
    ("swarmforge.bb" "swarmforge.sh")
    (if-let [target (some #(flag-value options %) root-flags)]
      [target]
      (when (and (seq options)
                 (not-any? #(str/starts-with? % "--test-") options)
                 (not (str/starts-with? (first options) "--")))
        [(first options)]))
    []))

(defn guard!
  "Ordinary tests may start or stop only disposable fixtures. Live forge,
  Lieutenant and project runtimes are deployment targets, never gate inputs."
  [dir args]
  (let [program (fs/file-name (canonical (or (first args) "")))
        options (rest args)
        tmux-socket (when (= program "tmux")
                      (flag-value options "-S"))
        targets (lifecycle-targets program options dir)
        unsafe?
        (cond
          (= program "tmux")
          (and tmux-socket (not (disposable-root? tmux-socket)))

          (= program "get-swarm-forge")
          (and (not-any? #{"-h" "--help"} options)
               (not (disposable-root? dir)))

          :else
          (and (seq targets)
               (some #(not (disposable-root? %)) targets)))]
    (when unsafe?
      (throw
       (ex-info
        (str "Refusing live lifecycle command from ordinary tests under " dir
             ": " (str/join " " args))
        {:kind :live-lifecycle-in-test
         :dir (str dir)
         :args (vec args)}))))
  true)
