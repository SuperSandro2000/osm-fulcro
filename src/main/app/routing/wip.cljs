(ns app.routing.wip
  (:require
    [app.application :refer [SPA]]
    [com.fulcrologic.fulcro.application :refer [current-state]]
    [com.fulcrologic.fulcro.algorithms.denormalize :refer [db->tree]]
    [loom.graph :as graph] ;; right now no multigraph support -> migrate to ubergraph
    [loom.attr :as attr]
    [loom.alg :as alg]
    [loom.derived :as derived]
    [app.application :refer [SPA]]
    [app.ui.leaflet.state :refer [mutate-datasets]]
    [com.fulcrologic.fulcro.components :refer [transact!]]))

(defn lineStringFeature->pointList [feature]
  (get-in feature [:geometry :coordinates]))

(defn lineStringFeature->simplePointList [feature]
  (let [pointlist (lineStringFeature->pointList feature)]
       [(first pointlist) (last pointlist)]))

(defn lineStringFeature->successors [feature feature$index points$index]
  (->> (lineStringFeature->simplePointList feature)
       (map points$index)
       ((fn [[from to]] {from {to (feature$index feature)}}))))

(defn weight
  "TODO"
  [& args]
  1)

(defn ->edge [edges_data i]
  (->> (get edges_data i)
       (map (fn [[to fi]] {to (weight fi)}))
       (apply merge)))

(defn ->edge_attrs [edges_data i]
  (->> (get edges_data i)
       (map (fn [[to fi]] {to {:fi fi}}))
       (apply merge)))

(defn ->loom [geojson]
  (let [features (:features geojson)
        lineStrings (filter #(#{"LineString"} (get-in % [:geometry :type])) features)
        lineStrings$index (zipmap lineStrings (range))

        points (->> (map lineStringFeature->simplePointList lineStrings)
                    (apply concat))
        _ (def pointsUnique (into [] (set points)))
        _ (def pointsUnique$index (zipmap pointsUnique (range)))

        _ (prn '(count lineStrings) (count lineStrings)
               '(count totalPointList) (count (apply concat (map lineStringFeature->pointList lineStrings)))
               '(count simplPointList) (count points)
               '(count pointsUnique) (count pointsUnique))

        edges_data (->> (map #(lineStringFeature->successors % lineStrings$index pointsUnique$index) lineStrings)
                        (apply merge-with merge)) ;; for multigraph other merging is needed

        nodes (->> (range (count pointsUnique))
                   (map (fn [i] (hash-map i (->edge edges_data i))))
                   (apply merge))

        nodes_attrs (->> pointsUnique
                         (map-indexed (fn [i [lng lat]] {i {:lng lng :lat lat
                                                            :loom.attr/edge-attrs (->edge_attrs edges_data i)}}))
                         (apply merge))]

       (assoc (graph/weighted-graph nodes)
              :attrs nodes_attrs)))

(defn path->geojson [lngLatPath]
  {:type "FeatureCollection"
   :features [{:type "Feature"
               :geometry {:type "LineString"
                          :coordinates (into [] lngLatPath)}
               :properties {:style {:stroke "darkgreen"
                                    :stroke-width 4}}}]})

(defn get-random [v]
  (nth v (rand-int (count v))))

(defn routing-example [geojson &[{:keys [use-caching?] :as options}]]
  (let [use-caching? (:use-caching? options (if-let [v (resolve 'g)]
                                                    (> (count (graph/nodes @v)) 1000)))
        g (if-let [v (and use-caching?
                          (resolve 'g))]
                  (do (prn "graph was cached") @v)
                  (def g
                       (->loom geojson)))
        sg (derived/subgraph-reachable-from g 2)
        _ (prn '(count (graph/nodes g)) (count (graph/nodes g))
               '(count (graph/edges g)) (count (graph/edges g)))
        _ (prn '(count (graph/nodes sg)) (count (graph/nodes sg))
               '(count (graph/edges sg)) (count (graph/edges sg)))

        from (or (if-let [v (resolve 'from)] @v) (->> (graph/nodes sg) get-random (get pointsUnique) (def from)))
        to   (or (if-let [v (resolve 'to  )] @v) (->> (graph/nodes sg) get-random (get pointsUnique) (def to)))
        [path length] (alg/dijkstra-path-dist g (pointsUnique$index from) (pointsUnique$index to))]

       (prn :from from :to to :path path :length length)
       (transact! SPA [(mutate-datasets {:path [:routes :data :geojson]
                                         :data (path->geojson (map #(get pointsUnique %) path))})])))

(comment
  (let [path [:leaflet/datasets :vvo :data :geojson]
        geojson (get-in (db->tree path (current-state SPA) {}) path)]
       (routing-example geojson {:use-caching? true})))
