(ns cljnix.utils
  (:require
    [clojure.java.shell :as sh]
    [clojure.string :as string]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojure.data.json :as json]
    [clojure.tools.deps.util.maven :as mvn]
    [clojure.tools.gitlibs.config :as gitlibs-config]
    [clojure.tools.gitlibs.impl :as gli]
    [clojure.tools.deps :as deps]
    [babashka.fs :as fs]
    [babashka.http-client :as http]
    [version-clj.core :as version]
    [clojure.zip :as zip]
    [borkdude.rewrite-edn :as r]
    [clojure.tools.deps.util.io :refer [printerrln]]
    [clojure.tools.deps.extensions.pom :refer [read-model-file]])
  (:import
    [org.apache.maven.model.io.xpp3 MavenXpp3Reader]
    [org.apache.maven.model Model Repository Dependency]
    [java.net URI]))

(defn throw+
  [msg data]
  (throw (ex-info (prn-str msg data) data)))

(defn- snapshot?
  [path]
  (-> path
      fs/parent
      fs/file-name
      str
      (string/lower-case)
      (string/includes? "snapshot")))


; https://maven.apache.org/ref/3.9.6/maven-model/apidocs/index.html
(defn- pom
  ^Model [pom-path]
  (try
    (when (= "pom" (fs/extension pom-path))
      (let [f (io/input-stream (str pom-path))]
        (.read (MavenXpp3Reader.) f)))
    (catch Exception _
      (printerrln "Error parsing [skiping]" pom-path))))

; Alternative implementation
(defn- pom'
  ^Model [pom-path]
  (when (= "pom" (fs/extension pom-path))
    (read-model-file (io/file (str pom-path)) {:mvn/repos mvn/standard-repos})))

;; Snapshot jar can be
;; foo-123122312.jar
;; foo-SNAPSHOT.jar
(defn- snapshot-info
  [path]
  (let [[group-id artifact-id snapshot-version]
        (-> (str (fs/strip-ext path) ".pom")
            (pom)
            ((juxt
               (memfn ^Model getGroupId)
               (memfn ^Model getArtifactId)
               (memfn ^Model getVersion))))]
    {:artifact-id artifact-id
     :group-id group-id
     :snapshot-version snapshot-version
     :version (subs
                (fs/file-name (fs/strip-ext path))
                (inc (count artifact-id)))}))

(defn- latest-snapshot
  [path]
  (->> (fs/glob (fs/parent path) "*.pom")
       (map (comp :version snapshot-info))
       (remove version/snapshot?)
       (version/version-sort)
       (last)))

(defn- resolve-snapshot
  [path]
  (if-not (snapshot? path)
    {:resolved-path (str path)}
    (let [ext (fs/extension path)
          {:keys [artifact-id version snapshot-version]} (snapshot-info path)]
      {:resolved-path
        (if-not (version/snapshot? version) ; Maybe not needed, but who knows with maven...
          path
          (str (fs/path (fs/parent path) (str artifact-id
                                              "-"
                                              (if (version/snapshot? version)
                                                (latest-snapshot path)
                                                version)
                                              "."
                                              ext))))
       :snapshot (str artifact-id "-" snapshot-version "." ext)})))

; deps/create-basis returns '~/.../foo-1.0-SNAPSHOT.jar' as path,
; even if the maven version is fixed: 'foo-1.0-20220502.201054-5'
(defn fixed-snapshot-path
  "Helper to convert a maven artifact snapshot to the fixed version.
  e.g.: ~/.../foo-1.0-SNAPSHOT.jar -> ~/.../foo-1.0-20220502.201054-5.jar
  Path must be a SNAPSHOT, and version a fixed version, if not, does nothing"
  [path fixed-version]
  (if-not (and
            (snapshot? path)
            (not (string/includes? fixed-version "SNAPSHOT")))
    path
    ;; fixed-version is 1.0-20220502.201054-5
    ;; path is ~/.../my-lib-1.0-SNAPSHOT.jar
    (let [{:keys [version]} (snapshot-info path)]
      (fs/path
        (fs/parent path)
        (string/replace (fs/file-name path) version fixed-version)))))


