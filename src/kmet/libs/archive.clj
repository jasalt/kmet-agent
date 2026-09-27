(ns kmet.libs.archive
  "Zip reading and writing shared by the build packagers (kmet.tasks.build,
   kmet.tasks.build-jolt), extensions (e.g. the tree-sitter CLI download)
   and extension artifact loading.
   Host-evaluated with full Java interop and shared by reference, so
   extension SCI contexts — where instance methods on JDK inner classes
   such as ZipFile$ZipFileInflaterInputStream are not callable — get zip
   support through one audited zip-slip guard instead of reimplementing it.

   Portable: extraction and creation run on java.util.zip, which both hosts
   provide (babashka bundles the JDK's; Jolt's runtime implements it on the
   zlib every binary links). write-zip! additionally stamps POSIX modes into
   the central directory it writes, since no host's ZipEntry surface can
   record one."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- entry-target
  "Canonicalized extraction target for zip entry RAW under DEST, or throws
   ::zip-slip when it would land outside DEST. Zip entries may use \\ as a
   separator (the spec allows both); names are normalized to / first so
   Windows-style ..\\evil escapes are caught on every platform
   (canonicalize only resolves /-separated .. on unix, where \\ is a plain
   filename char)."
  [dest raw]
  (let [rel (str/replace (str raw) "\\" "/")
        out (when-not (or (str/blank? rel) (str/starts-with? rel "/"))
              (fs/canonicalize (fs/path dest rel) {:nofollow-links true}))]
    ;; canonicalize resolves ".." lexically, so the containment check is
    ;; effective (fs/starts-with? takes path prefix)
    (when (or (nil? out) (= out dest) (not (fs/starts-with? out dest)))
      (throw (ex-info (str "zip entry escapes target dir: " raw)
                      {:type ::zip-slip :entry raw})))
    out))

(defn extract-zip!
  "Extract every file entry of zip-path under dest-dir, creating nested
   dirs as needed. Returns the seq of extracted paths (no permission
   preservation — callers chmod as needed)."
  [zip-path dest-dir]
  (let [dest (fs/canonicalize dest-dir {:nofollow-links true})]
    (fs/create-dirs dest)
    (with-open [zf (java.util.zip.ZipFile. (fs/file zip-path))]
      (doall
       (for [entry (enumeration-seq (.entries zf))
             :when (not (.isDirectory entry))]
         (let [out (entry-target dest (.getName entry))]
           (fs/create-dirs (fs/parent out))
           (with-open [in (.getInputStream zf entry)
                       out-stream (io/output-stream (fs/file out))]
             (io/copy in out-stream))
           out))))))

(def ^:private file-mode
  "0100644 — a regular file with default permissions."
  (int 0x81A4))

(def ^:private executable-file-mode
  "0100755 — a regular file with the executable bits set."
  (int 0x81ED))

