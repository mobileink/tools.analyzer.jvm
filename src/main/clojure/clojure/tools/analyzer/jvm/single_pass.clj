(ns clojure.tools.analyzer.jvm.single-pass
  "Interface to clojure.lang.Compiler/analyze.

  Entry point `analyze-path` and `analyze-one`"
  (:refer-clojure :exclude [macroexpand])
  (:import (java.io LineNumberReader InputStreamReader PushbackReader)
           (clojure.lang RT LineNumberingPushbackReader Compiler$DefExpr Compiler$LocalBinding Compiler$BindingInit Compiler$LetExpr
                         Compiler$LetFnExpr Compiler$StaticMethodExpr Compiler$InstanceMethodExpr Compiler$StaticFieldExpr
                         Compiler$NewExpr Compiler$EmptyExpr Compiler$VectorExpr Compiler$MonitorEnterExpr
                         Compiler$MonitorExitExpr Compiler$ThrowExpr Compiler$InvokeExpr Compiler$TheVarExpr Compiler$VarExpr
                         Compiler$UnresolvedVarExpr Compiler$ObjExpr Compiler$NewInstanceMethod Compiler$FnMethod Compiler$FnExpr
                         Compiler$NewInstanceExpr Compiler$MetaExpr Compiler$BodyExpr Compiler$ImportExpr Compiler$AssignExpr
                         Compiler$TryExpr$CatchClause Compiler$TryExpr Compiler$C Compiler$LocalBindingExpr Compiler$RecurExpr
                         Compiler$MapExpr Compiler$IfExpr Compiler$KeywordInvokeExpr Compiler$InstanceFieldExpr Compiler$InstanceOfExpr
                         Compiler$CaseExpr Compiler$Expr Compiler$SetExpr Compiler$MethodParamExpr Compiler$KeywordExpr
                         Compiler$ConstantExpr Compiler$NumberExpr Compiler$NilExpr Compiler$BooleanExpr Compiler$StringExpr
                         Compiler$ObjMethod Compiler$Expr))
  (:require [clojure.reflect :as reflect]
            [clojure.java.io :as io]
            [clojure.repl :as repl]
            [clojure.string :as string]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.passes.jvm.emit-form :as emit-form]
            [clojure.tools.analyzer.passes.uniquify :refer [uniquify-locals]]
            [clojure.tools.analyzer.passes.jvm.infer-tag :refer [infer-tag]]
            [clojure.tools.analyzer.env :as env]
            [clojure.tools.analyzer.jvm :as taj]
            [clojure.tools.analyzer.jvm.utils :as ju]
            [clojure.tools.analyzer.utils :as u]))

;;;;;;;;;;;;;;;;;;;;;;;;
;; Interface

(def ^:dynamic *eval-ast* 
  "If true, evaluate the output AST before returning.
  Otherwise, AST is unevaluated. Defaults to false."
  false)

(declare analyze-one)

(defn analyze-form
  ([form] (analyze-form form {}))
  ([form opt]
   (env/ensure (ana.jvm/global-env)
     (-> (analyze-one (merge (taj/empty-env) (select-keys (meta form) [:line :column :ns :file])) form opt)
         uniquify-locals))))

