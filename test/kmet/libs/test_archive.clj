(ns kmet.libs.test-archive
  ;; Zip read/write tests for kmet.libs.archive (java.util.zip on both
  ;; hosts).
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kmet.libs.archive :as archive]))

(defn- make-zip!
  "Write a zip with {entry-name content-string} entries; returns its path."
  [zip-path entries]
  (with-open [zos (java.util.zip.ZipOutputStream.
                   (io/output-stream (fs/file zip-path)))]
    (doseq [[entry content] entries]
      (.putNextEntry zos (java.util.zip.ZipEntry. entry))
      (.write zos (.getBytes content "UTF-8"))
      (.closeEntry zos)))
  zip-path)

(defn- le-u16
  "Little-endian unsigned 16-bit value at I in B (test-local zip reader)."
  [b i]
  (bit-or (bit-and (aget b i) 0xFF)
          (bit-shift-left (bit-and (aget b (inc i)) 0xFF) 8)))

(defn- le-u32
  "Little-endian unsigned 32-bit value at I in B (test-local zip reader)."
  [b i]
  (bit-or (long (le-u16 b i))
          (bit-shift-left (long (le-u16 b (+ i 2))) 16)))

(defn- zip-central-entries
  "Raw central-directory [{:name :made-by-os :external-attrs}] of ZIP — what
   an extractor reads the POSIX mode from."
  [zip-path]
  (let [b (fs/read-all-bytes zip-path)
        n (alength b)
        eocd (loop [i (- n 22)]
               (when (>= i 0)
                 (if (and (= 0x06054b50 (le-u32 b i))
                          (= n (+ i 22 (le-u16 b (+ i 20)))))
                   i
                   (recur (dec i)))))]
    (loop [i 0
           off (le-u32 b (+ eocd 16))
           acc []]
      (if (= i (le-u16 b (+ eocd 10)))
        acc
        (let [name-len (le-u16 b (+ off 28))
              extra-len (le-u16 b (+ off 30))
              comment-len (le-u16 b (+ off 32))]
          (recur (inc i)
                 (+ off 46 name-len extra-len comment-len)
                 (conj acc {:name (String. b (+ off 46) name-len "UTF-8")
                            :made-by-os (bit-and (aget b (+ off 5)) 0xFF)
                            :external-attrs (le-u32 b (+ off 38))})))))))

(deftest extract-zip!-happy-path
  (let [tmp (str (fs/create-dirs "target/test-archive-extract") "")]
    (try
      (let [zip (make-zip! (str (fs/path tmp "a.zip"))
                           {"bin/tool" "#!/bin/sh\necho hi\n"
                            "nested/dir/readme.txt" "contents"})
            out (fs/path tmp "out")
            extracted (archive/extract-zip! zip out)]
        (is (= 2 (count extracted)))
        (is (= "#!/bin/sh\necho hi\n" (slurp (str (fs/path out "bin/tool")))))
        (is (= "contents" (slurp (str (fs/path out "nested/dir/readme.txt"))))))
      (finally
        (fs/delete-tree tmp)))))

(deftest extract-zip!-zip-slip-guard
  (let [tmp (str (fs/create-dirs "target/test-archive-slip") "")]
    (try
      (doseq [entry ["../evil.txt"
                     "/abs/evil.txt"
                     (str ".." (char 92) "evil")
                     (str "sub" (char 92) ".." (char 92) ".." (char 92) "evil")]]
        (let [zip (make-zip! (str (fs/path tmp "evil.zip")) {entry "nope"})
              out (fs/path tmp (str "out-" (count entry) "-" (rand-int 1000000)))]
          (is (thrown-with-msg? Exception #"zip entry escapes target dir"
                                (archive/extract-zip! zip out))
              (str "entry " (pr-str entry) " throws ::zip-slip"))
          (when (fs/exists? out)
            (is (empty? (filter #(not (fs/directory? %)) (fs/list-dir out)))
                (str "no file written for " (pr-str entry))))))
      (finally
        (fs/delete-tree tmp)))))

(deftest write-zip!-round-trips-through-extract-zip!
  (let [tmp (str (fs/create-dirs "target/test-archive-write") "")
        a (str (fs/path tmp "a.bin"))
        b (str (fs/path tmp "b.txt"))
        zip (str (fs/path tmp "out.zip"))]
    (try
      (spit a "binary\n")
      (spit b "text\n")
      (is (= zip (archive/write-zip! zip [{:name "bin/a" :file a :executable? true}
                                          {:name "b.txt" :file b}])))
      (let [out (fs/path tmp "unpacked")]
        (archive/extract-zip! zip out)
        (is (= "binary\n" (slurp (str (fs/path out "bin/a")))))
        (is (= "text\n" (slurp (str (fs/path out "b.txt"))))))
      (finally
        (fs/delete-tree tmp)))))

(deftest write-zip!-rejects-unsafe-entry-names
  (let [tmp (str (fs/create-dirs "target/test-archive-write-slip") "")
        f (str (fs/path tmp "x.bin"))]
    (try
      (spit f "x")
      (doseq [name ["../evil"
                    "/abs/evil"
                    (str ".." (char 92) "evil")
                    "C:/evil"]]
        (is (thrown-with-msg? Exception #"unsafe zip entry name"
                              (archive/write-zip! (str (fs/path tmp "evil.zip"))
                                                  [{:name name :file f}]))
            (pr-str name)))
      (finally
        (fs/delete-tree tmp)))))

(deftest write-zip!-stamps-posix-modes
  ;; both hosts lack ZipEntry.setUnixMode, so write-zip! stamps the central
  ;; directory itself; this reads the bytes the way an extractor does
  (let [tmp (str (fs/create-dirs "target/test-archive-write-modes") "")
        exe (str (fs/path tmp "kmet"))
        plain (str (fs/path tmp "readme.txt"))
        zip (str (fs/path tmp "modes.zip"))]
    (try
      (spit exe "binary\n")
      (spit plain "text\n")
      (archive/write-zip! zip [{:name "kmet" :file exe :executable? true}
                               {:name "readme.txt" :file plain}])
      (let [by-name (into {} (map (juxt :name identity)) (zip-central-entries zip))]
        (testing "the executable entry is Unix-made with 0100755"
          (is (= 3 (:made-by-os (by-name "kmet"))))
          (is (= 0x81ED (bit-and 0xFFFF
                                 (bit-shift-right (:external-attrs (by-name "kmet"))
                                                  16)))))
        (testing "a regular entry gets 0100644"
          (is (= 3 (:made-by-os (by-name "readme.txt"))))
          (is (= 0x81A4 (bit-and 0xFFFF
                                 (bit-shift-right (:external-attrs (by-name "readme.txt"))
                                                  16))))))
      (finally
        (fs/delete-tree tmp)))))




