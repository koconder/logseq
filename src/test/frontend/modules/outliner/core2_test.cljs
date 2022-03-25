(ns frontend.modules.outliner.core2-test
  (:require [cljs.test :refer [deftest is testing] :as test]
            [clojure.test.check.generators :as g]
            [datascript.core :as d]
            [frontend.modules.outliner.core2 :as outliner-core]
            [frontend.modules.outliner.transaction :as tx]))

(def tree
  "
- 1
 - 2
  - 3
   - 4
   - 5
  - 6
   - 7
    - 8
  - 9
   - 10
   - 11
   - 12
 - 13
  - 14
"
  [{:data 1 :level 1}
   {:data 2 :level 2}
   {:data 3 :level 3}
   {:data 4 :level 4}
   {:data 5 :level 4}
   {:data 6 :level 3}
   {:data 7 :level 4}
   {:data 8 :level 5}
   {:data 9 :level 3}
   {:data 10 :level 4}
   {:data 11 :level 4}
   {:data 12 :level 4}
   {:data 13 :level 2}
   {:data 14 :level 3}])

(def big-tree (map-indexed (fn [i m] (assoc m :data i)) (flatten (repeat 800 tree))))

(defn- build-db-records
  [tree]
  (let [conn (d/create-conn {:block/next {:db/valueType :db.type/ref
                                          :db/unique :db.unique/value}
                             :block/next-sibling {:db/valueType :db.type/ref
                                                  :db/unique :db.unique/value}
                             :block/parent {:db/valueType :db.type/ref
                                            :db/index true}
                             :block/page {:db/valueType :db.type/ref}
                             :block/page+next {:db/tupleAttrs [:block/page :block/next]}
                             :data {:db/unique :db.unique/identity}})]
    (d/transact! conn [[:db/add 1 :page-block true]])
    (d/transact! conn (outliner-core/insert-nodes @conn tree 1 false))
    conn))