(defn- get-mvn-repo-name
  [path mvn-repos]
  (try
    (let [info-file (fs/path (fs/parent path) "_remote.repositories")
          file-name (fs/file-name path)
          repo-name-finder (fn [s] (second (re-find
                                             (re-pattern (str file-name #">(\S+)="))
                                             s)))]
      (some #(let [repo-name (repo-name-finder %)]
               (when (contains? mvn-repos repo-name)
                 repo-name))
           (fs/read-all-lines info-file)))
    (catch Exception _ nil)))


(defn- valid-url?
  [url]
  (try
    (boolean (http/head url))
    (catch Exception _ false)))

(defn- join-url
  [url path]
  (let [url (if (string/ends-with? url "/")
              url
              (str url "/"))]
    (-> (URI. url)
      (.resolve path)
      (str))))

(defn- find-mvn-repo-name!
  [mvn-path mvn-repos]
  (some (fn [[repo-name {:keys [url]}]]
          (when (valid-url? (join-url url mvn-path))
            repo-name))
        mvn-repos))


(defn mvn-repo-info
  "Given a path for a jar in the maven local repo, e.g:
   $REPO/babashka/fs/0.1.4/fs-0.1.4.jar
   return the maven repository url and the dependecy url"
  [path & {:keys [cache-dir mvn-repos]
           :or {cache-dir @mvn/cached-local-repo
                mvn-repos mvn/standard-repos}}]
  (let [{:keys [resolved-path snapshot]} (resolve-snapshot path)
        mvn-path (str (fs/relativize cache-dir resolved-path))
        repo-name (or
                    (get-mvn-repo-name resolved-path mvn-repos)
                    (find-mvn-repo-name! mvn-path mvn-repos))
        repo-url (get-in mvn-repos [repo-name :url])
        repo-url (cond
                   (nil? repo-url) (throw+ "Maven repo not found"
                                           {:mvn-repos mvn-repos
                                            :repo-name repo-name
                                            :file path})
                   ((complement string/ends-with?) repo-url "/") (str repo-url "/")
                   :else repo-url)]
     (cond-> {:mvn-repo repo-url
              :mvn-path mvn-path
              :url (str repo-url (fs/relativize cache-dir resolved-path))}
       snapshot (assoc :snapshot snapshot))))


(defn git-remote-url
  [repo-root-path]
  (string/trim
    (:out
      (sh/with-sh-dir (str repo-root-path)
        (sh/sh "git" "remote" "get-url" "origin")))))

; See
; https://github.com/clojure/tools.gitlibs/blob/v2.4.172/src/main/clojure/clojure/tools/gitlibs/impl.clj#L83
(defn git-dir
  "Relative path to the git dir for a given URL"
  [url]
  (str (fs/relativize
         (fs/path (:gitlibs/dir @gitlibs-config/CONFIG) "_repos")
         (gli/git-dir url))))


(defn paths-to-gitdeps
  [deps-data]
  (some->> deps-data
           (zip/zipper coll? seq nil)
           (iterate zip/next)
           (take-while (complement zip/end?))
           (filter (comp (some-fn :sha :git/sha) zip/node))
           (mapv #(some->> (zip/path %)
                           (filter map-entry?)
                           (mapv key)))))

(defn- full-sha?'
  [sha]
  (boolean (and sha (= 40 (count sha)))))

