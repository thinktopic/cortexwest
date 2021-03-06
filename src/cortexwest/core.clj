(ns cortexwest.core
  (:require
    [clojure.java.browse :refer [browse-url]]
    [thi.ng.geom.viz.core :as viz]
    [thi.ng.geom.svg.core :as svg]
    [thi.ng.geom.core.vector :as t-vec]
    [thi.ng.color.core :as t-color]
    [thi.ng.math.core :as t-math :refer [PI TWO_PI]]
    [clojure.core.matrix :as mat]
    [clojure.core.matrix.stats :as stats]
    [cortex.nn.network :as network]
    [cortex.graph :as graph]
    [cortex.loss :refer [softmax-result-to-unit-vector]]
    [cortex.nn.execute :as execute]
    [cortex.nn.layers :as layers]
    ;[tsne.core :as tsne]
    ))

;;; Helpers

(def random (java.util.Random.))

(defn rand-gaussian
  []
  (.nextGaussian random))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn tmp-path
  []
  (str (System/getProperty "java.io.tmpdir")
       (uuid)
       ".svg"))

(defn wavy-line
  [t]
  (let [x (t-math/mix (- PI) PI t)]
    [x (* (Math/cos (* 0.5 x)) (Math/sin (* x x x)))]))

(defn noisy-line
  [m b noise x]
  (+ (* m x) b (* noise (rand-gaussian))))

(defn round10
  [v]
  (Math/pow 10
            (Math/round (+ 0.5
                           (Math/log10 v)
                           (- (Math/log10 5.5))))))

(defn evaluate-network
  [network test-ds & {:keys [label-key batch-size ]
                      :or {label-key :y
                           batch-size 10}}]
  (/ (Math/sqrt (->> (execute/run network test-ds :batch-size batch-size)
                     (map
                       (fn [a b]
                         (Math/pow (- (get a label-key)
                                      (first (get b label-key)))
                                   2))
                       test-ds)
                     (reduce +)))
     (count test-ds)))

(defn xy-cluster
  [& {:keys [x y scale n label]}]
  (repeatedly n
          (fn []
            {:input [(+ x (* scale (rand-gaussian)))
                     (+ y (* scale (rand-gaussian)))]
             :label label})))

(defn input-map->vec
  [data]
  (map
    (fn [{:keys [input]}]
      [(first input) (second input)])
    data))



;;; Visualization tools

(defn export-viz
  [spec path]
  (->> spec
       (viz/svg-plot2d-cartesian)
       (svg/svg {:width 600 :height 600})
       (svg/serialize)
       (spit path)))

(defn show-viz
  [spec]
  (let [path (tmp-path)]
    (export-viz spec path)
    (browse-url (str "file://" path))))

(defn major-axis-for
  [v]
  (let [axis (round10 v)
        n-ticks (/ v axis)]
    (cond
      (> n-ticks 12.0) (* 10 axis)
      (< n-ticks 3.0) (* 0.1 axis)
      :default axis)))

(defn viz-for
  [data]
  (let [margin-size 0.05
        xs (map first data)
        ys (map second data)
        min-x (apply min xs)
        max-x (apply max xs)
        xr (- max-x min-x)
        x-major (major-axis-for xr)
        x-margin (* margin-size xr)
        min-y (apply min ys)
        max-y (apply max ys)
        yr (- max-y min-y)
        y-margin (* margin-size yr)
        y-major (major-axis-for yr)]
    {:x-axis (viz/linear-axis
               {:domain [(- min-x x-margin) (+ max-x x-margin)]
                :range  [50 580]
                :major  x-major
                :minor  (* 0.5 x-major)
                :pos    250})
     :y-axis (viz/linear-axis
               {:domain      [(- min-y y-margin) (+ max-y y-margin)]
                :range       [250 20]
                :major       y-major
                :minor       (* 0.5 y-major)
                :pos         50
                :label-dist  15
                :label-style {:text-anchor "end"}})
     :grid   {:attribs {:stroke "#caa"}
              :minor-y true}
     :data []}))

(defn hsv-rainbow
  [& {:keys [n] :or {n 8}}]
  (map (fn [v]
         @(t-color/as-css (t-color/hsva v 0.9 0.9)))
       (range 0 1.0 (/ 1.0 n))))

(defn viz-add-data
  [viz data & {:keys [layout
                      color]}]
  (let [color (or color (rand-nth (hsv-rainbow)))
        layout (case layout
                 :line viz/svg-line-plot
                 :scatter viz/svg-scatter-plot
                 viz/svg-line-plot)]
    (update-in viz [:data] conj
               {:values  data
                :attribs {:fill "none" :stroke color}
                :layout  layout})))

;; Machine learning examples

