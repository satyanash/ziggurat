(ns ziggurat.messaging.consumer-test
  (:require [clojure.test :refer :all])
  (:require [langohr.channel :as lch]
            [ziggurat.config :refer [ziggurat-config]]
            [ziggurat.fixtures :as fix]
            [ziggurat.messaging.connection :refer [connection]]
            [ziggurat.messaging.consumer :refer :all]
            [ziggurat.messaging.dead-set :as dead-set]
            [ziggurat.messaging.producer :as producer]
            [ziggurat.retry :as retry]
            [ziggurat.util.rabbitmq :as util]))

(use-fixtures :once fix/init-rabbit-mq)

(defn- gen-message-payload [topic-entity]
  {:message {:gen-key (apply str (take 10 (repeatedly #(char (+ (rand 26) 65)))))}
   :topic-entity topic-entity})

(def topic-entity :default)

(defn- mock-mapper-fn [{:keys [retry-counter-atom
                               call-counter-atom
                               retry-limit
                               skip-promise
                               success-promise] :as opts}]
  (fn [message]
    (swap! call-counter-atom inc)
    (cond (< @retry-counter-atom (or retry-limit 5))
          (do (when retry-counter-atom (swap! retry-counter-atom inc))
              :retry)

          (= (:msg message) "skip")
          (do (when skip-promise (deliver skip-promise true))
              :skip)

          :else
          (do (when success-promise (deliver success-promise true))
              :success))))

(defn- block-and-retry-until [success-fn]
  (try
    (retry/with-retry {:count 5 :wait 1000} (success-fn))
    (catch Throwable e
      (println (.getMessage e)))))

(deftest test-retries
  (testing "when retry is enabled the mapper-fn for stream subscriber should be retried until return success"
    (fix/with-queues {topic-entity {:handler-fn #(constantly nil)}}
      (let [retry-counter       (atom 0)
            call-counter        (atom 0)
            success-promise     (promise)
            retry-count         5
            message-payload     (assoc (gen-message-payload topic-entity) :retry-count 2)
            original-zig-config (ziggurat-config)
            rmq-ch              (lch/open connection)]

        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:retry :count] (constantly retry-count))
                                                 (update-in [:retry :enabled] (constantly true))
                                                 (update-in [:jobs :instant :worker-count] (constantly 1))))]

          (start-retry-subscriber* (mock-mapper-fn {:retry-counter-atom retry-counter
                                                    :call-counter-atom  call-counter
                                                    :retry-limit        2
                                                    :success-promise    success-promise}) topic-entity [])

          (producer/publish-to-delay-queue message-payload)

          (when-let [promise-success? (deref success-promise 5000 :timeout)]
            (is (not (= :timeout promise-success?)))
            (is (= true promise-success?))
            (is (= 2 @retry-counter)))

          (util/close rmq-ch)))))

  (testing "when retry is enabled the mapper-fn should not be retried if it returns skip"
    (fix/with-queues {topic-entity {:handler-fn #(constantly nil)}}
      (let [retry-counter       (atom 0)
            skip-promise        (promise)
            call-counter        (atom 0)
            retry-count         5
            message-payload     (assoc (assoc-in (gen-message-payload topic-entity) [:message :msg] "skip") :retry-count 2)
            original-zig-config (ziggurat-config)
            rmq-ch              (lch/open connection)]

        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:retry :count] (constantly retry-count))
                                                 (update-in [:retry :enabled] (constantly true))
                                                 (update-in [:jobs :instant :worker-count] (constantly 1))))]

          (start-retry-subscriber* (mock-mapper-fn {:retry-counter-atom retry-counter
                                                    :call-counter-atom  call-counter
                                                    :skip-promise       skip-promise
                                                    :retry-limit        -1}) topic-entity [])

          (producer/publish-to-delay-queue message-payload)

          (when-let [promise-success? (deref skip-promise 5000 :timeout)]
            (is (not (= :timeout promise-success?)))
            (is (= true promise-success?))
            (is (= 0 @retry-counter)))

          (util/close rmq-ch)))))

  (testing "when retry is enabled the mapper-fn should be retried with the maximum specified times"
    (fix/with-queues {topic-entity {:handler-fn #(constantly nil)}}
      (let [retry-counter       (atom 0)
            call-counter        (atom 0)
            retry-count         5
            no-of-msgs          2
            original-zig-config (ziggurat-config)
            rmq-ch              (lch/open connection)]

        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:retry :count] (constantly retry-count))
                                                 (update-in [:retry :enabled] (constantly true))
                                                 (update-in [:jobs :instant :worker-count] (constantly 1))))]

          (start-retry-subscriber* (mock-mapper-fn {:retry-counter-atom retry-counter
                                                    :call-counter-atom  call-counter
                                                    :retry-limit        (* no-of-msgs 10)}) topic-entity [])

          (dotimes [_ no-of-msgs]
            (producer/retry (gen-message-payload topic-entity)))

          (block-and-retry-until (fn []
                                   (let [dead-set-msgs (count (dead-set/get-dead-set-messages-for-topic false topic-entity no-of-msgs))]
                                     (if (< dead-set-msgs no-of-msgs)
                                       (throw (ex-info "Dead set messages were never populated"
                                                       {:dead-set-msgs dead-set-msgs}))))))

          (is (= (* retry-count no-of-msgs) @retry-counter))
          (is (= no-of-msgs (count (dead-set/get-dead-set-messages-for-topic false topic-entity no-of-msgs)))))
        (util/close rmq-ch))))

  (testing "start subscribers should not call start-subscriber* when stream router is nil"
    (fix/with-queues {topic-entity {:handler-fn #(constantly nil)}}
      (let [no-of-workers       3
            original-zig-config (ziggurat-config)
            ch                  (lch/open connection)
            counter             (atom 0)]

        (with-redefs [ziggurat-config         (fn [] (-> original-zig-config
                                                         (update-in [:retry :enabled] (constantly true))
                                                         (update-in [:jobs :instant :worker-count] (constantly no-of-workers))))
                      start-retry-subscriber* (fn [_ _ _ _] (swap! counter inc))]

          (start-subscribers nil)

          (is (= 0 @counter))
          (util/close ch)))))

  (testing "start subscribers should call start-subscriber* according to the product of worker and mapper-fns in stream-routes"
    (let [no-of-workers       3
          original-zig-config (ziggurat-config)
          ch                  (lch/open connection)
          counter             (atom 0)
          stream-routes       {topic-entity {:handler-fn #(constantly nil)}
                               :test    {:handler-fn #(constantly nil)}}]
      (with-redefs [ziggurat-config         (fn [] (-> original-zig-config
                                                       (update-in [:retry :enabled] (constantly true))
                                                       (update-in [:jobs :instant :worker-count] (constantly no-of-workers))))
                    start-retry-subscriber* (fn [_ _ _] (swap! counter inc))]
        (start-subscribers stream-routes)
        (is (= (count stream-routes) @counter))
        (util/close ch)))))

(deftest start-channels-subscriber-test
  (testing "the mapper-fn for channel subscriber should be retried until return success when retry is enabled to for that channel"
    (let [retry-counter       (atom 0)
          call-counter        (atom 0)
          success-promise     (promise)
          retry-count         5
          message-payload     (gen-message-payload topic-entity)
          channel             :channel-1
          channel-fn          (mock-mapper-fn {:retry-counter-atom retry-counter
                                               :call-counter-atom  call-counter
                                               :retry-limit        2
                                               :success-promise    success-promise})
          original-zig-config (ziggurat-config)
          rmq-ch              (lch/open connection)]
      (fix/with-queues {topic-entity {:handler-fn #(constantly nil)
                                      channel     channel-fn}}
        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:stream-router topic-entity :channels channel :retry :count] (constantly retry-count))
                                                 (update-in [:stream-router topic-entity :channels channel :retry :enabled] (constantly true))
                                                 (update-in [:stream-router topic-entity :channels channel :worker-count] (constantly 1))))]
          (with-redefs [lch/open (fn [_] rmq-ch)]
            (start-channels-subscriber {channel channel-fn} topic-entity))
          (producer/retry-for-channel message-payload channel)
          (when-let [promise-success? (deref success-promise 5000 :timeout)]
            (is (not (= :timeout promise-success?)))
            (is (= true promise-success?))
            (is (= 2 @retry-counter)))
          (util/close rmq-ch)))))

  (testing "the mapper-fn for channel subscriber should not enqueue the message when retry is disabled for that channel"
    (let [retry-counter       (atom 0)
          call-counter        (atom 0)
          success-promise     (promise)
          message-payload     (gen-message-payload topic-entity)
          channel             :channel-1
          channel-fn          (mock-mapper-fn {:retry-counter-atom retry-counter
                                               :call-counter-atom  call-counter
                                               :retry-limit        2
                                               :success-promise    success-promise})
          original-zig-config (ziggurat-config)
          rmq-ch              (lch/open connection)]
      (fix/with-queues {topic-entity {:handler-fn #(constantly nil)
                                      channel     channel-fn}}
        (with-redefs [ziggurat-config (fn [] (-> original-zig-config
                                                 (update-in [:stream-router topic-entity :channels channel :retry :enabled] (constantly false))
                                                 (update-in [:stream-router topic-entity :channels channel :worker-count] (constantly 1))))]
          (start-channels-subscriber {channel channel-fn} topic-entity)
          (producer/publish-to-channel-instant-queue channel message-payload)
          (deref success-promise 5000 :timeout)
          (is (= 1 @call-counter))
          (util/close rmq-ch))))))
