(ns sitegen.ref
  (:require
    [cljs.reader :refer [read-string]]
    [clojure.string :as string]
    [util.io :as io]
    [util.markdown :as markdown]
    [util.console :as console]
    [util.highlight :refer [highlight-code]]
    [util.hiccup :as hiccup]
    [sitegen.urls :as urls]
    [sitegen.layout :refer [common-layout]]))

;;---------------------------------------------------------------
;; API Retrieval
;;---------------------------------------------------------------

(def api nil)
(def latest-version nil)

(def versions-url "https://api.github.com/repos/cljsinfo/cljs-api-docs/tags")
(defn api-url [version] (str "https://raw.githubusercontent.com/cljsinfo/cljs-api-docs/" version "/cljs-api.edn"))
(defn api-filename [version] (str "cljs-api-" version ".edn"))

; keys here: https://developer.github.com/v3/repos/#list-tags
(defn get-latest-version []
  (println "Checking latest version...")
  (->> (io/slurp-json versions-url)
       (first)
       (:name)))

(defn set-from-download!
  [filename]
  (->> (io/slurp filename)
       (read-string)
       (set! api)))

(defn update! []
  (let [version (get-latest-version)
        filename (api-filename version)
        downloaded? (io/path-exists? filename)]
    (set! latest-version version)
    (when-not downloaded?
      (println (str "Downloading latest API " version "..."))
      (->> (api-url version)
           (io/slurp)
           (io/spit filename)))
    (set-from-download! filename)))

;;---------------------------------------------------------------
;; Namespace Utilities
;;---------------------------------------------------------------

(defn hide-lib-ns? [ns-]
  (let [ns-data (get-in api [:namespaces ns-])]
    (or
      ;; pseudo-namespaces our handled manually (syntax, special, specialrepl)
      (:pseudo-ns? ns-data) ;; we handle these manually

      ;; clojure.browser is basically deprecated, so we don't show it.
      ;; https://groups.google.com/d/msg/clojurescript/OqkjlpqKSQY/9wVGC5wFjcAJ
      (string/starts-with? ns- "clojure.browser"))))

(defn lib-namespaces []
  (let [nss (->> api :api :library :namespace-names)]
    (filter (comp not hide-lib-ns?) nss)))

(defn compiler-namespaces []
  (->> api :api :compiler :namespace-names))

;;---------------------------------------------------------------
;; Sidebar Rendering
;;---------------------------------------------------------------

(defn overview-sidebar []
  [:div
    [:div latest-version " | "
     [:a {:href (urls/pretty urls/ref-history)} "History"]]
    [:div
     [:a {:href (urls/pretty urls/ref-index)} "Overview"]]
    [:div
     [:a {:href (urls/pretty (urls/ref-ns "syntax"))} "Syntax"]]
    [:div
     [:a {:href (urls/pretty (urls/ref-ns "special"))} "Special Forms"]]
    (for [ns- (lib-namespaces)]
      [:div
       [:a {:href (urls/pretty (urls/ref-ns ns-))} ns-]])
    [:div "Compiler"]
    (for [ns- (compiler-namespaces)]
      [:div
       [:a {:href (urls/pretty (urls/ref-compiler-ns ns-))} ns-]])])

(defn sidebar-layout [& columns]
  (case (count columns)
    1 (first columns)
    2 [:div.container
        [:div.row
          [:div.three.columns (first columns)]
          [:div.nine.columns (second columns)]]]
    3 [:div.container
        [:div.row
          [:div.three.columns (first columns)]
          [:div.three.columns (second columns)]
          [:div.six.columns (nth columns 2)]]]
    nil))

;;---------------------------------------------------------------
;; Page Rendering
;;---------------------------------------------------------------

(defn fullname->url [full-name]
  (let [{:keys [ns name-encode]} (get-in api [:symbols full-name])]
    (urls/pretty (urls/ref-symbol ns name-encode))))

(defn history-string [history]
  (let [change-str {"-" "Removed in "
                    "+" "Added in "}]
    (string/join ", "
      (for [[change version] history]
        (str (change-str change) version)))))

(defn sym-source
  [{:keys [title code repo filename] :as source}]
  (list
    [:div
      (:title source) " @ "
      [:a {:href (:url source)}
        (str repo ":" filename)]]
    [:pre [:code (highlight-code (:code source) "clj")]]
    [:hr]))

(defn api-sym-page [sym]
  [:div
    [:h1 (or (:display sym) (:full-name sym))]
    (when-let [name (:known-as sym)]
      [:em "known as " name])
    (when-let [full-name (:moved sym)]
      [:em [:strong "MOVED"] ", please see " full-name])
    [:table
      [:tr
        [:td (:type sym)]
        [:td (history-string (:history sym))]
        (when-let [{:keys [full-name url]} (:clj-equiv sym)]
          [:td
            (when (= "clojure" (-> sym :source :repo))
              "imported ")
            [:a {:href url}
              [:img {:src "/img/clojure-icon.gif"
                     :height "24px"
                     :valign "middle"}]
              " " full-name]])
        (when-let [{:keys [clj-url edn-url]} (:syntax-equiv sym)]
          (list
            (when clj-url
              [:td
                [:a {:href clj-url}
                  [:img {:src "/img/clojure-icon.gif"
                         :height "24px"
                         :valign "middle"}]
                  " in clojure"]])
            (when edn-url
              [:td [:a {:href edn-url} " in edn"]])))]]

    (when-let [usage (seq (:usage sym))]
      [:ul
        (for [u usage]
          [:li [:code u]])])
    [:hr]
    (when-let [md-desc (:description sym)]
      (list
        [:div (markdown/render md-desc)]
        [:hr]))
    (when-let [examples (seq (:examples sym))]
       (list
         [:h3 "Examples:"]
         (for [example examples]
           (list
             [:div (markdown/render (:content example))]
             [:hr]))))
    (when-let [related (seq (:related sym))]
      (list
        [:h3 "See Also:"]
        [:ul
          (for [full-name related]
            [:li [:a {:href (fullname->url full-name)} full-name]])]
        [:hr]))
    (when-let [docstring (:docstring sym)]
      (list
        "Source docstring:"
        [:pre [:code docstring]]))
    (when-let [source (:source sym)]
      (sym-source source))
    (when-let [extra-sources (seq (:extra-sources sym))]
      (for [source extra-sources]
        (sym-source source)))

    [:div
      [:a {:href (:cljsdoc-url sym)} "Edit Here!"]]])

(defn api-index-page [syms]
  (sidebar-layout
    (overview-sidebar)
    [:div
      [:h3 "API Documentation"]
      [:p "This is a temporary index of all API symbols."]
      [:ul
        (for [{:keys [full-name]} syms]
          [:li [:a {:href (fullname->url full-name)} full-name]])]]))

(defn create-sym-page! [{:keys [ns name-encode] :as sym}]
  (->> (api-sym-page sym)
       (common-layout)
       (hiccup/render)
       (urls/write! (urls/ref-symbol ns name-encode))))

(defn create-index-page! [syms]
  (->> (api-index-page syms)
       (common-layout)
       (hiccup/render)
       (urls/write! urls/ref-index)))

(defn render! []
  (doseq [ns (keys (:namespaces api))]
    (urls/make-dir! (urls/ref-ns ns)))
  (let [syms (->> (vals (:symbols api))
                  (sort-by :full-name))]
    (create-index-page! syms)
    (doseq [sym syms]
      (console/replace-line "Creating page for" (:full-name sym))
      (create-sym-page! sym))
    (console/replace-line "Done creating docs pages."))
  (println))