(defn regression
  []
  (let [x-data (range 0 10 0.1)
        y-data (map (partial noisy-line 0.3 0.5 0.5) x-data)
        data (map (fn [x y] {:x x :y y}) x-data y-data)
        network (network/linear-network
                  [(layers/input 1 1 1 :id :x)
                   (layers/linear 1 :id :y)])
        epoch-count 8
        network
        (loop [network network
               epoch 0]
         (if (> epoch-count epoch)
            (recur (execute/train network data :batch-size 3) (inc epoch))
            network))
        inferences (execute/run network (map #(dissoc % :y) data) :batch-size 50)
        model-data (map vector x-data (map (comp first :y) inferences))
        sample-data (map vector x-data y-data)
        viz (-> (viz-for sample-data)
                (viz-add-data sample-data :layout :scatter)
                (viz-add-data model-data :layout :line))]
    (show-viz viz)))

(defn regression-wavy
  []
  (let [data (map wavy-line (t-math/norm-range 800))
        data (filter #(and (> (first %) 0)
                           (< (first %) 2))
                     data)
        x-data (map first data)
        y-data (mat/scale (mapv second data) 5)
        data (map (fn [x y] {:x x :y y}) x-data y-data)
        network (network/linear-network
                  [(layers/input 1 1 1 :id :x)
                   (layers/linear 1 :id :y)])
        epoch-count 8
        network
        (loop [network network
               epoch 0]
         (if (> epoch-count epoch)
            (recur (execute/train network data :batch-size 3) (inc epoch))
            network))
        inferences (execute/run network (map #(dissoc % :y) data) :batch-size 50)
        model-data (map vector x-data (map (comp first :y) inferences))
        sample-data (map vector x-data y-data)
        viz (-> (viz-for sample-data)
                (viz-add-data sample-data :layout :line)
                (viz-add-data model-data :layout :line))]
    (show-viz viz)))

(defn mlp-regression
  [& [net]]
  (let [data (map wavy-line (t-math/norm-range 800))
        data (filter #(and (> (first %) 0)
                           (< (first %) 2))
                     data)
        x-data (map first data)
        y-data (mat/scale (mapv second data) 5)
        net-data (map (fn [x y] {:x x :y y}) x-data y-data)
        test-ds (map #(dissoc % :y) net-data)
        network (network/linear-network
                  [(layers/input 1 1 1 :id :x)
                   (layers/batch-normalization)
                   (layers/linear->logistic 50)
                   (layers/linear->relu 30)
                   (layers/linear 1 :id :y)])
        epoch-count 12
        trained-network
        (loop [net network
               epoch 0]
         (if (> epoch-count epoch)
           (let [train-data (shuffle (apply concat (repeat 200 net-data)))
                 new-net (execute/train net train-data :batch-size 50)
                 mse (evaluate-network net net-data)]
             (println "mse: " mse)
            (recur new-net (inc epoch)))
            net))
        inferences (execute/run trained-network test-ds :batch-size 1)
        model-data (map vector x-data (map (comp first :y) inferences))
        sample-data (map vector x-data y-data)
        viz (-> (viz-for sample-data)
                (viz-add-data sample-data :layout :line :color "blue")
                (viz-add-data model-data :layout :line :color "red"))]
    (show-viz viz)))

(defn classifier
  []
  (let [train-a (xy-cluster :x 30 :y 50 :scale 3 :n 100 :label [1.0 0])
        train-b (xy-cluster :x 20 :y 10 :scale 2 :n 100 :label [0 1.0])
        train-data (concat train-a train-b)
        test-a (xy-cluster :x 30 :y 50 :scale 3 :n 10 :label [1.0 0])
        test-b (xy-cluster :x 20 :y 10 :scale 2 :n 10 :label [0 1.0])
        test-data (concat test-a test-b)
        network (network/linear-network
                  [(layers/input 2 1 1 :id :input)
                   (layers/linear->relu 50)
                   (layers/linear->relu 30)
                   (layers/linear 2)
                   (layers/softmax :output-channels 2 :id :label)])
        epoch-count 10
        network
        (loop [network network
               epoch 0]
         (if (> epoch-count epoch)
           (let [data (shuffle (apply concat (repeat 200 train-data)))]
             (recur (execute/train network data :batch-size 10)
                    (inc epoch)))
            network))
        predictions (execute/run network test-data :batch-size 10)
        predictions (map #(update-in % [:label] softmax-result-to-unit-vector) predictions)
        predictions (map merge test-data predictions)
        model-data (group-by :label predictions)
        ;_ (println model-data)
        a-data (input-map->vec (get model-data [1.0 0]))
        b-data (input-map->vec (get model-data [0 1.0]))
        _ (println "class counts a:" (count a-data) " b:" (count b-data))
        a-train-data (input-map->vec train-a)
        b-train-data (input-map->vec train-b)
        colors (hsv-rainbow)
        viz (-> (viz-for (concat a-train-data b-train-data))
                (viz-add-data (take 10 a-train-data)
                              :layout :scatter :color "rgb(10, 10, 10)")
                (viz-add-data (take 10 b-train-data)
                              :layout :scatter :color "rgb(10, 10, 10)")
                (viz-add-data a-data :layout :scatter :color (nth colors 3))
                (viz-add-data b-data :layout :scatter :color (nth colors 7))
                )]
    (show-viz viz)))

