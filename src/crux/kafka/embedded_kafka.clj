(ns ^{:doc "Embedded Kafka for self-contained Crux deployments."}
    crux.kafka.embedded-kafka
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import [kafka.server
            KafkaConfig KafkaServerStartable]
           [org.apache.zookeeper.server
            ServerCnxnFactory ServerCnxnFactory ZooKeeperServer]
           java.io.Closeable))

;; Based on:
;; https://github.com/pingles/clj-kafka/blob/master/test/clj_kafka/test/utils.clj
;; https://github.com/chbatey/kafka-unit/blob/master/src/main/java/info/batey/kafka/unit/KafkaUnit.java

(def ^:dynamic ^String *host* "localhost")
(def ^:dynamic ^String *broker-id* "0")

(def default-zookeeper-port 2182)
(def default-kafka-port 9092)

(def default-kafka-broker-config
  {"host" *host*
   "port" (int default-kafka-port)
   "broker.id" *broker-id*
   "offsets.topic.replication.factor" "1"
   "transaction.state.log.replication.factor" "1"
   "transaction.state.log.min.isr" "1"
   "auto.create.topics.enable" "false"})

(defn start-kafka-broker ^KafkaServerStartable [config]
  (doto (KafkaServerStartable. (KafkaConfig. (merge default-kafka-broker-config config)))
    (.startup)))

(defn stop-kafka-broker [^KafkaServerStartable broker]
  (some-> broker .shutdown)
  (some-> broker .awaitShutdown))

(defn start-zookeeper
  (^ServerCnxnFactory [data-dir]
   (start-zookeeper data-dir default-zookeeper-port))
  (^ServerCnxnFactory [data-dir ^long port]
   (let [tick-time 500
         max-connections 16
         server (ZooKeeperServer. (io/file data-dir) (io/file data-dir) tick-time)]
     (doto (ServerCnxnFactory/createFactory port max-connections)
       (.startup server)))))

(defn stop-zookeeper [^ServerCnxnFactory server-cnxn-factory]
  (some-> ^ServerCnxnFactory server-cnxn-factory .shutdown))

(defrecord EmbeddedKafka [zookeeper kafka bootstrap-servers]
  Closeable
  (close [_]
    (stop-kafka-broker kafka)
    (stop-zookeeper zookeeper)))

(s/def ::zookeeper-data-dir string?)
(s/def ::kafka-log-dir string?)
(s/def ::zk-port (s/int-in 1 65536))
(s/def ::kafka-port (s/int-in 1 65536))
(s/def ::broker-config map?)

(s/def ::options (s/keys :req-un [::zookeeper-data-dir
                                  ::kafka-log-dir]
                         :opt-un [::zk-port
                                  ::kafka-port
                                  ::broker-config]))

(defn ^EmbeddedKafka start-embedded-kafka
  "Starts Zookeeper and Kafka locally. This can be used to run Crux in a
  self-contained single node mode. The options zookeeper-data-dir and
  kafka-log-dir are required.

  Returns a crux.kafka.embedded_kafka.EmbeddedKafka component that
  implements java.io.Closeable, which allows Zookeeper and Kafka to be
  stopped by calling close.

  NOTE: requires org.apache.kafka/kafka_2.11 and
  org.apache.zookeeper/zookeeper on the classpath."
  [{:keys [zookeeper-data-dir zookeeper-port kafka-log-dir kafka-port broker-config]
    :or {zookeeper-port default-zookeeper-port
         kafka-port default-kafka-port}
    :as options}]
  (when (s/invalid? (s/conform :crux.kafka.embedded-kafka/options options))
    (throw (IllegalArgumentException.
            (str "Invalid options: " (s/explain-str :crux.kafka.embedded-kafka/options options)))))
  (let [zookeeper (start-zookeeper (io/file zookeeper-data-dir) zookeeper-port)
        kafka (try
                (start-kafka-broker (merge broker-config
                                           {"log.dir" (str (io/file kafka-log-dir))
                                            "port" (int kafka-port)
                                            "zookeeper.connect" (str *host* ":" zookeeper-port)}))
                (catch Throwable t
                  (stop-zookeeper zookeeper)
                  (throw t)))]
    (->EmbeddedKafka zookeeper kafka (str *host* ":" kafka-port))))