(defmacro ast
  "Returns the abstract syntax tree representation of the given form,
  evaluated in the current namespace"
  ([form] `(ast ~form {}))
  ([form opt]
   `(analyze-form '~form ~opt)))

;;;;;;;;;;;;;;;;;;;;;;;
;; Utils

(defmacro field
  "Call a private field, must be known at compile time. Throws an error
  if field is already publicly accessible."
  ([class-obj field] `(field ~class-obj ~field nil))
  ([class-obj field obj]
   (let [{class-flags :flags :keys [members]} (reflect/reflect (resolve class-obj))
         {field-flags :flags} (some #(and (= (:name %) field) %) members)]
     (assert field-flags
             (str "Class " (resolve class-obj) " does not have field " field))
     (assert (not (and (:public class-flags)
                       (:public field-flags)))
             (str "Class " (resolve class-obj) " and field " field " is already public")))
   `(field-accessor ~class-obj '~field ~obj)))

(defn- field-accessor [^Class class-obj field obj]
  (let [^java.lang.reflect.Field
        field (.getDeclaredField class-obj (name field))]
    (.setAccessible field true)
    (let [ret (.get field obj)]
      (if (instance? Boolean ret)
        (boolean ret)
        ret))))

(defn- method-accessor [^Class class-obj method obj types & args]
  (let [^java.lang.reflect.Method
        method (.getMethod class-obj (name method) (into-array Class types))]
    (.setAccessible method true)
    (try
      (.invoke method obj (object-array args))
      (catch java.lang.reflect.InvocationTargetException e
        (throw (repl/root-cause e))))))

(defn- when-column-map [expr]
  (let [field (try (.getDeclaredField (class expr) "column")
                (catch Exception e))]
    (when field
      {:column (field-accessor (class expr) 'column expr)})))

(defn- when-line-map [expr]
  (let [^java.lang.reflect.Method
        method (try (.getMethod (class expr) "line" (into-array Class []))
                 (catch Exception e))
        field (try (.getDeclaredField (class expr) "line")
                (catch Exception e))]
    (cond 
      method {:line (method-accessor (class expr) 'line expr [])}
      field {:line (field-accessor (class expr) 'line expr)})))

(defn- when-source-map [expr]
  (let [field (try (.getDeclaredField (class expr) "source")
                (catch Exception e))]
    (when field
      {:file (field-accessor (class expr) 'source expr)})))

(defn- env-location [env expr]
  (merge env
         (when-line-map expr)
         (when-column-map expr)
         ;; only adds the suffix of the path
         #_(when-source-map expr)))

(defn- inherit-env [expr env]
  (merge env
         (when-let [line (-> expr :env :line)]
           {:line line})
         (when-let [column (-> expr :env :column)]
           {:column column})
         (when-let [file (-> expr :env :file)]
           {:file file})))

(defprotocol AnalysisToMap
  (analysis->map [aobj env opt]
    "Recursively converts the output of the Compiler's analysis to a map. Takes
    a map of options:
    - :children
      when true, include a :children key with all child expressions of each node
    - :java-obj
      when true, include a :java-obj key with the node's corresponding Java object"))

;; Literals extending abstract class Compiler$LiteralExpr and have public value fields

;  {:op   :const
;   :doc   "Node for a constant literal or a quoted collection literal"
;   :keys [[:form "A constant literal or a quoted collection literal"]
;          [:literal? "`true`"]
;          [:type "one of :nil, :bool, :keyword, :symbol, :string, :number, :type, :record, :map, :vector, :set, :seq, :char, :regex, :class, :var, or :unknown"]
;          [:val "The value of the constant node"]
;          ^:optional ^:children
;          [:meta "An AST node representing the metadata of the constant value, if present. The node will be either a :map node or a :const node with :type :map"]]}

(defn tag-for-val [val]
  {:post [((some-fn nil? class?) %)]}
  (let [c (ju/unbox (class val))]
    c))

(defmacro literal-dispatch [disp-class op-keyword]
  {:pre [((some-fn nil? keyword?) op-keyword)]}
  `(extend-protocol AnalysisToMap
     ~disp-class
     (analysis->map
       [expr# env# opt#]
       (let [v# (.eval expr#)
             tag# (tag-for-val v#)
                 #_(method-accessor (class expr#) '~'getJavaClass expr# [])]
         {:op :const
          :tag tag#
          :o-tag tag#
          :literal? true
          :type (or ~op-keyword
                    (u/classify v#))
          :env env#
          :val v#
          :form v#}))))

(literal-dispatch Compiler$KeywordExpr :keyword)
(literal-dispatch Compiler$NumberExpr :number)
(literal-dispatch Compiler$StringExpr :string)
(literal-dispatch Compiler$NilExpr :nil)
(literal-dispatch Compiler$BooleanExpr :bool)
(literal-dispatch Compiler$EmptyExpr nil)

(extend-protocol AnalysisToMap
  Compiler$ConstantExpr
  (analysis->map
    [expr env opt]
    (let [val (.eval expr)
          ;; t.a.j is much more specific with things like maps. 
          ;; eg. Compiler returns APersistentMap, but t.a.j has PersistentArrayMap
          tag (tag-for-val val)
                #_(method-accessor (class expr) 'getJavaClass expr [])
          inner {:op :const
                 :form val
                 :tag tag
                 :o-tag tag
                 :literal? true
                 :type (u/classify val)
                 :env env
                 :val val}]
      {:op :quote
       :form (list 'quote val)
       :literal? true
       :env env
       :tag tag
       :o-tag tag
       :expr inner
       :children [:expr]})))

(extend-protocol AnalysisToMap

  ;; def
  ; {:op   :def
  ;  :doc  "Node for a def special-form expression"
  ;  :keys [[:form "`(def name docstring? init?)`"]
  ;         [:name "The var symbol to define in the current namespace"]
  ;         [:var "The Var object created (or found, if it already existed) named by the symbol :name in the current namespace"]
  ;         ^:optional ^:children
  ;         [:meta "An AST node representing the metadata attached to :name, if present. The node will be either a :map node or a :const node with :type :map"]
  ;         ^:optional ^:children
  ;         [:init "An AST node representing the initial value of the var"]
  ;         ^:optional
  ;         [:doc "The docstring for this var"]]}
  Compiler$DefExpr
  (analysis->map
    [expr env opt]
    (let [env (env-location env expr)
          init? (field Compiler$DefExpr initProvided expr)
          init (analysis->map (field Compiler$DefExpr init expr) env opt)
          meta (when-let [meta (field Compiler$DefExpr meta expr)]
                 (analysis->map meta env opt))
          children (into (into [] (when meta [:meta]))
                         (when init? [:init]))
          ^clojure.lang.Var var (field Compiler$DefExpr var expr)
          name (.sym var)]
      (merge 
        {:op :def
         :form (list* 'def name 
                      (when init?
                        [(emit-form/emit-form init)]))
         :tag clojure.lang.Var
         :o-tag clojure.lang.Var
         :env env
         :name name
         :var var}
        (when init?
          {:init init})
        (when meta
          (assert (= :quote (:op meta)))
          {:meta (:expr meta)})
        (when-not (empty? children)
          {:children children}))))

  ;; let/loop
  ; {:op   :let
  ;  :doc  "Node for a let* special-form expression"
  ;  :keys  [[:form "`(let* [binding*] body*)`"]
  ;          ^:children
  ;          [:bindings "A vector of :binding AST nodes with :local :let"]
  ;          ^:children
  ;          [:body "Synthetic :do node (with :body? `true`) representing the body of the let expression"]]}
  ; {:op   :loop
  ;  :doc  "Node a loop* special-form expression"
  ;  :keys [[:form "`(loop* [binding*] body*)`"]
  ;         ^:children
  ;         [:bindings "A vector of :binding AST nodes with :local :loop"]
  ;         ^:children
  ;         [:body "Synthetic :do node (with :body? `true`) representing the body of the loop expression"]
  ;         [:loop-id "Unique symbol identifying this loop as a target for recursion"]]}
  Compiler$LetExpr
  (analysis->map
    [expr {:keys [context loop-id] :as env} opt]
    (let [top-env env
          loop? (.isLoop expr)]
      (loop [bindings (.bindingInits expr)
             env (u/ctx env :ctx/expr)
             binds []]
        (if-let [[binit & bindings] (seq bindings)]
          (let [bind-expr (assoc (analysis->map binit env opt)
                                 :local (if loop? :loop :let))
                name (:name bind-expr)]
            (assert (symbol? name) (str "letexpr" bind-expr))
            ;(prn "name" name (keys bind-expr) (:op bind-expr))
            (recur bindings
                   (assoc-in env [:locals name] (u/dissoc-env bind-expr))
                   (conj binds bind-expr)))
          (let [body-env (assoc env :context (if loop? :ctx/return context))
                body (analysis->map (.body expr)
                                    (merge body-env
                                           (when loop?
                                             {:loop-id     loop-id
                                              :loop-locals (count binds)}))
                                    opt)]
            {:op (if loop? :loop :let)
             :form (list (if loop? 'loop* 'let*) 'TODO)
             :env (inherit-env body top-env)
             :bindings binds
             :body body
             :children [:bindings :body]})))))

  ;{:op   :local
  ; :doc  "Node for a local symbol"
  ; :keys [[:form "The local symbol"]
  ;        [:assignable? "`true` if the corresponding :binding AST node is :local :field and is declared either ^:volatile-mutable or ^:unsynchronized-mutable"]
  ;        [:name "The uniquified local symbol"]
  ;        [:local "One of :arg, :catch, :fn, :let, :letfn, :loop, :field or :this"]
  ;        ^:optional
  ;        [:arg-id "When :local is :arg, the parameter index"]
  ;        ^:optional
  ;        [:variadic? "When :local is :arg, a boolean indicating whether this parameter binds to a variable number of arguments"]
  ;        [:atom "An atom shared by this :local node, the :binding node this local refers to and all the other :local nodes that refer to this same local"]]}
  Compiler$LocalBinding
  (analysis->map
    [lb env opt]
    (let [init (when-let [init (.init lb)]
                 (analysis->map init env opt))
          form (.sym lb)]
      {:op :local
       :name form
       :form form
       :env (inherit-env init env)
       :tag (.tag lb)
       :atom (atom {})
       :children []}))

  ;  {:op   :binding
  ;   :doc  "Node for a binding symbol"
  ;   :keys [[:form "The binding symbol"]
  ;          [:name "The uniquified binding symbol"]
  ;          [:local "One of :arg, :catch, :fn, :let, :letfn, :loop, :field or :this"]
  ;          ^:optional
  ;          [:arg-id "When :local is :arg, the parameter index"]
  ;          ^:optional
  ;          [:variadic? "When :local is :arg, a boolean indicating whether this parameter binds to a variable number of arguments"]
  ;          ^:optional ^:children
  ;          [:init "When :local is :let, :letfn or :loop, an AST node representing the bound value"]
  ;          [:atom "An atom shared by this :binding node and all the :local nodes that refer to this binding"]]}
  Compiler$BindingInit
  (analysis->map
    [bi env opt]
    (let [;; Compiler$LocalBinding
          local-binding (analysis->map (.binding bi) env opt)
          ;_ (prn "binding init local-binding" (keys local-binding)
          ;       (:op local-binding))
          init (analysis->map (.init bi) env opt)
          name (:name local-binding)]
      (assert (symbol? name) "bindinginit")
      {:op :binding
       :form name
       :name name
       :env (inherit-env init env)
       :local :unknown
       :init init
       :children [:init]}))

  ;; letfn
  Compiler$LetFnExpr
  (analysis->map
    [expr env opt]
    (let [body (analysis->map (.body expr) env opt)
          binding-inits (map analysis->map (.bindingInits expr) (repeat env) (repeat opt))]
      (merge
        {:op :letfn
         :env (inherit-env body env)
         :body body
         :binding-inits binding-inits}
        (when (:children opt)
          {:children [[[:binding-inits] {:exprs? true}]
                      [[:body] {}]]})
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  ;; LocalBindingExpr
  Compiler$LocalBindingExpr
  (analysis->map
    [expr env opt]
    {:post [%]}
    (let [b (analysis->map (.b expr) env opt)
          form (:name b)]
      (assert (symbol? form))
      (assert (contains? (:locals env) form))
      ;(prn "LocalBindingExpr" env)
      (assoc ((:locals env) form)
             :op :local
             ;; form has new metadata
             :form form
             :val form
             :env env)))

  ;; Methods
  ; {:op   :static-call
  ;  :doc  "Node for a static method call"
  ;  :keys [[:form "`(Class/method arg*)`"]
  ;         [:class "The Class the static method belongs to"]
  ;         [:method "The symbol name of the static method"]
  ;         ^:children
  ;         [:args "A vector of AST nodes representing the args to the method call"]
  ;         ^:optional
  ;         [:validated? "`true` if the static method could be resolved at compile time"]]}
  Compiler$StaticMethodExpr
  (analysis->map
    [expr env opt]
    (let [args (mapv #(analysis->map % env opt) (field Compiler$StaticMethodExpr args expr))
          method (when-let [method (field Compiler$StaticMethodExpr method expr)]
                   (@#'reflect/method->map method))
          method-name (symbol (field Compiler$StaticMethodExpr methodName expr))
          tag (field Compiler$StaticMethodExpr tag expr)
          c (field Compiler$StaticMethodExpr c expr)]
      (merge
        {:op :static-call
         :env (env-location env expr)
         :form (list '. c (list* method-name (map emit-form/emit-form args)))
         :class c
         :method method-name
         :args args
         :tag tag
         :o-tag tag
         :children [:args]}
        (when method
          {:validated? true}))))

  Compiler$InstanceMethodExpr
  ;  {:op   :instance-call
  ;   :doc  "Node for an instance method call"
  ;   :keys [[:form "`(.method instance arg*)`"]
  ;          [:method "Symbol naming the invoked method"]
  ;          ^:children
  ;          [:instance "An AST node representing the instance to call the method on"]
  ;          ^:children
  ;          [:args "A vector of AST nodes representing the args passed to the method call"]
  ;          ^:optional
  ;          [:validated? "`true` if the method call could be resolved at compile time"]
  ;          ^:optional
  ;          [:class "If :validated? the class or interface the method belongs to"]]}
  (analysis->map
    [expr env opt]
    (let [target (analysis->map (field Compiler$InstanceMethodExpr target expr) env opt)
          args (mapv #(analysis->map % env opt) (field Compiler$InstanceMethodExpr args expr))
          method-name (symbol (field Compiler$InstanceMethodExpr methodName expr))
          method (when-let [method (field Compiler$InstanceMethodExpr method expr)]
                   (@#'reflect/method->map method))
          tag (field Compiler$InstanceMethodExpr tag expr)]
      (merge
        {:op :instance-call
         :form (list '. (emit-form/emit-form target)
                     (list* method-name (map emit-form/emit-form args)))
         :env (env-location env expr)
         :instance target
         :method method-name
         :args args
         :tag tag
         :o-tag tag
         :children [:instance :args]}
        (when method
          {:validated? true}))))

  ;; Fields
  Compiler$StaticFieldExpr
  (analysis->map
    [expr env opt]
    (let []
      (merge
        {:op :static-field
         :env (env-location env expr)
         :class (field Compiler$StaticFieldExpr c expr)
         :field-name (field Compiler$StaticFieldExpr fieldName expr)
         :field (when-let [field (field Compiler$StaticFieldExpr field expr)]
                  (@#'reflect/field->map field))
         :tag (field Compiler$StaticFieldExpr tag expr)}
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  Compiler$InstanceFieldExpr
  (analysis->map
    [expr env opt]
    (let [target (analysis->map (field Compiler$InstanceFieldExpr target expr) env opt)]
      (merge
        {:op :instance-field
         :env (env-location env expr)
         :target target
         :target-class (field Compiler$InstanceFieldExpr targetClass expr)
         :field (when-let [field (field Compiler$InstanceFieldExpr field expr)]
                  (@#'reflect/field->map field))
         :field-name (field Compiler$InstanceFieldExpr fieldName expr)
         :tag (field Compiler$InstanceFieldExpr tag expr)}
        (when (:children opt)
          {:children [[[:target] {}]]})
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  ; {:op   :new
  ;  :doc  "Node for a new special-form expression"
  ;  :keys [[:form "`(new Class arg*)`"]
  ;         ^:children
  ;         [:class "A :const AST node with :type :class representing the Class to instantiate"]
  ;         ^:children
  ;         [:args "A vector of AST nodes representing the arguments passed to the Class constructor"]
  ;         ^:optional
  ;         [:validated? "`true` if the constructor call could be resolved at compile time"]]}
  Compiler$NewExpr
  (analysis->map
    [expr env opt]
    (let [args (mapv #(analysis->map % env opt) (.args expr))
          c (.c expr)
          cls {:op :const
               :env env
               :type :class
               :literal? true
               :form (emit-form/class->sym c)
               :val c
               :tag Class
               :o-tag Class}
          ctor (when-let [ctor (.ctor expr)]
                 (@#'reflect/constructor->map ctor))]
      (merge
        {:op :new
         :form (list* 'new (emit-form/emit-form cls) (map emit-form/emit-form args))
         :env 
         ; borrow line numbers from arguments
         (if-let [iexpr (first (filter :line (map :env args)))]
           (inherit-env iexpr env)
           env)
         :class cls
         :args args
         :tag c
         :o-tag c
         :children [:class :args]}
        (when ctor
          {:validated? true}))))

  ;; set literal
  ; {:op   :set
  ;  :doc  "Node for a set literal with attached metadata and/or non literal elements"
  ;  :keys [[:form "`#{item*}`"]
  ;         ^:children
  ;         [:items "A vector of AST nodes representing the items of the set"]]}
  Compiler$SetExpr
  (analysis->map
    [expr env opt]
    (let [keys (mapv #(analysis->map % env opt) (.keys expr))]
      {:op :set
       :env env
       :form `#{~@(map emit-form/emit-form keys)}
       :items keys
       :children [:items]}))

  ;; vector literal
  ; {:op   :vector
  ;  :doc  "Node for a vector literal with attached metadata and/or non literal elements"
  ;  :keys [[:form "`[item*]`"]
  ;         ^:children
  ;         [:items "A vector of AST nodes representing the items of the vector"]]}
  Compiler$VectorExpr
  (analysis->map
    [expr env opt]
    (let [args (mapv #(analysis->map % env opt) (.args expr))]
      {:op :vector
       :env env
       :form `[~@(map emit-form/emit-form args)]
       :items args
       :children [:items]}))

  ;; map literal
  ; {:op   :map
  ;  :doc  "Node for a map literal with attached metadata and/or non literal elements"
  ;  :keys [[:form "`{[key val]*}`"]
  ;         ^:children
  ;         [:keys "A vector of AST nodes representing the keys of the map"]
  ;         ^:children
  ;         [:vals "A vector of AST nodes representing the vals of the map"]]}
  Compiler$MapExpr
  (analysis->map
    [expr env opt]
    (let [kvs (partition 2 (.keyvals expr))
          ks  (mapv #(analysis->map % env opt) (map first kvs))
          vs  (mapv #(analysis->map % env opt) (map second kvs))]
      {:op :map
       :env env
       ;; FIXME use transducers when dropping 1.6 support
       :form (into {}
                   (map
                     (fn [k v]
                       [(emit-form/emit-form k)
                        (emit-form/emit-form v)])
                     ks
                     vs))
       :keys ks
       :vals vs
       :children [:keys :vals]}))

  ;; Untyped
  Compiler$MonitorEnterExpr
  (analysis->map
    [expr env opt]
    (let [target (analysis->map (field Compiler$MonitorEnterExpr target expr) env opt)]
      {:op :monitor-enter
       :env env
       :form (list 'monitor-enter (emit-form/emit-form target))
       :target target
       :children [:target]}))

  Compiler$MonitorExitExpr
  ;{:op   :monitor-exit
  ; :doc  "Node for a monitor-exit special-form statement"
  ; :keys [[:form "`(monitor-exit target)`"]
  ;        ^:children
  ;        [:target "An AST node representing the monitor-exit sentinel"]]}
  (analysis->map
    [expr env opt]
    (let [target (analysis->map (field Compiler$MonitorExitExpr target expr) env opt)]
      (merge
        {:op :monitor-exit
         :env env
         :form (list 'monitor-exit (emit-form/emit-form target))
         :target target
         :children [:target]})))

  Compiler$ThrowExpr
  ; {:op   :throw
  ;  :doc  "Node for a throw special-form statement"
  ;  :keys [[:form "`(throw exception)`"]
  ;         ^:children
  ;         [:exception "An AST node representing the exception to throw"]]}
  (analysis->map
    [expr env opt]
    (let [exception (analysis->map (field Compiler$ThrowExpr excExpr expr) env opt)]
      {:op :throw
       :form (list 'throw (emit-form/emit-form exception))
       :env env
       :exception exception
       :children [:exception]}))

  ;; Invokes
  ; {:op   :invoke
  ;  :doc  "Node for an invoke expression"
  ;  :keys [[:form "`(f arg*)`"]
  ;         ^:children
  ;         [:fn "An AST node representing the function to invoke"]
  ;         ^:children
  ;         [:args "A vector of AST nodes representing the args to the function"]
  ;         ^:optional
  ;         [:meta "Map of metadata attached to the invoke :form"]]}
  ; {:op   :keyword-invoke
  ;  :doc  "Node for an invoke expression where the fn is a not-namespaced keyword and thus a keyword callsite can be emitted"
  ;  :keys [[:form "`(:k instance)`"]
  ;         ^:children
  ;         [:keyword "An AST node representing the keyword to lookup in the instance"]
  ;         ^:children
  ;         [:target "An AST node representing the instance to lookup the keyword in"]]}
  Compiler$InvokeExpr
  (analysis->map
    [expr env opt]
    (let [fexpr (analysis->map (field Compiler$InvokeExpr fexpr expr) env opt)
          args (mapv #(analysis->map % env opt) (field Compiler$InvokeExpr args expr))
          env (env-location env expr)
          tag (field Compiler$InvokeExpr tag expr)
          form (list* (emit-form/emit-form fexpr) (map emit-form/emit-form args))]
      (cond
        (and (== 1 (count args))
             (keyword? (:val fexpr)))
        {:op :keyword-invoke
         :form form
         :env env
         :keyword fexpr
         :tag tag
         :o-tag tag
         :target (first args)
         :children [:keyword :target]}

        :else
        {:op :invoke
         :form form
         :env env
         :fn fexpr
         :tag tag
         :o-tag tag
         :args args
         :children [:fn :args]})))

         ;:is-protocol (field Compiler$InvokeExpr isProtocol expr)
         ;:is-direct (field Compiler$InvokeExpr isDirect expr)
         ;:site-index (field Compiler$InvokeExpr siteIndex expr)
         ;:protocol-on (field Compiler$InvokeExpr protocolOn expr)
        ;(when-let [m (field Compiler$InvokeExpr onMethod expr)]
        ;  {:method (@#'reflect/method->map m)})

  Compiler$KeywordInvokeExpr
  (analysis->map
    [expr env opt]
    (assert "NYI")
    #_(let [target (analysis->map (field Compiler$KeywordInvokeExpr target expr) env opt)
          kw (analysis->map (field Compiler$KeywordInvokeExpr kw expr) env opt)]
      (merge
        {:op :keyword-invoke
         :env (env-location env expr)
         :kw kw
         :tag (field Compiler$KeywordInvokeExpr tag expr)
         :target target}
        (when (:children opt)
          {:children [[[:target] {}]]})
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  ;; TheVarExpr
  ; {:op   :the-var
  ;  :doc  "Node for a var special-form expression"
  ;  :keys [[:form "`(var var-name)`"]
  ;         [:var "The Var object this expression refers to"]]}
  Compiler$TheVarExpr
  (analysis->map
    [expr env opt]
    (let [^clojure.lang.Var var (.var expr)]
      {:op :the-var
       :tag clojure.lang.Var
       :o-tag clojure.lang.Var
       :form (list 'var (.sym var))
       :env env
       :var var}))

  ;; VarExpr
  ; {:op   :var
  ;  :doc  "Node for a var symbol"
  ;  :keys [[:form "A symbol naming the var"]
  ;         [:var "The Var object this symbol refers to"]
  ;         ^:optional
  ;         [:assignable? "`true` if the Var is :dynamic"]]}
  Compiler$VarExpr
  (analysis->map
    [expr env opt]
    (let [^clojure.lang.Var var (.var expr)
          meta (meta var)
          tag (.tag expr)]
      {:op :var
       :env env
       :var var
       :meta meta
       :tag tag
       :assignable? (boolean (:dynamic meta))
       :arglists (:arglists meta)
       :form (.sym var)}))

  ;; UnresolvedVarExpr
  Compiler$UnresolvedVarExpr
  (analysis->map
    [expr env opt]
    (assert nil "NYI")
    (let []
      (merge
        {:op :unresolved-var
         :env env
         :sym (.symbol expr)}
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  ;; ObjExprs
  Compiler$ObjExpr
  (analysis->map
    [expr env opt]
    (assert nil "NYI")
    (merge
      {:op :obj-expr
       :env env
       :tag (.tag expr)}
      (when (:java-obj opt)
        {:Expr-obj expr})))

  ;; FnExpr (extends ObjExpr)
  Compiler$NewInstanceMethod
  (analysis->map
    [obm env opt]
    (assert nil "NYI")
    (let [body (analysis->map (.body obm) env opt)]
      (merge
        {:op :new-instance-method
         :env (env-location env obm)
         :name (symbol (field Compiler$NewInstanceMethod name obm))
         :required-params (map analysis->map 
                               (concat [((field Compiler$ObjMethod indexlocals obm) 0)]
                                       (field Compiler$ObjMethod argLocals obm))
                               (repeat env)
                               (repeat opt))
         :body body}
        (when (:children opt)
          {:children [[[:body] {}]]})
        (when (:java-obj opt)
          {:ObjMethod-obj obm}))))

  ; {:op   :fn-method
  ;  :doc  "Node for an arity method in a fn* expression"
  ;  :keys [[:form "`([arg*] body*)`"]
  ;         [:loop-id "Unique symbol identifying this method as a target for recursion"]
  ;         [:variadic? "`true` if this fn-method takes a variable number of arguments"]
  ;         ^:children
  ;         [:params "A vector of :binding AST nodes with :local :arg representing this fn-method args"]
  ;         [:fixed-arity "The number of non-variadic args this fn-method takes"]
  ;         ^:children
  ;         [:body "Synthetic :do node (with :body? `true`) representing the body of this fn-method"]]}
  Compiler$FnMethod
  (analysis->map
    [obm env opt]
    (let [loop-id (gensym "loop_")
          rest-param (when-let [rest-param (.restParm obm)]
                       (assoc (analysis->map rest-param env opt)
                              :variadic? true
                              :local :arg
                              :op :binding))
          required-params (mapv #(assoc (analysis->map %1 env opt)
                                        :variadic? false
                                        :local :arg
                                        :arg-id %2
                                        :op :binding)
                                (.reqParms obm)
                                (range))
          params-expr (into required-params
                            (when rest-param
                              [rest-param]))
          ;_ (prn "params-expr" (map :op params-expr))
          body-env (into (update-in env [:locals]
                                    merge (zipmap (map :name params-expr) (map u/dissoc-env params-expr)))
                         {:context     :ctx/return
                          :loop-id     loop-id
                          :loop-locals (count params-expr)})
          body (analysis->map (.body obm) body-env opt)]
      (merge
        {:op :fn-method
         :loop-id loop-id
         :variadic? (boolean rest-param)
         :params params-expr
         :fixed-arity (count required-params)
         :body (assoc body :body? true)
         :env env
         :tag (:tag body)
         :o-tag (:o-tag body)
         ;; Map LocalExpr@xx -> LocalExpr@xx
         ;;:locals (map analysis->map (keys (.locals obm)) (repeat env) (repeat opt))
         :children [:params :body]})))

  ; {:op   :fn
  ;  :doc  "Node for a fn* special-form expression"
  ;  :keys [[:form "`(fn* name? [arg*] body*)` or `(fn* name? method*)`"]
  ;         [:variadic? "`true` if this function contains a variadic arity method"]
  ;         [:max-fixed-arity "The number of arguments taken by the fixed-arity method taking the most arguments"]
  ;         ^:optional ^:children
  ;         [:local "A :binding AST node with :local :fn representing the function's local name, if one is supplied"]
  ;         ^:children
  ;         [:methods "A vector of :fn-method AST nodes representing the fn method arities"]
  ;         [:once "`true` if the fn is marked as `^:once fn*`, meaning it will only be executed once and thus allowing for the clearing of closed-over locals"]
  Compiler$FnExpr
  (analysis->map
    [expr env opt]
    (let [variadic-method (when-let [variadic-method (.variadicMethod expr)]
                            (analysis->map variadic-method env opt))
          once (field-accessor Compiler$ObjExpr 'onceOnly expr)
          menv (assoc env :once once)
          methods-no-variadic (mapv #(analysis->map % menv opt) (.methods expr))
          methods (into methods-no-variadic
                        (when variadic-method
                          [variadic-method]))
          this-name (when-let [nme (.thisName expr)]
                      (symbol nme))
          fixed-arities (seq (map :fixed-arity methods-no-variadic))
          max-fixed-arity (when fixed-arities (apply max fixed-arities))]
      (merge
        {:op :fn
         :env (env-location env expr)
         ;FIXME
         ;:form (list 'fn* (map emit-form/emit-form methods))
         :methods methods
         :variadic? (boolean variadic-method)
         :tag   clojure.lang.AFunction #_(.tag expr)
         :o-tag clojure.lang.AFunction #_(.tag expr)
         :max-fixed-arity max-fixed-arity
         :once once
         :children [:methods]}
        (when this-name
          ;; FIXME what is a :binding?
          {:local {:op :binding
                   :name this-name}}))))

  ;; NewInstanceExpr
  ; {:op   :deftype
  ;  :doc  "Node for a deftype* special-form expression"
  ;  :keys [[:form "`(deftype* name class.name [arg*] :implements [interface*] method*)`"]
  ;         [:interfaces "A set of the interfaces implemented by the type"]
  ;         [:name "The symbol name of the deftype"]
  ;         [:class-name "A class for the deftype, should *never* be instantiated or used on instance? checks as this will not be the same class the deftype will evaluate to after compilation"]
  ;         ^:children
  ;         [:fields "A vector of :binding AST nodes with :local :field representing the deftype fields"]
  ;         ^:children
  ;         [:methods "A vector :method AST nodes representing the deftype methods"]]}
;FIXME find vector of interfaces this implements (I think it's in mmap + IType)
  Compiler$NewInstanceExpr
  (analysis->map
    [expr env opt]
    ;(prn "NewInstanceExpr")
    (let [methods (mapv #(analysis->map % env opt) (field Compiler$NewInstanceExpr methods expr))
          ;_ (prn "fields")
          fields (mapv (fn [kv]
                         ;(prn kv)
                         (assoc (analysis->map (val kv) env opt)
                                :op :binding
                                :local :field
                                :mutable nil))
                       (field Compiler$ObjExpr fields expr))
          ;_ (prn "before name")
          name (symbol (.name expr))
          ;_ (prn "after name")
          class-name (.compiledClass expr) ;or  #_(.internalName expr) ?
          interfaces (remove
                       #{Object}
                       (concat
                         [clojure.lang.IType]
                         (mapcat second (keys (field Compiler$NewInstanceExpr mmap expr)))))]
      ;(prn "mmap" (field Compiler$NewInstanceExpr mmap expr))
      {:op :deftype
       :form (list* 'deftype* name class-name
                    [] ;; TODO
                    :implements (map emit-form/class->sym interfaces)
                    (map emit-form/emit-form methods))
       :name name
       :env (env-location env expr)
       :methods methods
       :fields fields
       :class-name class-name

       :interfaces (set interfaces)

       ;:mmap (field Compiler$NewInstanceExpr mmap expr)
       ;:compiled-class (.compiledClass expr)
       ;:internal-name (.internalName expr)
       ;:this-name (.thisName expr)

       ;(Vec Symbol)
       ;:hinted-fields (field Compiler$ObjExpr hintedFields expr)
       ;:covariants (field Compiler$NewInstanceExpr covariants expr)
       :tag (.tag expr)
       :children [:fields :methods]}))

  ;; InstanceOfExpr
  ; {:op   :instance?
  ;  :doc  "Node for a clojure.core/instance? call where the Class is known at compile time"
  ;  :keys [[:form "`(clojure.core/instance? Class x)`"]
  ;         [:class "The Class to test the :target for instanceability"]
  ;         ^:children
  ;         [:target "An AST node representing the object to test for instanceability"]]}
  Compiler$InstanceOfExpr
  (analysis->map
    [expr env opt]
    (let [exp (analysis->map (field Compiler$InstanceOfExpr expr expr) env opt)
          ^Class cls (field Compiler$InstanceOfExpr c expr)]
      {:op :instance?
       :env env
       :class cls
       :target exp
       :tag Boolean/TYPE
       :o-tag Boolean/TYPE
       :form (list 'instance? (symbol (.getName cls)) (emit-form/emit-form exp))
       :children [:target]}))

  ;; MetaExpr
  ; {:op   :with-meta
  ;  :doc  "Node for a non quoted collection literal or fn/reify expression with attached metadata"
  ;  :keys [[:form "Non quoted collection literal or fn/reify expression with attached metadata"]
  ;         ^:children
  ;         [:meta "An AST node representing the metadata of expression. The node will be either a :map node or a :const node with :type :map"]
  ;         ^:children
  ;         [:expr "The expression this metadata is attached to, :op is one of :vector, :map, :set, :fn or :reify"]]}]}
  Compiler$MetaExpr
  (analysis->map
    [expr env opt]
    (let [meta (analysis->map (.meta expr) env opt)
          the-expr (analysis->map (.expr expr) env opt)]
      {:op :with-meta
       :env env
       :form (emit-form/emit-form the-expr) ;FIXME add meta
       :meta meta
       :expr the-expr
       :children [:meta :children]}))

  ;; do
  ; {:op   :do
  ;  :doc  "Node for a do special-form expression or for another special-form's body"
  ;  :keys [[:form "`(do statement* ret)`"]
  ;         ^:children
  ;         [:statements "A vector of AST nodes representing all but the last expression in the do body"]
  ;         ^:children
  ;         [:ret "An AST node representing the last expression in the do body (the block's return value)"]
  ;         ^:optional
  ;         [:body? "`true` if this node is a synthetic body"]]}
  Compiler$BodyExpr
  (analysis->map
    [expr env opt]
    (let [[statements ret] (loop [statements [] [e & exprs] (.exprs expr)]
                             (if exprs
                               (recur (conj statements (analysis->map e env opt)) exprs)
                               [statements (analysis->map e env opt)]))]
      {:op :do
       :env (inherit-env ret env)
       :form (list* 'do (map emit-form/emit-form (concat statements [ret])))
       :statements statements
       :ret ret
       :tag (:tag ret)
       :o-tag (:o-tag ret)
       :children [:statements :ret]}))

  ;; if
  ; {:op   :if
  ;  :doc  "Node for an if special-form expression"
  ;  :keys [[:form "`(if test then else?)`"]
  ;         ^:children
  ;         [:test "An AST node representing the test expression"]
  ;         ^:children
  ;         [:then "An AST node representing the expression's return value if :test evaluated to a truthy value"]
  ;         ^:children
  ;         [:else "An AST node representing the expression's return value if :test evaluated to a falsey value, if not supplied it will default to a :const node representing nil"]]}
  Compiler$IfExpr
  (analysis->map
    [expr env opt]
    (let [test (analysis->map (.testExpr expr) env opt)
          then (analysis->map (.thenExpr expr) env opt)
          else (analysis->map (.elseExpr expr) env opt)]
      {:op :if
       :env (env-location env expr)
       :form (list* 'if (map emit-form/emit-form [test then else]))
       :test test
       :then then
       :else else
       :children [:test :then :else]}))

  ;; case
  ;; (from Compiler.java)
  ;;  //(case* expr shift mask default map<minhash, [test then]> table-type test-type skip-check?)
  ; {:op   :case
  ;  :doc  "Node for a case* special-form expression"
  ;  :keys [[:form "`(case* expr shift maks default case-map switch-type test-type skip-check?)`"]
  ;         ^:children
  ;         [:test "The AST node for the expression to test against"]
  ;         ^:children
  ;         [:tests "A vector of :case-test AST nodes, each node has a corresponding :case-then node in the :thens field"]
  ;         ^:children
  ;         [:thens "A vector of :case-then AST nodes, each node has a corresponding :case-test node in the :tests field"]
  ;         ^:children
  ;         [:default "An AST node representing the default value of the case expression"]
  ;         [:shift]
  ;         [:mask]
  ;         [:low]
  ;         [:high]
  ;         [:switch-type "One of :sparse or :compact"]
  ;         [:test-type "One of :int, :hash-equiv or :hash-identity"]
  ;         [:skip-check? "A set of case ints for which equivalence checking should not be done"]]}
  Compiler$CaseExpr
  (analysis->map
    [expr env opt]
    (let [the-expr (analysis->map (.expr expr) env opt)
          tests-map (.tests expr)
          thens-map (.thens expr)
          [low high] ((juxt first last) (keys tests-map)) ;;tests-map is a sorted-map
          [tests thens] (reduce (fn [[tests thens] [h tst]]
                                  (let [test-expr (analysis->map tst env opt)
                                        then-expr (analysis->map (get thens-map h) env opt)]
                                    [(conj tests {:op       :case-test
                                                  :form     (emit-form/emit-form test-expr)
                                                  :env      env
                                                  :hash     h
                                                  :test     test-expr
                                                  :children [:test]})
                                     (conj thens {:op       :case-then
                                                  :form     (emit-form/emit-form then-expr)
                                                  :env      env
                                                  :hash     h
                                                  :then     then-expr
                                                  :children [:then]})]))
                                [[] []] tests-map)
          default (analysis->map (.defaultExpr expr) env opt)]
      {:op :case
       :env (env-location env expr)
       :test (assoc the-expr :case-test true)
       :tests tests
       :thens thens
       :default default
       :shift (.shift expr)
       :mask (.mask expr)
       :low low
       :high high
       :switch-type (.switchType expr)
       :test-type (.testType expr)
       :skip-check? (.skipCheck expr)
       :children [:test :tests :thens :default]}))


  ;; ImportExpr
  ; {:op   :import
  ;  :doc  "Node for a clojure.core/import* special-form expression"
  ;  :keys [[:form "`(clojure.core/import* \"qualified.class\")`"]
  ;         [:class "String representing the qualified class to import"]]}
  Compiler$ImportExpr
  (analysis->map
    [expr env opt]
    (let [c (.c expr)]
      (assert (string? c))
      {:op :import
       :env env
       :form (list 'clojure.core/import* c)
       :class c
       ; :validated? true ?
       }))

  ;; AssignExpr (set!)
  Compiler$AssignExpr
  (analysis->map
    [expr env opt]
    (let [target (analysis->map (.target expr) env opt)
          val (analysis->map (.val expr) env opt)]
      (merge
        {:op :set!
         :env env
         :target target
         :val val}
        (when (:children opt)
          {:children [[[:target] {}] 
                      [[:val] {}]]})
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  ;;TryExpr
  Compiler$TryExpr$CatchClause
  (analysis->map
    [ctch env opt]
    (let [local-binding (analysis->map (.lb ctch) env opt)
          handler (analysis->map (.handler ctch) env opt)]
      (merge
        {:op :catch
         :env env
         :class (.c ctch)
         :local-binding local-binding
         :handler handler}
        (when (:children opt)
          {:children [[[:local-binding] {}]
                      [[:handler] {}]]})
        (when (:java-obj opt)
          {:CatchClause-obj ctch}))))

  Compiler$TryExpr
  (analysis->map
    [expr env opt]
    (let [try-expr (analysis->map (.tryExpr expr) env opt)
          finally-expr (when-let [finally-expr (.finallyExpr expr)]
                         (analysis->map finally-expr env opt))
          catch-exprs (map analysis->map (.catchExprs expr) (repeat env) (repeat opt))]
      (merge
        {:op :try
         :env env
         :try-expr try-expr
         :finally-expr finally-expr
         :catch-exprs catch-exprs
         :ret-local (.retLocal expr)
         :finally-local (.finallyLocal expr)}
        (when (:children opt)
          {:children [[[:try-expr] {}]
                      [[:finally-expr] {}]
                      [[:catch-exprs] {:exprs? true}]]})
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  ;; RecurExpr
  Compiler$RecurExpr
  (analysis->map
    [expr env opt]
    (let [loop-locals (map analysis->map (.loopLocals expr) (repeat env) (repeat opt))
          args (map analysis->map (.args expr) (repeat env) (repeat opt))]
      (merge
        {:op :recur
         :env (env-location env expr)
         :loop-locals loop-locals
         :args args}
        (when (:children opt)
          {:children [[[:loop-locals] {:exprs? true}]
                      [[:args] {:exprs? true}]]})
        (when (:java-obj opt)
          {:Expr-obj expr}))))

  Compiler$MethodParamExpr
  (analysis->map
    [expr env opt]
    (let []
      (merge
        {:op :method-param
         :env env
         :class (.getJavaClass expr)
         :can-emit-primitive (.canEmitPrimitive expr)}
        (when (:java-obj opt)
          {:Expr-obj expr})))))

(defmulti keyword->Context identity)
(defmethod keyword->Context :ctx/statement [_] Compiler$C/STATEMENT)
(defmethod keyword->Context :ctx/expr      [_] #_Compiler$C/EXPRESSION
  ;; EXPRESSION doesn't work too well, eg. (analyze-form '(let []))
  Compiler$C/EVAL)
(defmethod keyword->Context :ctx/return    [_] Compiler$C/RETURN)
;; :eval Compiler$C/EVAL

(defn- analyze*
  "Must be called after binding the appropriate Compiler and RT dynamic Vars."
  ([env form] (analyze* env form {}))
  ([env form opt]
   (let [context (keyword->Context (:context env))
         expr-ast (try
                    (Compiler/analyze context form)
                    (catch RuntimeException e
                      (throw (repl/root-cause e))))
         ;_ (method-accessor (class expr-ast) 'eval expr-ast [])
         ]
     (-> (analysis->map expr-ast 
                        (merge env
                               (when-let [file (and (not= *file* "NO_SOURCE_FILE")
                                                    *file*)]
                                 {:file file}))
                        opt)
         (assoc :top-level true)))))

(defn analyze-one
  "Analyze a single form"
  ([env form] (analyze-one env form {}))
  ([env form opt] (analyze* env form opt)))

(defn forms-seq
  "Lazy seq of forms in a Clojure or ClojureScript file."
  [^java.io.PushbackReader rdr]
  (let [eof (reify)]
    (lazy-seq
      (let [form (read rdr nil eof)]
        (when-not (identical? form eof)
          (lazy-seq (cons form (forms-seq rdr))))))))

(defn ^:private munge-ns [ns-sym]
  (-> (name ns-sym)
      (string/replace "." "/")
      (string/replace "-" "_")
      (str ".clj")))
       
(defn uri-for-ns 
  "Returns a URI representing the namespace. Throws an
  exception if URI not found."
  [ns-sym]
  (let [source-path (munge-ns ns-sym) 
        uri (io/resource source-path)]
    (when-not uri
      (throw (Exception. (str "No file found for namespace " ns-sym))))
    uri))

(defn ^LineNumberingPushbackReader
  pb-reader-for-ns
  "Returns a LineNumberingPushbackReader for namespace ns-sym"
  [ns-sym]
  (let [uri (uri-for-ns ns-sym)]
    (LineNumberingPushbackReader. (io/reader uri))))

(defonce ^:private Compiler-members (set (map :name (:members (reflect/type-reflect RT)))))
(defonce ^:private RT-members (set (map :name (:members (reflect/type-reflect RT)))))

(defmacro ^:private analyzer-bindings [source-path pushback-reader]
  `(merge
     {Compiler/LOADER (RT/makeClassLoader)
      Compiler/SOURCE_PATH (str ~source-path)
      Compiler/SOURCE (str ~source-path)
      Compiler/METHOD nil
      Compiler/LOCAL_ENV nil
      Compiler/LOOP_LOCALS nil
      Compiler/NEXT_LOCAL_NUM 0
      RT/CURRENT_NS @RT/CURRENT_NS
      Compiler/LINE_BEFORE (.getLineNumber ~pushback-reader)
      Compiler/LINE_AFTER (.getLineNumber ~pushback-reader)
      RT/UNCHECKED_MATH @RT/UNCHECKED_MATH}
     ~(when (RT-members 'WARN_ON_REFLECTION)
        `{(field RT ~'WARN_ON_REFLECTION) @(field RT ~'WARN_ON_REFLECTION)})
     ~(when (Compiler-members 'COLUMN_BEFORE)
        `{Compiler/COLUMN_BEFORE (.getColumnNumber ~pushback-reader)})
     ~(when (Compiler-members 'COLUMN_AFTER)
        `{Compiler/COLUMN_AFTER (.getColumnNumber ~pushback-reader)})
     ~(when (RT-members 'DATA_READERS)
        `{RT/DATA_READERS @RT/DATA_READERS})))

(defn analyze-file
  "Takes a file path and optionally a pushback reader.
  Returns a vector of maps representing the ASTs of the forms
  in the target file.

  Options:
  - :reader  a pushback reader to use to read the namespace forms
  - :opt     a map of analyzer options
    - :children
      when true, include a :children key with all child expressions of each node
    - :java-obj
      when true, include a :java-obj key with the node's corresponding Java object

  eg. (analyze-file \"my/ns.clj\")"
  [source-path & {:keys [reader opt] 
                  :or {reader (LineNumberingPushbackReader. (io/reader (io/resource source-path)))}}]
  (let [eof (reify)
        ^LineNumberingPushbackReader 
        pushback-reader (if (instance? LineNumberingPushbackReader reader)
                          reader
                          (LineNumberingPushbackReader. reader))]
    (with-bindings (analyzer-bindings source-path pushback-reader)
      (loop [form (read pushback-reader nil eof)
             out []]
        (if (identical? form eof)
          out
          (let [env {:ns {:name (ns-name *ns*)}
                     :source-path source-path
                     :locals {}}
                expr-ast (Compiler/analyze (keyword->Context :eval) form)
                m (analysis->map expr-ast env opt)
                _ (when *eval-ast*
                    (method-accessor Compiler$Expr 'eval expr-ast []))]
            (recur (read pushback-reader nil eof) (conj out m))))))))

(defn analyze-ns
  "Takes a LineNumberingPushbackReader and a namespace symbol.
  Returns a vector of maps, with keys :op, :env. If expressions
  have children, will have :children entry.

  Options:
  - :reader  a pushback reader to use to read the namespace forms
  - :opt     a map of analyzer options
    - :children
      when true, include a :children key with all child expressions of each node
    - :java-obj
      when true, include a :java-obj key with the node's corresponding Java object

  eg. (analyze-ns 'my-ns :opt {:children true} :reader (pb-reader-for-ns 'my.ns))"
  [source-nsym & {:keys [reader opt] :or {reader (pb-reader-for-ns source-nsym)}}]
  (let [source-path (munge-ns source-nsym)]
    (analyze-file source-path :reader reader :opt opt)))


(comment
  (ast 
    (try (throw (Exception.)) 
      (catch Exception e (throw e)) 
      (finally 33)))

  (ast
    (let [b 1] 
      (fn [& a] 1)))

  (ast (Integer. (+ 1 1)))

  (ast (map io/file [1 2]))

  (ast (do 
         (require '[clojure.repl :refer [pst]])
         (pst)))
  (ast (deftype A [a b]
         Object
         (toString [this])))
  
  ;children
  ; - what about optional keys? eg. :local-binding's :init? do we need an :optional case, or
  ;   maybe a `:when child-expr` will be sufficient?
  (->
    (let [expr (ast (let [a 1] a) {:children true})]
      (for [[path {:keys [exprs?]}] (:children expr)
            :let [in (get-in expr path)]
            child-expr (if exprs?
                         in
                         [in])]
        child-expr))
    clojure.pprint/pprint)

  (def in (Compiler/analyze Compiler$C/STATEMENT '(seq 1)))
  (class in)
  (def method (doto (.getMethod (class in) "eval" (into-array Class []))
                (.setAccessible true)))
  (try (.invoke method in (object-array []))
    (catch java.lang.reflect.InvocationTargetException e
      (throw (repl/root-cause e))))
    )