(defn full-sha?
  [git-dep]
  (or (full-sha?' (:sha git-dep))
      (full-sha?' (:git/sha git-dep))))

(def partial-sha? (complement full-sha?))


(defn- expand-hash
  [git-deps lib node]
  (let [node-data (r/sexpr node)
        tag (or (:tag node-data)
                (:git/tag node-data))
        full-sha (:rev (first (filter #(and
                                         (= (str lib) (:lib %))
                                         (= tag (:tag %)))
                                      git-deps)))]
    (if full-sha
      (cond-> node
        (:sha node-data)     (r/assoc :sha full-sha)
        (:git/sha node-data) (r/assoc :git/sha full-sha)
        ; Remove :git/tag to avoid network call
        :always              (-> (r/dissoc :tag) (r/dissoc :git/tag)))
      (do
        (printerrln "Can't expand full sha, ignoring"
                    {:lib lib
                     :node (edn/read-string (str node))})
        node))))

(defn- deps-file?
  "Returns true if filename is deps.edn or bb.edn "
  [file]
  (contains? #{"bb.edn" "deps.edn"}
             (fs/file-name file)))

(defn get-deps-files
  "Returns all deps.edn files in a directory.
   Optionally, includes bb.edn files and filter some files"
  [dir {:keys [deps-include deps-exclude bb?]}]
  (if-not (empty? deps-include)
    (map fs/canonicalize deps-include)
    (let [exclude? (fn [f] (some #(fs/same-file? f %) deps-exclude))]
      (filter (every-pred deps-file? (complement exclude?))
        (concat
          (fs/glob dir "**deps.edn")
          (when bb? (fs/glob dir "**bb.edn")))))))


(defn- valid-deps-file?
  [f]
  (try
    (deps/slurp-deps (fs/file f))
    true
    (catch clojure.lang.ExceptionInfo _
      (printerrln "Ignoring invalid deps.edn file:" (fs/file f))
      false)))

(defn expand-shas!
  [project-dir]
  (let [dep-paths (get-deps-files project-dir {:bb? true})
        {:keys [git-deps]} (json/read-str
                            (slurp (str (fs/path project-dir "deps-lock.json")))
                            :key-fn keyword)]
    (doseq [my-deps (->> dep-paths
                         (filter valid-deps-file?)
                         (mapv fs/file))
            :let [deps (deps/slurp-deps my-deps)
                  git-deps-paths (paths-to-gitdeps deps)
                  partial-sha-paths (filter #(partial-sha? (get-in deps %))
                                            git-deps-paths)]]
        (as-> (r/parse-string (slurp my-deps)) nodes
          (reduce
            (fn [acc path] (r/update-in acc path (partial expand-hash git-deps (last path))))
            nodes
            partial-sha-paths)
          (spit my-deps (str nodes))))))

(defn str->keyword
  [s]
  (-> s
    (string/replace  ":" "")
    (string/split #"/")
    (->> (apply keyword))))


(defn mvn?
  [[_ {:keys [mvn/version]}]]
  (boolean version))

(defn git?
  [[_ {:keys [git/url]}]]
  (boolean url))

(defn artifact->pom
  [path]
  (str (->> (fs/glob (fs/parent path) "*.pom")
            (sort (fn [x y]
                    (cond
                      (string/includes? (fs/file-name x) "SNAPSHOT") -1
                      (string/includes? (fs/file-name y) "SNAPSHOT") 1
                      :else (compare (fs/file-name x) (fs/file-name y)))))
            first)))

(defn get-repos
  "For a given path to a pom, returns all maven repos"
  [pom-path]
  (try
    (let [pom (io/file pom-path)
          model (read-model-file pom nil)]
      (into {}
            (map (fn [^Repository repo] (vector (.getId repo)
                                                {:url (.getUrl repo)})))
            (.getRepositories model)))
    (catch Exception _ {})))

; https://maven.apache.org/guides/mini/guide-multiple-repositories.html#Repository_Order
(defn get-maven-repos
  "Given a basis, returns all maven repos"
  [basis]
  (merge
    (into {} (comp
              (filter mvn?)
              (map val)
              (mapcat :paths)
              (map artifact->pom)
              (map get-repos))
          (:libs basis))
    mvn/standard-repos
    (:mvn/repos basis)))




(defn- dep-path
  [^Dependency dep]
  (str
    (fs/path
      @mvn/cached-local-repo
      (string/replace (.getGroupId dep) "." "/")
      (.getArtifactId dep)
      (.getVersion dep)
      (format "%s-%s.pom" (.getArtifactId dep) (.getVersion dep)))))


(defn- get-deps
  [pom-path]
  (some->> (pom pom-path)
           (.getDependencyManagement)
           (.getDependencies)
           (map dep-path)
           (filter fs/exists?)
           (distinct)
           (remove #(= % pom-path))))

(defn get-management-deps
  "Given a pom file, recursively returns all the management dependencies
   In many cases those POMs contain BOM information"
  [pom-path]
  (when pom-path
    (loop [deps #{}
           deps' (get-deps pom-path)]
      (let [new-deps (remove deps deps')]
        (if-let [dep (first new-deps)]
          (recur (conj deps dep)
                 (concat (rest new-deps) (get-deps dep)))
          deps)))))


(defn get-parent
 [pom-path]
 (when-let [parent (some-> (pom pom-path) (.getParent))]
    (let [parent-path
          (fs/path
            @mvn/cached-local-repo
            (string/replace (.getGroupId parent) "." "/")
            (.getArtifactId parent)
            (.getVersion parent)
            (format "%s-%s.pom" (.getArtifactId parent) (.getVersion parent)))]
      (when (fs/exists? parent-path)
        (str parent-path)))))


(defn get-parent-poms
  "Given a pom file, recursively returns all parent POMs"
  [pom-path]
  (loop [parents #{}
         new-parent (get-parent pom-path)]
     (if (nil? new-parent)
       parents
       (recur (conj parents new-parent)
              (get-parent new-parent)))))

(comment

  (fixed-snapshot-path
    (fs/expand-home "~/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-SNAPSHOT.jar")
    "2022.04.26-20220502.201054-5")

  (get-maven-repos
    (deps/create-basis {:user nil
                        :project (str (fs/expand-home "~/projects/clj-demo-project/deps.edn"))}))

  (expand-shas! (fs/expand-home "~/projects/clojure-lsp"))

  (mvn-repo-info (fs/expand-home "~/.m2/repository/org/clojure/clojure/1.11.1/clojure-1.11.1.jar"))
  (mvn-repo-info (fs/expand-home "~/.m2/repository/org/clojure/pom.contrib/1.1.0/pom.contrib-1.1.0.pom"))
  (get-management-deps (fs/expand-home "~/.m2/repository/metosin/reitit/0.5.15/reitit-0.5.15.pom"))

  (fs/expand-home "~/.m2/repository/metosin/reitit/0.5.15/reitit-0.5.15.pom")
  (get-management-deps (fs/expand-home "~/.m2/repository/com/google/firebase/firebase-admin/9.2.0/firebase-admin-9.2.0.pom"))
  (get-parent-poms (fs/expand-home "~/.m2/repository/io/grpc/grpc-bom/1.55.1/pom.xml"))


  (mvn-repo-info
    (fs/expand-home "~/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-SNAPSHOT.jar"))

  (mvn-repo-info
    (fs/expand-home "~/.m2/repository/clj-kondo/clj-kondo/2022.04.26-SNAPSHOT/clj-kondo-2022.04.26-20220502.201054-5.jar"))


  (get-deps-files (fs/canonicalize ".") {:bb? true :deps-exclude ["templates/default/deps.edn"]})
  (get-deps-files "." {:bb? true :deps-include [""]})
  (get-deps-files "." {:bb? true :deps-exclude []})
  (get-deps-files "." {:bb? true :deps-include ["templates/default/deps.edn"]}))