(defn- get-page-nodes1
  [db]
  (sequence
   (comp
    (map #(select-keys % [:data :block/parent :db/id :block/next-sibling :block/page]))
    (map #(update % :block/parent :db/id)))
   (outliner-core/get-page-nodes db (d/entity db 1))))

(defn- get-page-nodes2
  [db]
  (outliner-core/get-page-nodes db (d/entity db 1)))

(defn- print-page-nodes
  [db]
  (loop [parents {}
         [node & tail] (get-page-nodes2 db)]
    (when node
      (->> node
           ((juxt #(apply str (repeat (inc (get parents (:db/id (:block/parent %)) -1)) "__"))
                  (constantly "- ")
                  :data
                  (constantly " #")
                  :db/id
                  #(some-> % :block/next-sibling :db/id ((fn [id] (str " ↓" id))))))
           (apply str)
           println)
      (recur (assoc parents (:db/id node) (inc (get parents (:db/id (:block/parent node)) -1))) tail))))

;;; testcases for operations (pure functions)

(deftest test-insert-nodes
  (testing "insert 15, 16 as children after 5; 15 & 16 are siblings"
    (let [conn (build-db-records tree)
          txs-data
          (outliner-core/insert-nodes @conn
                                      [{:data 15 :level 1} {:data 16 :level 1}]
                                      (d/entid @conn [:data 5]) false)]
      (d/transact! conn txs-data)
      (let [nodes-data (get-page-nodes1 @conn)]
        (is (= [{:data 1, :block/parent 1, :db/id 2}
                {:data 2, :block/parent 2, :db/id 3}
                {:data 3, :block/parent 3, :db/id 4}
                {:data 4, :block/parent 4, :db/id 5}
                {:data 5, :block/parent 4, :db/id 6}
                {:data 15, :block/parent 6, :db/id 16}
                {:data 16, :block/parent 6, :db/id 17}
                {:data 6, :block/parent 3, :db/id 7}
                {:data 7, :block/parent 7, :db/id 8}
                {:data 8, :block/parent 8, :db/id 9}
                {:data 9, :block/parent 3, :db/id 10}
                {:data 10, :block/parent 10, :db/id 11}
                {:data 11, :block/parent 10, :db/id 12}
                {:data 12, :block/parent 10, :db/id 13}
                {:data 13, :block/parent 2, :db/id 14}
                {:data 14, :block/parent 14, :db/id 15}] nodes-data)))))
  (testing "insert 15, 16 as children after 5; 16 is child of 15"
    (let [conn (build-db-records tree)
          txs-data
          (outliner-core/insert-nodes @conn
                                      [{:data 15 :level 1} {:data 16 :level 2}]
                                      (d/entid @conn [:data 5]) false)]
      (d/transact! conn txs-data)
      (let [nodes-data (get-page-nodes1 @conn)]
        (is (= [{:data 1, :block/parent 1, :db/id 2}
                {:data 2, :block/parent 2, :db/id 3}
                {:data 3, :block/parent 3, :db/id 4}
                {:data 4, :block/parent 4, :db/id 5}
                {:data 5, :block/parent 4, :db/id 6}
                {:data 15, :block/parent 6, :db/id 16}
                {:data 16, :block/parent 16, :db/id 17}
                {:data 6, :block/parent 3, :db/id 7}
                {:data 7, :block/parent 7, :db/id 8}
                {:data 8, :block/parent 8, :db/id 9}
                {:data 9, :block/parent 3, :db/id 10}
                {:data 10, :block/parent 10, :db/id 11}
                {:data 11, :block/parent 10, :db/id 12}
                {:data 12, :block/parent 10, :db/id 13}
                {:data 13, :block/parent 2, :db/id 14}
                {:data 14, :block/parent 14, :db/id 15}] nodes-data))))))

(deftest test-get-children-nodes
  (testing "get 3 and its children nodes"
    (let [conn (build-db-records tree)
          nodes (outliner-core/get-children-nodes @conn (d/entity @conn [:data 3]))]
      (is (= '(3 4 5) (map :data nodes))))))


(deftest test-move-nodes
  (testing "move 3 and its children to 11 (as children)"
    (let [conn (build-db-records tree)
          nodes (outliner-core/get-children-nodes @conn (d/entity @conn [:data 3] @conn))
          node-11 (d/entity @conn [:data 11])
          txs-data (outliner-core/move-nodes @conn nodes node-11 false)]
      (d/transact! conn txs-data)
      (let [page-nodes (get-page-nodes1 @conn)]
        (is (= page-nodes
               [{:data 1, :block/parent 1, :db/id 2}
                {:data 2, :block/parent 2, :db/id 3}
                {:data 6, :block/parent 3, :db/id 7}
                {:data 7, :block/parent 7, :db/id 8}
                {:data 8, :block/parent 8, :db/id 9}
                {:data 9, :block/parent 3, :db/id 10}
                {:data 10, :block/parent 10, :db/id 11}
                {:data 11, :block/parent 10, :db/id 12}
                {:data 3, :block/parent 12, :db/id 4}
                {:data 4, :block/parent 4, :db/id 5}
                {:data 5, :block/parent 4, :db/id 6}
                {:data 12, :block/parent 10, :db/id 13}
                {:data 13, :block/parent 2, :db/id 14}
                {:data 14, :block/parent 14, :db/id 15}]))))))

(deftest test-delete-nodes
  (testing "delete 6-12 nodes"
    (let [conn (build-db-records tree)
          nodes-6 (outliner-core/get-children-nodes @conn (d/entity @conn [:data 6] @conn))
          nodes-9 (outliner-core/get-children-nodes @conn (d/entity @conn [:data 9] @conn))
          txs-data (outliner-core/delete-nodes @conn (concat nodes-6 nodes-9))]
      (d/transact! conn txs-data)
      (let [page-nodes (get-page-nodes1 @conn)]
        (is (= page-nodes
               [{:data 1, :block/parent 1, :db/id 2}
                {:data 2, :block/parent 2, :db/id 3}
                {:data 3, :block/parent 3, :db/id 4}
                {:data 4, :block/parent 4, :db/id 5}
                {:data 5, :block/parent 4, :db/id 6}
                {:data 13, :block/parent 2, :db/id 14}
                {:data 14, :block/parent 14, :db/id 15}]))))))

(deftest test-indent-nodes
  (testing "indent 6-12 nodes"
    (let [conn (build-db-records tree)
          nodes-6 (outliner-core/get-children-nodes @conn (d/entity @conn [:data 6] @conn))
          nodes-9 (outliner-core/get-children-nodes @conn (d/entity @conn [:data 9] @conn))
          txs-data (outliner-core/indent-nodes @conn (concat nodes-6 nodes-9))]
      (d/transact! conn txs-data)
      (let [page-nodes (get-page-nodes1 @conn)]
        (is (= page-nodes
               [{:data 1, :block/parent 1, :db/id 2}
                {:data 2, :block/parent 2, :db/id 3}
                {:data 3, :block/parent 3, :db/id 4}
                {:data 4, :block/parent 4, :db/id 5}
                {:data 5, :block/parent 4, :db/id 6}
                {:data 6, :block/parent 4, :db/id 7}
                {:data 7, :block/parent 7, :db/id 8}
                {:data 8, :block/parent 8, :db/id 9}
                {:data 9, :block/parent 4, :db/id 10}
                {:data 10, :block/parent 10, :db/id 11}
                {:data 11, :block/parent 10, :db/id 12}
                {:data 12, :block/parent 10, :db/id 13}
                {:data 13, :block/parent 2, :db/id 14}
                {:data 14, :block/parent 14, :db/id 15}]))))))

(deftest test-outdent-nodes
  (testing "outdent 6-12 nodes"
    (let [conn (build-db-records tree)
          nodes-6 (outliner-core/get-children-nodes @conn (d/entity @conn [:data 6] @conn))
          nodes-9 (outliner-core/get-children-nodes @conn (d/entity @conn [:data 9] @conn))
          txs-data (outliner-core/outdent-nodes @conn (concat nodes-6 nodes-9))]
      (d/transact! conn txs-data)
      (let [page-nodes (get-page-nodes1 @conn)]
        (is (= page-nodes
               [{:data 1, :block/parent 1, :db/id 2}
                {:data 2, :block/parent 2, :db/id 3}
                {:data 3, :block/parent 3, :db/id 4}
                {:data 4, :block/parent 4, :db/id 5}
                {:data 5, :block/parent 4, :db/id 6}
                {:data 6, :block/parent 2, :db/id 7}
                {:data 7, :block/parent 7, :db/id 8}
                {:data 8, :block/parent 8, :db/id 9}
                {:data 9, :block/parent 2, :db/id 10}
                {:data 10, :block/parent 10, :db/id 11}
                {:data 11, :block/parent 10, :db/id 12}
                {:data 12, :block/parent 10, :db/id 13}
                {:data 13, :block/parent 2, :db/id 14}
                {:data 14, :block/parent 14, :db/id 15}]))))))

(defn- validate-nodes-parent2
  "Validate that every node's parent is correct."
  [nodes db]
  (when (seq nodes)
    (let [parents+self (volatile! [])]
      (loop [[node & tail] nodes]
        (let [parents (mapv outliner-core/get-id (vec (reverse (outliner-core/get-parent-nodes db node))))]
          (when (seq @parents+self)
            (assert (= parents (subvec @parents+self 0 (count parents)))
                    (do (print-page-nodes db) [parents @parents+self node])))
          (vreset! parents+self (conj parents (outliner-core/get-id node)))
          (when tail
            (recur tail)))))))

(defn- validate-nodes-next-sibling
  "Validate every node's next-sibling is correct.
  NODE is the first-node.
  (validate-nodes-next-sibling db) = (validate-nodes-next-sibling db (get-next {:db/id 1}))"
  ([db] (validate-nodes-next-sibling db (outliner-core/get-next db (d/entity db 1))))
  ([db node]
   (when node
     ;; verify siblings have the same parent, print first node of siblings when failed
     (assert (apply = (mapv #(outliner-core/get-parent db %) (outliner-core/get-next-sibling-nodes db node))) node)
     (let [siblings+node (cons node (outliner-core/get-next-sibling-nodes db node))]
       ;; recursively verify each node's children
       (doseq [node siblings+node]
         (when-let [first-child (outliner-core/get-first-child db node)]
           (validate-nodes-next-sibling db first-child)))))))


(defn- gen-random-tree
  "PREFIX: used for generating unique data, :data is :db/unique in this test-file for test"
  [n prefix]
  (let [coll (transient [])]
    (loop [i 0 last-level 0]
      (when (< i n)
        (let [level (inc (rand-int (inc last-level)))]
          (conj! coll {:data (str prefix "-" i) :level level})
          (recur (inc i) level))))
    (persistent! coll)))

(defn- random-range-nodes
  [db]
  (let [start (rand-int (count (d/datoms db :avet :data)))
        num (inc (rand-int 5))
        first-node (outliner-core/get-next db (d/entity db 1))
        nodes (transient [])]
    (when first-node
      (loop [started start num num node first-node]
        (when node
          (cond
            (pos? started)
            (recur (dec started) num (outliner-core/get-next db node))
            (pos? num)
            (do (conj! nodes node)
                (recur started (dec num) (outliner-core/get-next db node))))))
      (let [nodes* (persistent! nodes)]
        (when (seq nodes*)
          (outliner-core/with-children-nodes db nodes*))))))

(defn- op-insert-nodes
  [db seq-state]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [nodes (gen-random-tree (inc (rand-int 10)) (vswap! seq-state inc))
            target-id (:e (g/generate (g/elements datoms)))]
        {:txs-data (outliner-core/insert-nodes db nodes target-id (g/generate g/boolean))
         :nodes-count-change (count nodes)}))))

(defn- op-delete-nodes
  [db _]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [node (d/entity db (:e (g/generate (g/elements datoms))))
            nodes (outliner-core/get-children-nodes db node)]
        {:txs-data (outliner-core/delete-nodes db nodes)
         :nodes-count-change (- (count nodes))}))))

(defn- op-indent-nodes
  [db _]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [nodes (apply subvec (vec (outliner-core/get-page-nodes db (d/entity db 1)))
                         (sort [(rand-int (count datoms)) (rand-int (count datoms))]))]
        (if (seq nodes)
          {:txs-data (outliner-core/indent-nodes db nodes)
             :nodes-count-change 0}
          {:txs-data [] :nodes-count-change 0})))))

(defn- op-outdent-nodes
  [db _]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [nodes (apply subvec (vec (outliner-core/get-page-nodes db (d/entity db 1)))
                         (sort [(rand-int (count datoms)) (rand-int (count datoms))]))]
        (if (seq nodes)
          {:txs-data (outliner-core/outdent-nodes db nodes)
           :nodes-count-change 0}
          {:txs-data [] :nodes-count-change 0})))))

(defn- op-move-nodes
  [db _seq-state]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [nodes (random-range-nodes db)
            target (loop [n 10 maybe-node (d/entity db (:e (g/generate (g/elements datoms))))]
                     (cond
                       (= 0 n)
                       nil
                       (outliner-core/contains-node? nodes maybe-node)
                       (recur (dec n) (d/entity db (:e (g/generate (g/elements datoms)))))
                       :else
                       maybe-node))]

        (if-not target
          {:txs-data [] :nodes-count-change 0}
          {:txs-data (outliner-core/move-nodes db nodes target (g/generate g/boolean))
           :nodes-count-change 0})))))

