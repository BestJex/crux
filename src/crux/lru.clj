(ns crux.lru
  (:require [clojure.spec.alpha :as s]
            [crux.db :as db]
            [crux.index :as idx]
            [crux.kv :as kv]
            [crux.memory :as mem])
  (:import java.io.Closeable
           [java.lang.reflect InvocationHandler Proxy]
           [java.util Collections LinkedHashMap]
           java.util.concurrent.locks.StampedLock
           java.util.function.Function
           [clojure.lang Counted ILookup]
           org.agrona.concurrent.UnsafeBuffer
           [org.agrona DirectBuffer MutableDirectBuffer]))

(set! *unchecked-math* :warn-on-boxed)

(defprotocol LRUCache
  (compute-if-absent [this k f])
  (evict [this k]))

(defn new-cache [^long size]
  (let [cache (proxy [LinkedHashMap] [size 0.75 true]
                (removeEldestEntry [_]
                  (> (count this) size)))
        lock (StampedLock.)]
    (reify
      LRUCache
      (compute-if-absent [_ k f]
        (if (.containsKey cache k)
          (let [stamp (.writeLock lock)]
            (try
              (.computeIfAbsent cache k (reify Function
                                          (apply [_ k]
                                            (f k))))
              (finally
                (.unlock lock stamp))))
          (let [v (f k)
                stamp (.writeLock lock)]
            (try
              (.computeIfAbsent cache k (reify Function
                                          (apply [_ k]
                                            v)))
              (finally
                (.unlock lock stamp))))))

      (evict [_ k]
        (let [stamp (.writeLock lock)]
          (try
            (.remove cache k)
            (finally
              (.unlock lock stamp)))))

      ILookup
      (valAt [this k]
        (let [stamp (.writeLock lock)]
          (try
            (.get cache k)
            (finally
              (.unlock lock stamp)))))

      (valAt [this k default]
        (let [stamp (.writeLock lock)]
          (try
            (.getOrDefault cache k default)
            (finally
              (.unlock lock stamp)))))

      Counted
      (count [_]
        (.size cache)))))

