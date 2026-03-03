#!/usr/bin/env bb

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.java.shell :refer [sh]])

(defn text-file? [file]
  (and (.isFile file)
       (str/ends-with? (.getName file) ".txt")))

(defn search-file [file needle]
  (with-open [reader (io/reader file)]
    (doseq [[idx line] (map-indexed vector (line-seq reader))]
      (when (str/includes? line needle)
        (println (str (.getPath file)
                      ":"
                      (inc idx)
                      ": "
                      line))))))

(defn walk-dir [dir needle]
  (doseq [file (file-seq (io/file dir))
          :when (text-file? file)]
    (search-file file needle)))

(defn -main [& args]
  (if (< (count args) 2)
    (println "Usage: bb search.clj <directory> <search-string>")
    (let [[dir needle] args]
      (walk-dir dir needle))))

(apply -main *command-line-args*)
