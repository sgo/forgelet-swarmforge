(ns swarmforge.runtime-identity-acceptance-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(def repo-root (fs/cwd))
(def script (str (fs/path repo-root
                           "qa" "run-runtime-identity-acceptance.sh")))
(def temp-dirs (atom []))

(use-fixtures :once
  (fn [tests]
    (try
      (tests)
      (finally
        (doseq [dir @temp-dirs]
          (fs/delete-tree dir))))))

(defn tmp-dir []
  (let [dir (fs/create-temp-dir {:dir "/tmp"
                                 :prefix "sf-runtime-identity."})]
    (swap! temp-dirs conj dir)
    dir))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn sentinel-fixture! []
  (let [root (tmp-dir)
        paths [(fs/path root "bridge" "matrix-bridge.json")
               (fs/path root "control" "control.json")
               (fs/path root "homeserver" "homeserver.db")
               (fs/path root "operator" "rooms.json")]]
    (doseq [[index path] (map-indexed vector paths)]
      (write-file path (str "sentinel-" index "\n")))
    {:root root
     :bridge (nth paths 0)
     :control (nth paths 1)
     :homeserver (nth paths 2)
     :operator (nth paths 3)}))

(defn sentinel-bytes [{:keys [bridge control homeserver operator]}]
  [(slurp (str bridge))
   (slurp (str control))
   (slurp (str homeserver))
   (slurp (str operator))])

(defn isolation-env [sentinels]
  {"PATH" (System/getenv "PATH")
   "FORGELET_HOST_TOKEN_FILE" (str (fs/path (:root sentinels) "host.token"))
   "FORGELET_HOST_URL" "http://127.0.0.1:9"
   "MATRIX_BRIDGE_CONFIG" (str (:bridge sentinels))
   "MATRIX_HOMESERVER_DIR" (str (:root sentinels) "homeserver")
   "MATRIX_OPERATOR_ROOMS" (str (:operator sentinels))})

(defn acceptance-works []
  (set (map str (fs/glob repo-root "tmp/runtime-identity-acceptance.*"))))

(defn run-acceptance [env]
  (apply sh/sh
         (concat ["bash" script]
                 [:dir (str repo-root)
                  :env (merge (isolation-env
                               {:root "/tmp/unused"
                                :bridge "/tmp/unused/bridge.json"
                                :control "/tmp/unused/control.json"
                                :homeserver "/tmp/unused/homeserver"
                                :operator "/tmp/unused/rooms.json"})
                              env)])))

(deftest acceptance-is-hermetic-and-does-not-mutate-live-sentinels
  (let [sentinels (sentinel-fixture!)
        before (sentinel-bytes sentinels)
        works-before (acceptance-works)
        result (run-acceptance (isolation-env sentinels))]
    (is (zero? (:exit result)) (str (:err result) (:out result)))
    (is (str/includes? (:out result)
                       "exact source, digest, files and bridge policy verified"))
    (is (= before (sentinel-bytes sentinels)))
    (is (= works-before (acceptance-works)))))

(deftest failed-acceptance-cleans-up-without-touching-live-sentinels
  (let [sentinels (sentinel-fixture!)
        before (sentinel-bytes sentinels)
        works-before (acceptance-works)
        result (run-acceptance
                (assoc (isolation-env sentinels)
                       "SWARMFORGE_ACCEPTANCE_SOURCE_COMMIT"
                       (apply str (repeat 40 "0"))))]
    (is (not (zero? (:exit result))))
    (is (= before (sentinel-bytes sentinels)))
    (is (= works-before (acceptance-works)))))

(deftest interrupted-acceptance-cleans-up-without-touching-live-sentinels
  (let [sentinels (sentinel-fixture!)
        before (sentinel-bytes sentinels)
        works-before (acceptance-works)
        output (fs/path (:root sentinels) "acceptance.log")
        builder (doto (ProcessBuilder. ["bash" script])
                  (.directory (java.io.File. (str repo-root)))
                  (.redirectErrorStream true)
                  (.redirectOutput (java.io.File. (str output))))
        env (.environment builder)]
    (doseq [[key value] (assoc (isolation-env sentinels)
                               "SWARMFORGE_ACCEPTANCE_TEST_HOLD_SECONDS" "30")]
      (.put env key value))
    (let [process (.start builder)]
      (try
        (let [deadline (+ (System/currentTimeMillis) 5000)
              held (loop []
                     (cond
                       (> (System/currentTimeMillis) deadline) nil
                       :else
                       (or (first
                            (filter #(fs/regular-file? (fs/path % "held"))
                                    (fs/glob repo-root
                                             "tmp/runtime-identity-acceptance.*")))
                           (do
                             (Thread/sleep 25)
                             (recur)))))]
          (is held "the acceptance script reached its held fixture state")
          (.destroy process)
          (is (.waitFor process 10 java.util.concurrent.TimeUnit/SECONDS)))
        (is (= before (sentinel-bytes sentinels)))
        (is (= works-before (acceptance-works)))
        (finally
          (when (.isAlive process)
            (.destroyForcibly process)
            (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)))))))