(defrecord CachedObjectStore [cache object-store]
  db/ObjectStore
  (get-single-object [this snapshot k]
    (compute-if-absent
     cache
     k
     #(db/get-single-object object-store snapshot %)))

  (get-objects [this snapshot ks]
    (->> (for [k ks
               :let [v (compute-if-absent
                        cache
                        k
                        #(get (db/get-objects object-store snapshot [%]) %))]
               :when v]
           [k v])
         (into {})))

  (put-objects [this kvs]
    (db/put-objects object-store kvs))

  (delete-objects [this ks]
    (doseq [k ks]
      (evict cache k))
    (db/delete-objects object-store ks))

  Closeable
  (close [_]))

;; NOTE: this only tracks buffers created by the iterator and calls to
;; crux.memory/slice, so might miss things. Adds significant overhead
;; so only useful for debugging.
(def ^:const track-buffers? (Boolean/parseBoolean (System/getenv "CRUX_TRACK_BUFFERS")))

(definterface TrackedBuffer
  (^crux.lru.TrackedBuffer trackSlice [^org.agrona.concurrent.UnsafeBuffer b]))

(defn- tracking-buffer [b buffer-state]
  (try
    (Proxy/newProxyInstance
     (.getClassLoader TrackedBuffer)
     (into-array [MutableDirectBuffer TrackedBuffer])
     (reify InvocationHandler
       (invoke [_ proxy m args]
         (if (= (.getName m) "trackSlice")
           (tracking-buffer (first args) buffer-state)
           (do (when-not (contains? @buffer-state b)
                 (throw (IllegalStateException.
                         (str "Buffer has gone out of Iterator scope: " (pr-str b)))))
               (.invoke m b args))))))
    (finally
      (swap! buffer-state conj b))))

(defn- new-tracking-buffer [b buffer-state]
  (if (or (not track-buffers?)
          (nil? b))
    b
    (tracking-buffer b (doto buffer-state
                         (reset! #{})))))

(defn- tracking-slice [^DirectBuffer buffer ^long offset ^long limit]
  (let [b (UnsafeBuffer. buffer offset limit)]
    (if (instance? TrackedBuffer buffer)
      (.trackSlice ^TrackedBuffer buffer b)
      b)))

(when track-buffers?
  (alter-var-root #'mem/slice-buffer (constantly tracking-slice)))

(defrecord TrackingIterator [i buffer-state]
  kv/KvIterator
  (seek [_ k]
    (new-tracking-buffer (kv/seek i k) buffer-state))

  (next [_]
    (new-tracking-buffer (kv/next i) buffer-state))

  (value [_]
    (new-tracking-buffer (kv/value i) buffer-state))

  (refresh [this]
    (reset! buffer-state #{})
    (assoc this :i (kv/refresh i)))

  Closeable
  (close [_]
    (reset! buffer-state #{})
    (.close ^Closeable i)))

(defn- ensure-iterator-open [closed-state]
  (when @closed-state
    (throw (IllegalStateException. "Iterator closed."))))

(defrecord CachedIterator [i ^StampedLock lock closed-state]
  kv/KvIterator
  (seek [_ k]
    (let [stamp (.readLock lock)]
      (try
        (ensure-iterator-open closed-state)
        (kv/seek i k)
        (finally
          (.unlock lock stamp)))))

  (next [_]
    (let [stamp (.readLock lock)]
      (try
        (ensure-iterator-open closed-state)
        (kv/next i)
        (finally
          (.unlock lock stamp)))))

  (prev [_]
    (let [stamp (.readLock lock)]
      (try
        (ensure-iterator-open closed-state)
        (kv/prev i)
        (finally
          (.unlock lock stamp)))))

  (value [_]
    (let [stamp (.readLock lock)]
      (try
        (ensure-iterator-open closed-state)
        (kv/value i)
        (finally
          (.unlock lock stamp)))))

  (refresh [this]
    (let [stamp (.readLock lock)]
      (try
        (ensure-iterator-open closed-state)
        (assoc this :i (kv/refresh i))
        (finally
          (.unlock lock stamp)))))

  Closeable
  (close [_]
    (let [stamp (.readLock lock)]
      (try
        (ensure-iterator-open closed-state)
        (reset! closed-state true)
        (finally
          (.unlock lock stamp))))))

(defrecord CachedSnapshot [^Closeable snapshot close-snapshot? ^StampedLock lock iterators-state]
  kv/KvSnapshot
  (new-iterator [_]
    (if-let [^CachedIterator i (->> @iterators-state
                                    (filter (fn [^CachedIterator i]
                                              @(.closed-state i)))
                                    (first))]
      (if (compare-and-set! (.closed-state i) true false)
        (kv/refresh i)
        (recur))
      (let [i (kv/new-iterator snapshot)
            i (if track-buffers?
                (->TrackingIterator i (atom #{}))
                i)
            i (->CachedIterator i lock (atom false))]
        (swap! iterators-state conj i)
        i)))

  Closeable
  (close [_]
    (doseq [^CachedIterator i @iterators-state]
      (let [stamp (.writeLock lock)]
        (try
          (reset! (.closed-state i) true)
          (.close ^Closeable (.i i))
          (finally
            (.unlock lock stamp)))))
    (when close-snapshot?
      (.close snapshot))))

(defn new-cached-snapshot ^crux.lru.CachedSnapshot [snapshot close-snapshot?]
  (->CachedSnapshot snapshot close-snapshot? (StampedLock.) (atom #{})))

(defprotocol CacheProvider
  (get-named-cache [this cache-name cache-size]))

;; TODO: this should be changed to something more sensible, this is to
;; simplify API usage, and the kv instance is the main
;; object. Potentially these caches should simply just live in the
;; main system directly, but that requires passing more stuff around
;; to the lower levels.
(defrecord CacheProvidingKvStore [kv cache-state]
  kv/KvStore
  (open [this options]
    (assoc this :kv (kv/open kv options)))

  (new-snapshot [_]
    (new-cached-snapshot (kv/new-snapshot kv) true))

  (store [_ kvs]
    (kv/store kv kvs))

  (delete [_ ks]
    (kv/delete kv ks))

  (fsync [_]
    (kv/fsync kv))

  (backup [_ dir]
    (kv/backup kv dir))

  (count-keys [_]
    (kv/count-keys kv))

  (db-dir [_]
    (kv/db-dir kv))

  (kv-name [_]
    (kv/kv-name kv))

  Closeable
  (close [_]
    (.close ^Closeable kv))

  CacheProvider
  (get-named-cache [this cache-name cache-size]
    (get (swap! cache-state
                update
                cache-name
                (fn [cache]
                  (or cache (new-cache cache-size))))
         cache-name)))

(defn new-cache-providing-kv-store [kv]
  (if (instance? CacheProvidingKvStore kv)
    kv
    (->CacheProvidingKvStore kv (atom {}))))

(s/def ::doc-cache-size nat-int?)

(def ^:const default-doc-cache-size (* 128 1024))

(defn new-cached-object-store
  ([kv]
   (new-cached-object-store kv default-doc-cache-size))
  ([kv cache-size]
   (->CachedObjectStore (get-named-cache kv ::doc-cache (or cache-size default-doc-cache-size))
                        (idx/->KvObjectStore kv))))
