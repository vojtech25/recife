(ns recife.alloy
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk])
  (:import
   (edu.mit.csail.sdg.parser CompUtil)
   (edu.mit.csail.sdg.translator
    A4Options A4SolutionReader A4Solution A4Options$SatSolver TranslateAlloyToKodkod)
   (edu.mit.csail.sdg.alloy4viz VizGUI)
   (edu.mit.csail.sdg.alloy4 TableView Computer)))

(defn macroexpand-all
  [form]
  (walk/prewalk (fn [x]
                  (cond
                    (and (seq? x)
                         (symbol? (first x))
                         (resolve (first x))
                         ;; XXX: `and` and `or` macroexpand to `let*`, so they become
                         ;; a very messy form for alloy.
                         (contains? #{'clojure.core/and
                                      'clojure.core/or}
                                    (symbol (resolve (first x)))))
                    (apply list (first x) (mapv macroexpand-all (rest x)))

                    (seq? x) (macroexpand x)
                    :else x))
                form))

(defonce ^:private current-state
  (atom {:viz-gui nil
         :ans nil
         :ans-index 0}))

(defn next-solution!
  []
  (let [{:keys [:viz-gui :ans]} @current-state
        next-ans (.next ans)]
    (when (.satisfiable next-ans)
      (swap! current-state assoc :ans next-ans)
      (swap! current-state update :ans-index inc)
      (.writeXML next-ans "alloy_example_output.xml")
      (when viz-gui
        (.loadXML viz-gui "alloy_example_output.xml" true))
      true)))

(defn run-alloy-expression
  [module-contents {:keys [:expression :reuse-ans?]}]
  (let [reporter (edu.mit.csail.sdg.alloy4.A4Reporter.)
        world (CompUtil/parseEverything_fromString reporter module-contents)
        opt (A4Options.)]
    (set! (.-solver opt) A4Options$SatSolver/GlucoseJNI)
    (set! (.-skolemDepth opt) 1)
    (last
     (for [alloy-cmd (.getAllCommands world)]
       (let [ans' (TranslateAlloyToKodkod/execute_commandFromBook
                   reporter (.getAllReachableSigs world) alloy-cmd opt)
             {:keys [:ans :ans-index]} (if reuse-ans?
                                         ;; XXX: Call (.next ans) n times so we are at the same solution
                                         ;; and can "reuse" it.
                                         {:ans (if (zero? (:ans-index @current-state))
                                                 ans'
                                                 (reduce (fn [current-ans _]
                                                           (.next current-ans))
                                                         ans'
                                                         (range (:ans-index @current-state))))
                                          :ans-index (:ans-index @current-state)}
                                         {:ans ans'
                                          :ans-index 0})]
         (when (.satisfiable ans)
           (swap! current-state assoc :ans ans :ans-index ans-index)
           (.writeXML ans "alloy_example_output.xml")
           #_(if (nil? (:viz-gui @current-state))
               (do (swap! current-state assoc
                          :viz-gui (VizGUI. true "alloy_example_output.xml" nil
                                            (reify Computer
                                              (compute [_ _]
                                                (next-solution!)
                                                ""))
                                            nil
                                            true))
                   (.setFocusableWindowState (.getFrame (:viz-gui @current-state)) false))
               (.loadXML (:viz-gui @current-state) "alloy_example_output.xml" true))
           (->> expression
                (CompUtil/parseOneExpression_fromString world)
                (.eval ans))))))))