(defn- safe-entry-name
  "Normalize RAW to a /-separated zip entry name, throwing ::zip-slip when it
   is blank, absolute (POSIX or drive-lettered), or carries a .. segment."
  [raw]
  (let [rel (str/replace (str raw) "\\" "/")]
    (when (or (str/blank? rel)
              (str/starts-with? rel "/")
              (re-find #"^[A-Za-z]:" rel)
              (some #{".."} (str/split rel #"/")))
      (throw (ex-info (str "unsafe zip entry name: " raw)
                      {:type ::zip-slip :entry raw})))
    rel))

;; ─── Zip mode stamping ────────────────────────────────────────────────────
;;
;; A zip records each entry's POSIX mode in the central directory's
;; external-attributes field, and that is where extractors read it from.
;; java.util.zip.ZipOutputStream writes none, and neither host's ZipEntry
;; surface can set one — babashka's bundled JDK image and Jolt's runtime both
;; lack setUnixMode — so write-zip! stamps the modes into the archive's own
;; bytes after writing it, in place (the local data and the entries are left
;; exactly as ZipOutputStream wrote them).

(def ^:private eocd-signature 0x06054b50)
(def ^:private central-signature 0x02014b50)
(def ^:private eocd-size 22)
(def ^:private eocd-max-size (+ eocd-size 0xFFFF))

(defn- le-u16
  "Little-endian unsigned 16-bit value at I in B."
  [b i]
  (bit-or (bit-and (aget b i) 0xFF)
          (bit-shift-left (bit-and (aget b (inc i)) 0xFF) 8)))

(defn- le-u32
  "Little-endian unsigned 32-bit value at I in B."
  [b i]
  (bit-or (long (le-u16 b i))
          (bit-shift-left (long (le-u16 b (+ i 2))) 16)))

(defn- put-le-u32!
  "Write V as a little-endian 32-bit value at I in B."
  [b i v]
  (dotimes [k 4]
    (aset b (+ i k)
          (unchecked-byte (bit-and (bit-shift-right (long v) (* 8 k)) 0xFF))))
  b)

(defn- read-fully!
  "Read exactly N bytes from IN; throws ::truncated-zip on a short stream."
  [in n]
  (let [buf (byte-array (int n))]
    (loop [off 0]
      (if (= off n)
        buf
        (let [read (.read in buf off (- (int n) off))]
          (if (neg? read)
            (throw (ex-info "zip ended before its central directory was read"
                            {:type ::truncated-zip}))
            (recur (+ off read))))))))

(defn- discard-bytes!
  "Read and discard N bytes from IN; throws ::truncated-zip on a short stream."
  [in n]
  (let [buf (byte-array 65536)]
    (loop [n (long n)]
      (when (pos? n)
        (let [read (.read in buf 0 (int (min n 65536)))]
          (if (neg? read)
            (throw (ex-info "zip ended before its tail was read"
                            {:type ::truncated-zip}))
            (recur (- n read))))))))

(defn- copy-range!
  "Copy exactly N bytes from IN to OUT; throws ::truncated-zip on a short
   stream."
  [in out n]
  (let [buf (byte-array 65536)]
    (loop [n (long n)]
      (when (pos? n)
        (let [read (.read in buf 0 (int (min n 65536)))]
          (when (neg? read)
            (throw (ex-info "zip ended before its central directory was read"
                            {:type ::truncated-zip})))
          (.write out buf 0 read)
          (recur (- n read)))))))

(defn- eocd-offset
  "Offset of the end-of-central-directory record within TAIL (the archive's
   last bytes), or nil. The comment length must reach the end of the file, so
   a signature-like byte sequence inside a comment cannot win."
  [tail]
  (let [n (alength tail)]
    (loop [i (- n eocd-size)]
      (when (>= i 0)
        (if (and (= eocd-signature (le-u32 tail i))
                 (= n (+ i eocd-size (le-u16 tail (+ i 20)))))
          i
          (recur (dec i)))))))

(defn- patch-central-directory!
  "Stamp MODES — full POSIX modes, one per central-directory entry, in file
   order — into the central directory bytes CD: an entry's version-made-by
   platform byte becomes 3 (Unix) and its external-attributes field gets the
   mode in the high 16 bits, which is where extractors read it. Returns CD."
  [cd modes]
  (loop [i 0
         off 0]
    (when (< i (count modes))
      (when-not (= central-signature (le-u32 cd off))
        (throw (ex-info "not a zip central directory"
                        {:type ::bad-central-directory :offset off})))
      (let [name-len (le-u16 cd (+ off 28))
            extra-len (le-u16 cd (+ off 30))
            comment-len (le-u16 cd (+ off 32))]
        (aset cd (+ off 5) (unchecked-byte 3))
        (put-le-u32! cd (+ off 38)
                     (bit-shift-left (bit-and (long (nth modes i)) 0xFFFF) 16))
        (recur (inc i) (+ off 46 name-len extra-len comment-len)))))
  cd)

(defn- stamp-zip-modes!
  "Stamp MODES (one per entry, in the order the entries were written) into
   the zip at ZIP. Rewrites the archive through a .part temp file: the
   central directory bytes carry the modes, the compressed data is copied
   untouched. Returns ZIP."
  [zip modes]
  (let [size (long (fs/size zip))
        tail-len (int (min size (long eocd-max-size)))
        tail (with-open [in (io/input-stream (fs/file zip))]
               (discard-bytes! in (- size tail-len))
               (read-fully! in tail-len))
        eocd (eocd-offset tail)
        _ (when-not eocd
            (throw (ex-info "no zip end-of-central-directory record"
                            {:type ::bad-central-directory})))
        entry-count (le-u16 tail (+ eocd 10))
        cd-size (le-u32 tail (+ eocd 12))
        cd-offset (le-u32 tail (+ eocd 16))]
    (when-not (= entry-count (count modes))
      (throw (ex-info "zip central directory does not match the written entries"
                      {:type ::bad-central-directory
                       :entries entry-count :modes (count modes)})))
    (let [tmp (str zip ".part")]
      (with-open [in (io/input-stream (fs/file zip))
                  out (io/output-stream (fs/file tmp))]
        (copy-range! in out cd-offset)
        (.write out (patch-central-directory! (read-fully! in cd-size) modes))
        (io/copy in out))
      (fs/move (fs/path tmp) (fs/path zip) {:replace-existing true}))
    zip))

(defn write-zip!
  "Create a zip archive at OUT-PATH from ENTRIES, written in order. Each
   entry is a map:

     :name        slash-separated entry name (required)
     :file        file whose bytes are stored (required)
     :executable? record the executable POSIX mode (0100755) instead of the
                  regular-file default (0100644)

   Entry names are validated like extraction targets (no absolute paths, no
   .. segments); parent directory entries are not synthesized, so extraction
   creates them. Writes in-process through java.util.zip on both hosts — no
   external zip program — and returns OUT-PATH as a string. The modes are
   stamped into the central directory after writing (stamp-zip-modes!),
   because neither host's ZipEntry can record one, so an extracted
   executable keeps its executable bit."
  [out-path entries]
  (let [entries (vec entries)]
    (fs/create-dirs (fs/parent (fs/absolutize out-path)))
    (with-open [zos (java.util.zip.ZipOutputStream.
                     (io/output-stream (fs/file out-path)))]
      (doseq [{:keys [name file]} entries]
        (.putNextEntry zos (java.util.zip.ZipEntry. (safe-entry-name name)))
        (with-open [in (io/input-stream (fs/file file))]
          (io/copy in zos))
        (.closeEntry zos)))
    (stamp-zip-modes! out-path
                      (mapv #(if (:executable? %) executable-file-mode file-mode)
                            entries))
    (str out-path)))