(defn- op-move-nodes-up
  [db _]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [node (d/entity db (:e (g/generate (g/elements datoms))))
            nodes (outliner-core/get-children-nodes db node)]
        {:txs-data (outliner-core/move-nodes-up db nodes)
         :nodes-count-change 0}))))

(defn- op-move-nodes-down
  [db _]
  (let [datoms (d/datoms db :avet :data)]
    (if (empty? datoms)
      {:txs-data [] :nodes-count-change 0}
      (let [node (d/entity db (:e (g/generate (g/elements datoms))))
            nodes (outliner-core/get-children-nodes db node)]
        {:txs-data (outliner-core/move-nodes-down db nodes)
         :nodes-count-change 0}))))

;;; generative testcases
;; build random legal tree, then apply random operations on it.
(deftest test-random-op
  (testing "random insert/delete/indent/outdent nodes"
    (dotimes [_ 20]
      (let [seq-state (volatile! 0)
            tree (gen-random-tree 20 (vswap! seq-state inc))
            conn (build-db-records tree)
            nodes-count (volatile! (count tree))]
        (println "(test-random-op) random insert/delete/indent/outdent nodes (100 runs)")

        (dotimes [_ 100]
          (let [{:keys [txs-data nodes-count-change]}
                ((g/generate (g/elements [op-insert-nodes
                                          op-delete-nodes
                                          ;; op-indent-nodes
                                          ;; op-outdent-nodes
                                          op-move-nodes
                                          op-move-nodes-up
                                          op-move-nodes-down
                                          ]))@conn seq-state)]
            (d/transact! conn txs-data)
            (vswap! nodes-count #(+ % nodes-count-change))
            (let [page-nodes (get-page-nodes2 @conn)]
              (validate-nodes-parent2 page-nodes @conn)
              (validate-nodes-next-sibling @conn)
              (is (= @nodes-count (count page-nodes))) ; check node count
              )))))))

;;; generative testcases on write-operations with side-effects

(defn- fetch-tx-data [*txs]
  (fn [tx-data]
    (vswap! *txs into tx-data)))

(defn- undo-tx-data! [conn tx-data]
  (let [rev-tx-data (->> tx-data
                         reverse
                         (map (fn [[e a v t add?]]
                                (let [op (if add? :db/retract :db/add)]
                                  [op e a v t]))))]
    (d/transact! conn rev-tx-data)))

(defn- op-insert-nodes!
  [conn seq-state]
  (let [datoms (d/datoms @conn :avet :data)]
    (when (seq datoms)
      (let [nodes (gen-random-tree (inc (rand-int 10)) (vswap! seq-state inc))
            target-id (:e (g/generate (g/elements datoms)))]
        (outliner-core/insert-nodes! conn nodes target-id (g/generate g/boolean))))))

(defn- op-delete-nodes!
  [conn _seq-state]
  (let [datoms (d/datoms @conn :avet :data)]
    (when (seq datoms)
      (let [node (d/entity @conn (:e (g/generate (g/elements datoms))))
            nodes (outliner-core/get-children-nodes @conn node)]
        (outliner-core/delete-nodes! conn nodes)))))

(defn- op-indent-nodes!
  [conn _seq-state]
  (let [datoms (d/datoms @conn :avet :data)]
    (when (seq datoms)
      (let [nodes (apply subvec (vec (outliner-core/get-page-nodes @conn (d/entity @conn 1)))
                         (sort [(rand-int (count datoms)) (rand-int (count datoms))]))]
        (when (seq nodes)
          (outliner-core/indent-nodes! conn nodes))))))

(defn- op-outdent-nodes!
  [conn _seq-state]
  (let [datoms (d/datoms @conn :avet :data)]
    (when (seq datoms)
      (let [nodes (apply subvec (vec (outliner-core/get-page-nodes @conn (d/entity @conn 1)))
                         (sort [(rand-int (count datoms)) (rand-int (count datoms))]))]
        (when (seq nodes)
          (outliner-core/outdent-nodes! conn nodes))))))

(defn- op-move-nodes!
  [conn _seq-state]
  (let [datoms (d/datoms @conn :avet :data)]
    (when (seq datoms)
      (let [node (d/entity @conn (:e (g/generate (g/elements datoms))))
            nodes (outliner-core/get-children-nodes @conn node)
            target (loop [n 10 maybe-node (d/entity @conn (:e (g/generate (g/elements datoms))))]
                     (cond
                       (= 0 n)
                       nil
                       (outliner-core/contains-node? nodes maybe-node)
                       (recur (dec n) (d/entity @conn (:e (g/generate (g/elements datoms)))))
                       :else
                       maybe-node))]
        (when target
          (outliner-core/move-nodes! conn nodes target (g/generate g/boolean)))))))

(deftest test-random-op!
  (testing "random insert nodes"
    (dotimes [_ 20]
      (let [seq-state (volatile! 0)
            tree (gen-random-tree 20 (vswap! seq-state inc))
            conn (build-db-records tree)
            origin-db @conn
            *tx-data (volatile! [])]
        (binding [tx/listeners (volatile! [(fetch-tx-data *tx-data)])]
          (tx/save-transactions
           {}
           (println "(test-random-op!) random insert/delete/indent/outdent nodes (100 runs)")
           (dotimes [_ 100]
             ((g/generate (g/elements [op-insert-nodes!
                                       op-delete-nodes!
                                       op-indent-nodes!
                                       op-outdent-nodes!
                                       op-move-nodes!])) conn seq-state)))
          ;; undo all *tx-data, then validate it's equal to origin db
          (is (not= origin-db @conn))
          (undo-tx-data! conn @*tx-data)
          (is (= origin-db @conn)))))))

(comment
  (defn nodes [db ids] (mapv #(d/entity db %) ids))
  (def conn
    (d/create-conn {:next {:db/valueType :db.type/ref
                           :db/unique :db.unique/value}
                    :page {:db/valueType :db.type/ref}
                    :next+page {:db/tupleAttrs [:next :page]}}))
  (d/transact! conn [{:db/id 10 :is-page true}
                     {:db/id 11 :is-page true}
                     {:db/id -3 :data 222}
                     {:db/id -2 :next -3 :page 10}
                     {:db/id -1 :next -2 :page 10}
                     {:db/id -4 :page 11}])


  )