(defn parse-alloy-result
  [alloy-result]
  (cond
    (instance? Iterable alloy-result)
    (->> alloy-result
         (mapv (fn [tuple]
                 (let [atoms (->> (mapv #(.atom tuple %) (range (.arity tuple)))
                                  (mapv symbol))]
                   (if (= (count atoms) 1)
                     (first atoms)
                     atoms))))
         (into #{}))

    :else alloy-result))

(defn build-alloy-module
  [{:keys [:rec.signature/sigs :rec.signature/module]}
   constraints
   {:keys [:run]}]
  ;; Reset sigs cache.
  (run! #(reset! (:rec.alloy/result-cache %) nil) sigs)
  (let [module-body (apply str
                           ;; Require ordering.
                           (->> sigs
                                (filter :rec.alloy/ordered?)
                                (mapv #(format "open util/ordering[%s] as %s_ordering"
                                               (:rec.alloy/name %)
                                               (:rec.alloy/name %)))
                                (str/join "\n"))
                           "\n\n"
                           (->> (apply conj sigs constraints)
                                (mapv :rec.alloy/form)
                                (str/join "\n\n"))
                           "\n\n"
                           run)]
    (reset! module module-body)
    module-body))

(defn custom-munge
  [s]
  (cond
    (not (or (string? s) (symbol? s) (keyword? s))) s
    (contains? #{"-" "+"} (name s)) s
    :else (let [munged-s (munge (name s))]
            (symbol (-> munged-s
                        (str/replace "_SINGLEQUOTE_" "'")
                        (str/replace "_LBRACE_" "{")
                        (str/replace "_RBRACE_" "}")
                        (str/replace "_PLUS_" " + ")
                        symbol)))))

(defn- parse-args
  [args]
  (->> args
       (partition 3)
       (mapv (fn [[var _ var-type]]
               (format "%s: %s"
                       (custom-munge var)
                       (if (coll? var-type)
                         (->> var-type
                              (mapv (comp custom-munge clojure.core/name))
                              (str/join " "))
                         (custom-munge var-type)))))
       (str/join ", ")))

(defn- parse-type
  [type]
  (format "%s"
          (->> type
               (mapv (comp custom-munge clojure.core/name))
               (str/join " "))))

(defn parse
  [expr]
  #_(def expr expr)
  (let [expr (macroexpand-all expr)]
    (cond
      (set? expr)
      (format "{%s}"
              (->> expr
                   ;; FIXME: A set can be made of other things besides a symbolm or keyword.
                   (mapv (comp parse name))
                   (str/join " + ")))

      (coll? expr)
      (format
       "{%s}"
       (let [op' (first expr)
             op (if (or (special-symbol? op')
                        (keyword? op'))
                  op'
                  (symbol (resolve op')))]
         (cond
           (= op 'if)
           (format "%s implies %s%s"
                   (parse (nth expr 1))
                   (parse (nth expr 2))
                   ;; TODO: Have to check the else branch, it is optional.
                   (if (> (count expr) 3)
                     (str " else " (parse (nth expr 3)))
                     ""))

           (= op 'let*)
           (format "let %s {\n  %s\n}"
                   (->> (nth expr 1)
                        (partition 2)
                        (mapv (fn [[sym v]]
                                (format "%s = %s" (custom-munge sym) (parse v))))
                        (str/join ", "))
                   (parse (nth expr 2)))

           ;; TODO: Maybe we can always use keywords as relations.
           (keyword? op)
           (format "%s.%s" (parse (nth expr 1)) (name (nth expr 0)))

           (= op 'clojure.core/=)
           (format "%s = %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'clojure.core/empty?)
           (format "%s = none" (parse (nth expr 1)))

           (= op 'clojure.core/not)
           (format "!%s" (parse (nth expr 1)))

           (= op 'clojure.core/not=)
           (format "%s != %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'clojure.core/count)
           (format "#%s" (parse (nth expr 1)))

           (contains? #{'clojure.core/>
                        'clojure.core/<
                        'clojure.core/>=
                        'clojure.core/<=}
                      op)
           (format "%s %s %s" (parse (nth expr 1)) (name (nth expr 0)) (parse (nth expr 2)))

           (= op 'clojure.core/and)
           (->> (rest expr)
                (mapv parse)
                (str/join "\nand \n"))

           (= op 'clojure.core/or)
           (->> (rest expr)
                (mapv parse)
                (str/join "\nor \n"))

           (= op 'clojure.core/+)
           (cond
             (= (count (next expr)) 1)
             (parse (second expr))

             (= (count (next expr)) 2)
             (format "add[%s]"
                     (->> (next expr)
                          (mapv parse)
                          (str/join "][")))

             :else
             (->> (next expr)
                  (partition-all 2)
                  (map #(apply list `+ %))
                  (#(apply list `+ %))
                  parse))

           (= op 'clojure.core/-)
           (cond
             (= (count (next expr)) 1)
             (parse (second expr))

             (= (count (next expr)) 2)
             (format "sub[%s]"
                     (->> (next expr)
                          (mapv parse)
                          (str/join "][")))

             :else
             (->> (next expr)
                  (partition-all 2)
                  (map #(apply list `- %))
                  (#(apply list `- %))
                  parse))

           (= op 'clojure.core/*)
           (cond
             (= (count (next expr)) 1)
             (parse (second expr))

             (= (count (next expr)) 2)
             (format "mul[%s]"
                     (->> (next expr)
                          (mapv parse)
                          (str/join "][")))

             :else
             (->> (next expr)
                  (partition-all 2)
                  (map #(apply list `* %))
                  (#(apply list `* %))
                  parse))

           ;; FIXME: We probably should create `max*` as this alloy function receives a set instead
           ;; of multiple arguments.
           (= op 'clojure.core/max)
           (format "max[%s]" (last expr))

           ;; TODO: We are gonna to assume for now a two-arity function (and for other functions too!).
           (= op 'clojure.set/difference)
           (format "%s - %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'clojure.set/union)
           (->> (rest expr)
                (mapv parse)
                (str/join " + "))

           (= op 'clojure.set/subset?)
           (format "%s in %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'clojure.set/intersection)
           (format "%s & %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'recife.alloy/union)
           (->> (rest expr)
                (mapv parse)
                (str/join " + "))

           (= op 'recife.alloy/difference)
           (->> (rest expr)
                (mapv parse)
                (str/join " - "))

           (= op 'recife.alloy/subset?)
           (format "%s in %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'recife.alloy/all)
           (format "all %s | {\n  %s\n}"
                   (parse-args (nth expr 1)) (parse (nth expr 2)))

           (= op 'recife.alloy/one)
           (format "one %s"
                   (parse-args (nth expr 1)))

           (= op 'recife.alloy/cartesian)
           (format "%s -> %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'recife.alloy/trans)
           (format "^%s" (parse (nth expr 1)))

           (= op 'recife.alloy/<*)
           (format "%s <: %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'recife.alloy/>*)
           (format "%s :> %s" (parse (nth expr 1)) (parse (nth expr 2)))

           (= op 'recife.alloy/join)
           (->> (rest expr)
                (mapv parse)
                (str/join "."))

           (= op 'recife.alloy/some*)
           (format "some %s"
                   (parse (nth expr 1)))

           (= op 'recife.alloy/select)
           (format "{%s: %s | %s}"
                   ;; XXX: arg from a `fn*` (only one supported for now)
                   (ffirst (second (nth expr 1)))
                   (parse (nth expr 2))
                   ;; XXX: body from a `fn*`
                   (parse (second (second (nth expr 1)))))

           (= op 'recife.alloy/++)
           (->> (rest expr)
                (mapv parse)
                (str/join " ++ "))

           ;; FIXME: This works only for the ordering module, let's fix it later.
           ;; Also it should accept arguments.
           (:rec.alloy/module-required (meta (resolve op)))
           (format "%s_ordering/%s[%s]"
                   (nth expr 1)
                   (name (nth expr 0))
                   (if (> (count expr) 2)
                     (parse (nth expr 2))
                     ""))

           :else
           (format "%s[%s]"
                   (parse (nth expr 0))
                   (->> (next expr)
                        (mapv parse)
                        (str/join "]["))))))

      :else
      (custom-munge expr))))

(defn eval-module-expression
  [sig-map expression]
  (some-> @(:rec.alloy/module sig-map)
          (run-alloy-expression {:expression expression
                                 :reuse-ans? true})
          parse-alloy-result))

(defn- sig-map->set
  [sig-map]
  (let [result (or #_@(:rec.alloy/result-cache sig-map)
                   (eval-module-expression sig-map (str (:rec.alloy/name sig-map))))]
    (reset! (:rec.alloy/result-cache sig-map) result)
    (if (some? result)
      result
      #{})))

(declare invoke*)

(deftype AlloySignature [sig-map]
  clojure.lang.IPersistentSet
  (disjoin [_ k]
    (.disjoin (sig-map->set sig-map) k))
  (contains [_ v]
    (.contains (sig-map->set sig-map) v))
  (get [_ k]
    (if (keyword? k)
      (.get sig-map k)
      (.get (sig-map->set sig-map sig-map) k)))
  (empty [_]
    (.empty (sig-map->set sig-map)))
  (count [_]
    (.count (sig-map->set sig-map)))
  (seq [_]
    (.seq (sig-map->set sig-map)))
  (equiv [this obj]
    (= (.hashCode this) (.hashCode obj)))
  (cons [_ v]
    (.cons (sig-map->set sig-map) v))

  Object
  (toString [_]
    (if (contains? #{:rec.type/pred :rec.type/fun :rec.type/fact :rec.type/assertion}
                   (:rec.alloy/type sig-map))
      (.toString (:rec.alloy/form sig-map))
      (.toString (sig-map->set sig-map))))
  (hashCode [_]
    (.hashCode (sig-map->set sig-map)))

  clojure.lang.IFn
  (invoke [this arg1] (invoke* this [arg1]))
  (invoke [this arg1 arg2] (invoke* this [arg1 arg2]))
  (invoke [this arg1 arg2 arg3] (invoke* this [arg1 arg2 arg3]))
  (invoke [this arg1 arg2 arg3 arg4] (invoke* this [arg1 arg2 arg3 arg4]))
  (invoke [this arg1 arg2 arg3 arg4 arg5] (invoke* this [arg1 arg2 arg3 arg4 arg5]))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6] (invoke* this [arg1 arg2 arg3 arg4 arg5 arg6]))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7] (invoke* this [arg1 arg2 arg3 arg4 arg5 arg6 arg7]))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8] (invoke* this [arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8])))

(defmethod print-method AlloySignature [c, ^java.io.Writer w]
  (.write w (.toString c)))

(defmethod clojure.pprint/simple-dispatch AlloySignature [c]
  (let [sig-map (.-sig-map c)]
    (if (contains? #{:rec.type/pred :rec.type/fun :rec.type/fact :rec.type/assertion :rec.type/check}
                   (:rec.alloy/type sig-map))
      (println (:rec.alloy/form sig-map))
      (println (sig-map->set (.-sig-map c))))))

(defn- invoke*
  [this args]
  (let [obj (first args)]
    (case (:rec.alloy/type (.-sig-map this))
      (:rec.type/relation :rec.type/sig)
      (let [expression
            (format "{%s.%s}"
                    (:rec.alloy/name (.-sig-map obj))
                    (:rec.alloy/name (.-sig-map this)))]
        (AlloySignature. (assoc (.-sig-map this)
                                :rec.alloy/result-cache (atom nil)
                                :rec.alloy/name expression)))

      :rec.type/pred
      (let [expression
            (format "{%s[%s]}"
                    (:rec.alloy/name (.-sig-map this))
                    (->> args
                         (mapv #(:rec.alloy/name (.-sig-map %)))
                         (str/join ", ")))]
        (AlloySignature. (assoc (.-sig-map obj)
                                :rec.alloy/result-cache (atom nil)
                                :rec.alloy/type :rec.type/pred-call
                                :rec.alloy/name expression))))))

(defn trans
  "Transitive closure operator (`^`)."
  [alloy-obj]
  (let [sig-map (.sig-map alloy-obj)]
    (AlloySignature. (assoc sig-map
                            :rec.alloy/result-cache (atom nil)
                            :rec.alloy/name (format "^%s" (:rec.alloy/name sig-map))))))

(defn pred
  [name args & body]
  (AlloySignature.
   {:rec.alloy/name (custom-munge name)
    :rec.alloy/type :rec.type/pred
    :rec.alloy/form (format "pred %s[%s] {\n  %s\n}"
                            (custom-munge name)
                            (parse-args args)
                            (parse (macroexpand-all (first body))))}))

(defmacro defpred
  [name args & body]
  `(def ~name
     (pred '~name '~args '~@body)))

(defmacro deffun
  [name _ return-type args & body]
  `(def ~name
     (AlloySignature.
      {:rec.alloy/name '~(custom-munge name)
       :rec.alloy/type :rec.type/fun
       :rec.alloy/form ~(format "fun %s[%s] : %s {\n  %s\n}"
                                (custom-munge name)
                                (parse-args args)
                                (parse-type return-type)
                                (parse (macroexpand-all (first body))))})))

(defn fact
  [name & body]
  (AlloySignature.
   {:rec.alloy/name (custom-munge name)
    :rec.alloy/type :rec.type/fact
    :rec.alloy/form (format "fact %s {\n  %s\n}"
                            (custom-munge name)
                            (parse (macroexpand-all (first body))))}))

(defmacro deffact
  [name & body]
  `(def ~name
     (fact '~name '~@body)))

(defmacro defassert
  [name & body]
  `(def ~name
     (AlloySignature.
      {:rec.alloy/name '~(custom-munge name)
       :rec.alloy/type :rec.type/assertion
       :rec.alloy/form ~(format "assert %s {\n  %s\n}"
                                (custom-munge name)
                                (parse (macroexpand-all (first body))))})))

(defn check
  [name & body]
  (AlloySignature.
   {:rec.alloy/name (custom-munge name)
    :rec.alloy/type :rec.type/check
    :rec.alloy/form (format "check %s {\n  %s\n}"
                            (custom-munge name)
                            (parse (macroexpand-all (first body))))}))

(defmacro defcheck
  [name & body]
  `(def ~name
     (check '~name '~@body)))

(defn- parse-sig-description
  [v]
  (cond
    (or (symbol? v) (keyword? v) (string? v))
    (name v)

    (seq? v)
    (->> v
         (mapv parse-sig-description)
         (str/join " ")
         (format "(%s)"))

    :else
    v))

(defn sig
  [name opts {:keys [:module-atom]}]
  (AlloySignature.
   {:rec.alloy/name (clojure.core/name name)
    :rec.alloy/type :rec.type/sig
    :rec.alloy/ordered? (boolean (:ordered? opts))
    :rec.alloy/module module-atom
    :rec.alloy/result-cache (atom nil)
    :rec.alloy/relations (->> (:relations opts)
                              (mapv (fn [[relation _]]
                                      (AlloySignature.
                                       {:rec.alloy/name (custom-munge (clojure.core/name relation))
                                        :rec.alloy/type :rec.type/relation
                                        :rec.alloy/parent name
                                        :rec.alloy/module module-atom
                                        :rec.alloy/result-cache (atom nil)}))))
    :rec.alloy/form (format "%ssig %s %s {%s}"
                            (cond
                              (:abstract? opts) "abstract "
                              (:multiplicity opts) (str (clojure.core/name (:multiplicity opts)) " ")
                              :else "")
                            (custom-munge (clojure.core/name name))
                            (or (some->> (:extends opts)
                                         clojure.core/name
                                         (str "extends "))
                                "")
                            (or (some->> (:relations opts)
                                         (mapv (fn [[relation description]]
                                                 (format "%s: %s"
                                                         (custom-munge (clojure.core/name relation))
                                                         (->> description
                                                              (mapv parse-sig-description)
                                                              (str/join " ")))))
                                         (str/join ", "))
                                ""))}))

(defmacro defsig
  [name opts {:keys [:module-atom]}]
  `(do
     (def ~name
       (AlloySignature.
        {:rec.alloy/name '~(clojure.core/name name)
         :rec.alloy/type :rec.type/sig
         :rec.alloy/ordered? ~(boolean (:ordered? opts))
         :rec.alloy/module ~module-atom
         :rec.alloy/result-cache (atom nil)
         ;; Create relations and define vars for them.
         :rec.alloy/relations ~(->> (:relations opts)
                                    (mapv (fn [[relation _]]
                                            `(def ~(symbol relation)
                                               (AlloySignature.
                                                {:rec.alloy/name '~(custom-munge (clojure.core/name relation))
                                                 :rec.alloy/type :rec.type/relation
                                                 :rec.alloy/parent ~name
                                                 :rec.alloy/module ~module-atom
                                                 :rec.alloy/result-cache (atom nil)})))))
         :rec.alloy/form ~(format "%ssig %s %s {%s}"
                                  (cond
                                    (:abstract? opts) "abstract "
                                    (:multiplicity opts) (str (clojure.core/name (:multiplicity opts)) " ")
                                    :else "")
                                  (custom-munge (clojure.core/name name))
                                  (or (some->> (:extends opts)
                                               clojure.core/name
                                               (str "extends "))
                                      "")
                                  (or (some->> (:relations opts)
                                               (mapv (fn [[relation description]]
                                                       (format "%s: %s"
                                                               (custom-munge (clojure.core/name relation))
                                                               (->> description
                                                                    (mapv parse-sig-description)
                                                                    (str/join " ")))))
                                               (str/join ", "))
                                      ""))}))
     ~name))

(defn signatures
  [sigs]
  (let [module-atom (atom nil)]
    {:rec.signature/sigs
     (->> sigs
          ;; XXX: Put abstracts first because is was some differences
          ;; at the ordering that I didn't investigate further.
          (sort-by (fn [[_sig-name opts]]
                     (:abstract? opts)))
          reverse
          (mapv (fn [[sig-name opts]]
                  (sig (symbol (name sig-name)) opts {:module-atom module-atom}))))

     :rec.signature/module module-atom}))

(defn- create-alloy-obj
  [existing-alloy-obj expression]
  (AlloySignature. {:rec.alloy/name expression
                    :rec.alloy/type :rec.type/expression
                    :rec.alloy/module (:rec.alloy/module (.-sig-map existing-alloy-obj))
                    :rec.alloy/result-cache (atom nil)}))

;; Operators

(declare all)

(declare one)

(declare ++)

(declare some*)

(declare subset?)

(declare union)

(declare difference)

(declare <*)

(declare >*)

(declare select)

(defn cartesian
  [x y])

(defn join
  [& args]
  (create-alloy-obj (first args)
                    (->> (mapv :rec.alloy/name args)
                         (str/join "."))))
