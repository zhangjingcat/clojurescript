;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.spec.test
  (:require-macros [cljs.spec.test :refer [with-instrument-disabled]])
  (:require
    [goog.userAgent.product :as product]
    [clojure.string :as str]
    [cljs.stacktrace :as st]
    [cljs.pprint :as pp]
    [cljs.spec :as s]
    [cljs.spec.impl.gen :as gen]
    [clojure.test.check]
    [clojure.test.check.properties]))

(defn ->sym
  [x]
  (@#'s/->sym x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; instrument ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:dynamic *instrument-enabled*
  "if false, instrumented fns call straight through"
  true)

(defn get-host-port []
  (if (not= "browser" *target*)
    {}
    {:host (.. js/window -location -host)
     :port (.. js/window -location -port)}))

(defn get-ua-product []
  (if (not= "browser" *target*)
    (keyword *target*)
    (cond
      product/SAFARI :safari
      product/CHROME :chrome
      product/FIREFOX :firefox
      product/IE :ie)))

(defn get-env []
  {:ua-product (get-ua-product)})

(defn- fn-spec?
  "Fn-spec must include at least :args or :ret specs."
  [m]
  (or (:args m) (:ret m)))

;; wrap spec/explain-data until specs always return nil for ok data
(defn- explain-data*
  [spec v]
  (when-not (s/valid? spec v nil)
    (s/explain-data spec v)))

(defn- spec-checking-fn
  [v f fn-spec]
  (let [fn-spec (@#'s/maybe-spec fn-spec)
        conform! (fn [v role spec data args]
                   (let [conformed (s/conform spec data)]
                     (if (= ::s/invalid conformed)
                       (let [caller (-> (st/parse-stacktrace
                                          (get-host-port)
                                          (.-stack (js/Error.))
                                          (get-env) nil)
                                      first)
                             ed (merge (assoc (s/explain-data* spec [role] [] [] data)
                                         ::s/args args
                                         ::s/failure :instrument)
                                  (when caller
                                    {::caller caller}))]
                         (throw (ex-info
                                  (str "Call to " v " did not conform to spec:\n" (with-out-str (s/explain-out ed)))
                                  ed)))
                       conformed)))]
    (fn
      [& args]
      (if *instrument-enabled*
        (with-instrument-disabled
          (when (:args fn-spec) (conform! v :args (:args fn-spec) args args))
          (binding [*instrument-enabled* true]
            (apply f args)))
        (apply f args)))))

(defn- no-fspec
  [v spec]
  (ex-info (str "Fn at " v " is not spec'ed.")
    {:var v :spec spec ::s/failure :no-fspec}))

(defonce ^:private instrumented-vars (atom {}))

(defn- instrument-choose-fn
  "Helper for instrument."
  [f spec sym {over :gen :keys [stub replace]}]
  (if (some #{sym} stub)
    (-> spec (s/gen over) gen/generate)
    (get replace sym f)))

(defn- instrument-choose-spec
  "Helper for instrument"
  [spec sym {overrides :spec}]
  (get overrides sym spec))

(defn- instrument-1*
  [s v opts]
  (let [spec (s/get-spec v)
        {:keys [raw wrapped]} (get @instrumented-vars v)
        current @v
        to-wrap (if (= wrapped current) raw current)
        ospec (or (instrument-choose-spec spec s opts)
                (throw (no-fspec v spec)))
        ofn (instrument-choose-fn to-wrap ospec s opts)
        checked (spec-checking-fn v ofn ospec)]
    (swap! instrumented-vars assoc v {:raw to-wrap :wrapped checked})
    checked))

(defn- unstrument-1*
  [s v]
  (when v
    (when-let [{:keys [raw wrapped]} (get @instrumented-vars v)]
      (swap! instrumented-vars dissoc v)
      (let [current @v]
        (when (= wrapped current)
          raw)))))

;; wrap and unwrap spec failure data in an exception so that
;; quick-check will treat it as a failure.
(defn- wrap-failing
  [explain-data step]
  (ex-info "Wrapper" {::check-call (assoc explain-data :failed-on step)}))

(defn- unwrap-failing
  [ret]
  (let [ret (if-let [explain (-> ret :result ex-data ::check-call)]
              (assoc ret :result explain)
              ret)]
    (if-let [shrunk-explain (-> ret :shrunk :result ex-data ::check-call)]
      (assoc-in ret [:shrunk :result] shrunk-explain)
      ret)))

(defn- check-call
  "Returns true if call passes specs, otherwise *returns* an exception
with explain-data plus a :failed-on key under ::check-call."
  [f specs args]
  (let [cargs (when (:args specs) (s/conform (:args specs) args))]
    (if (= cargs ::s/invalid)
      (wrap-failing (explain-data* (:args specs) args) :args)
      (let [ret (apply f args)
            cret (when (:ret specs) (s/conform (:ret specs) ret))]
        (if (= cret ::s/invalid)
          (wrap-failing (explain-data* (:ret specs) ret) :ret)
          (if (and (:args specs) (:ret specs) (:fn specs))
            (if (s/valid? (:fn specs) {:args cargs :ret cret})
              true
              (wrap-failing (explain-data* (:fn specs) {:args cargs :ret cret}) :fn))
            true))))))

(defn check-fn
  "Check a function using provided specs and test.check.
Same options and return as check-var"
  [f specs
   & {:keys [num-tests seed max-size reporter-fn]
      :or {num-tests 100 max-size 200 reporter-fn (constantly nil)}}]
  (let [g (s/gen (:args specs))
        prop (gen/for-all* [g] #(check-call f specs %))]
    (let [ret (gen/quick-check num-tests prop :seed seed :max-size max-size :reporter-fn reporter-fn)]
      (if-let [[smallest] (-> ret :shrunk :smallest)]
        (unwrap-failing ret)
        ret))))

(defn check-var
  "Checks a var's specs using test.check. Optional args are
passed through to test.check/quick-check:

  num-tests     number of tests to run, default 100
  seed          random seed
  max-size      how large an input to generate, max 200
  reporter-fn   reporting fn

Returns a map as quick-check, with :explain-data added if
:result is false."
  [v & opts]
  (let [fnspec (s/get-spec v)]
    (if (:args fnspec)
      (apply check-fn @v fnspec opts)
      (throw (js/Error. (str  "No :args spec for " v))))))

(defn- run-var-tests
  "Helper for run-tests, run-all-tests."
  [vs]
  (let [reporter-fn println]
    (reduce
      (fn [totals v]
        (let [_  (println "Checking" v)
              ret (check-var v :reporter-fn reporter-fn)]
          (prn ret)
          (cond-> totals
            true (update :test inc)
            (true? (:result ret)) (update :pass inc)
            (::s/problems (:result ret)) (update :fail inc)
            (instance? js/Error (:result ret)) (update :error inc))))
      {:test 0, :pass 0, :fail 0, :error 0}
      vs)))


(comment
  (require '[cljs.pprint :as pp]
    '[cljs.spec :as s]
    '[cljs.spec.impl.gen :as gen]
    '[cljs.test :as ctest])

  (require :reload '[cjls.spec.test :as test])

  ;; discover speced vars for your own test runner
  (s/speced-vars)

  ;; check a single var
  (test/check-var #'-)
  (test/check-var #'+)
  (test/check-var #'clojure.spec.broken-specs/throwing-fn)

  ;; old style example tests
  (ctest/run-all-tests)

  (s/speced-vars 'clojure.spec.correct-specs)
  ;; new style spec tests return same kind of map
  (test/check-var #'subs)
  (cljs.spec.test/run-tests 'clojure.core)
  (test/run-all-tests)

  )





