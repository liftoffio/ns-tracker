(ns ns-tracker.core
  "Keeps track of which namespaces have changed and need to be reloaded."
  (:import java.io.File
           java.io.PushbackReader)
  (:require [clojure.java.io :as io]
            [clojure.set :refer [union]]
            [clojure.tools.namespace.file :refer [clojure-file?]]
            [clojure.tools.namespace.parse :refer [read-ns-decl]]
            [ns-tracker.dependency :refer [graph depend dependents remove-key]]
            [ns-tracker.nsdeps :refer [deps-from-ns-decl]]
            [ns-tracker.parse :refer [read-in-ns-decl]]
            [ns-tracker.sort :refer [sort-dependencies]]))

(defn- file? [f]
  (instance? java.io.File f))

(defn- find-files-in-dir [dir]
  (filter #(.isFile ^File %) (file-seq dir)))

(defn- find-sources
  [dirs]
  {:pre [(every? file? dirs)]}
  (mapcat find-files-in-dir dirs))

(defn- current-timestamp-map
  "Get the current modified timestamp map for all sources"
  [dirs]
  (into {} (map (fn [^java.io.File f] {f (.lastModified f)})
                (find-sources dirs))))

(defn- modified?
  "Compare a file to a timestamp map to see if it's been modified since."
  [then now file]
  (> (get now file 0) (get then file 0)))

(defn- newer-sources [then now]
  (filter (partial modified? then now) (keys now)))

(defn- read-file [file func]
  (with-open [rdr (PushbackReader. (io/reader file))]
    (func rdr)))

(defn- newer-namespace-decls [then now dependency-graph]
  (loop [new-decls []
         new-names #{}
         files (newer-sources then now)]
    (if-let [file (first files)]
      (if (clojure-file? file)
        (if-let [ns-decl (read-file file read-ns-decl)]
          (recur (conj new-decls ns-decl)
                 (conj new-names (second ns-decl))
                 (rest files))
          (if-let [in-ns-decl (read-file file read-in-ns-decl)]
            (recur new-decls (conj new-names (second in-ns-decl)) (rest files))
            (recur new-decls new-names (rest files))))
        (recur new-decls
               (union new-names
                      (set (get-in dependency-graph [:dependents file])))
               (rest files)))
      [new-decls new-names])))

(defn- add-to-dep-graph [dep-graph namespace-decls dirs]
  (reduce (fn [g decl]
            (let [nn (second decl)
                  deps (deps-from-ns-decl decl dirs)]
              (apply depend g nn deps)))
          dep-graph namespace-decls))

(defn- remove-from-dep-graph [dep-graph new-decls]
  (apply remove-key dep-graph (map second new-decls)))

(defn- update-dependency-graph [dep-graph new-decls dirs]
  (-> dep-graph
      (remove-from-dep-graph new-decls)
      (add-to-dep-graph new-decls dirs)))

(defn- affected-namespaces [changed-namespaces old-dependency-graph]
  (->> (map #(dependents old-dependency-graph %)
            changed-namespaces)
       (apply union changed-namespaces)
       (sort-dependencies old-dependency-graph)))

(defn- make-file [f]
  {:pre [(or (string? f) (file? f))]}
  (if (file? f) f (io/file f)))

(defn- normalize-dirs [dirs]
  {:pre [(or (string? dirs) (sequential? dirs))]}
  (cond
    (string? dirs) [(io/file dirs)]
    (sequential? dirs) (map make-file dirs)))

(defn ns-tracker
  "Returns a no-arg function which, when called, returns a set of
  namespaces that need to be reloaded, based on file modification
  timestamps and the graph of namespace dependencies."
  ([dirs]
   (ns-tracker dirs (current-timestamp-map (normalize-dirs dirs))))
  ([dirs initial-timestamp-map]
   {:pre [(map? initial-timestamp-map)]}
   (let [dirs (normalize-dirs dirs)
         timestamp-map (atom initial-timestamp-map)
         [init-decls init-names] (newer-namespace-decls {} @timestamp-map {})
         dependency-graph (atom (update-dependency-graph (graph) init-decls
                                                         dirs))]
     (fn []
       (let [then @timestamp-map
             now (current-timestamp-map (normalize-dirs dirs))
             [new-decls new-names] (newer-namespace-decls then now
                                                          @dependency-graph)]
         (when (seq new-names)
           (let [affected-names (affected-namespaces new-names
                                                     @dependency-graph)]
             (reset! timestamp-map now)
             (swap! dependency-graph update-dependency-graph new-decls dirs)
             affected-names)))))))
