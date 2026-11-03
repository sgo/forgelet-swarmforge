(ns layer-coverage
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader.reader-types :as reader-types]
            [cloverage.coverage :as coverage]
            [cloverage.instrument :as instrument]
            [cloverage.source :as source]
            [crap4clj.bb-coverage :as bb-coverage]))

(def ^:private cloverage-resource-path source/resource-path)

(defn hyphenated-resource-path [namespace]
  (let [resource (str (-> (name namespace)
                          (str/replace "." "/"))
                      ".bb")]
    (when (io/resource resource)
      resource)))

(defn resource-path [namespace]
  (or (hyphenated-resource-path namespace)
      (cloverage-resource-path namespace)))

(defn- entrypoint-invocation? [form]
  (and (seq? form)
       (= 'apply (first form))
       (= '-main (second form))))

(defn- safe-instrument-file [instrument-fn namespace filename]
  (with-open [source-reader (source/form-reader namespace)]
    (transduce
     (comp (remove entrypoint-invocation?)
           (map #(instrument/instrument-form instrument-fn filename %)))
     conj
     (source/forms source-reader))))

(defn- load-file-path [path]
  (let [candidate (fs/path path)]
    (cond
      (fs/regular-file? candidate) candidate
      (fs/regular-file? (fs/path (System/getProperty "user.dir")
                                 "swarmforge" "scripts" (fs/file-name candidate)))
      (fs/path (System/getProperty "user.dir")
               "swarmforge" "scripts" (fs/file-name candidate))
      :else candidate)))

(defn- safe-load-file [path]
  (let [path (load-file-path path)]
    (with-open [source-reader
                (-> path
                    str
                    io/reader
                    java.io.PushbackReader.
                    reader-types/indexing-push-back-reader)]
      (binding [*ns* *ns*
                *file* (str path)
                *source-path* (str path)]
        (reduce (fn [_ form]
                  (when-not (entrypoint-invocation? form)
                    (eval form)))
                nil
                (source/forms source-reader))))))

(defn enable-discovery! []
  (bb-coverage/enable-bb-sources!)
  (alter-var-root #'source/resource-path (constantly resource-path))
  (alter-var-root #'instrument/instrument-file (constantly safe-instrument-file))
  (alter-var-root #'clojure.core/load-file (constantly safe-load-file)))

(defn discover!
  ([] (discover! "swarmforge/scripts"))
  ([source-root]
   (enable-discovery!)
   (let [resolved (->> (coverage/find-nses [source-root] [#".*"])
                       sort
                       (mapv (fn [namespace]
                               [namespace
                                (source/resource-path (symbol namespace))])))]
     (doseq [[namespace resource] resolved]
       (println (str namespace "\t" resource)))
     (println (str "Resolved " (count resolved)
                   " Babashka namespaces from " source-root "."))
     resolved)))

(defn -main [& args]
  (discover! (or (first args) "swarmforge/scripts")))
