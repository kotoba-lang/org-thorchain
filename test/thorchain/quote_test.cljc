(ns thorchain.quote-test
  "Requests are asserted as data (no network), and `verify-memo` is tested with
  the adversarial cases it exists for: a quote endpoint that substitutes the
  destination address, swaps the asset, or quietly drops the affiliate fee."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [thorchain.asset :as asset]
            [thorchain.quote :as q]))

(def dest "0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0")

(def req
  {:from-asset "BTC.BTC" :to-asset "ETH.ETH" :amount "10000000"
   :destination dest :affiliate "kb" :affiliate-bps 30})

;; ── requests as data ──

(deftest swap-quote-request-shape
  (let [{:keys [method path query]} (q/swap-quote-request req)]
    (is (= :get method))
    (is (= "/thorchain/quote/swap" path))
    (is (= {"from_asset" "BTC.BTC" "to_asset" "ETH.ETH" "amount" "10000000"
            "destination" dest "affiliate" "kb" "affiliate_bps" "30"}
           query))))

(deftest swap-quote-request-streaming-and-tolerance
  (let [{:keys [query]} (q/swap-quote-request
                         (assoc req :streaming-interval 1 :streaming-quantity 0
                                :tolerance-bps 300))]
    (is (= "1" (query "streaming_interval")))
    (is (= "0" (query "streaming_quantity")))
    (is (= "300" (query "tolerance_bps")))))

(deftest swap-quote-request-multiple-affiliates
  (let [{:keys [query]} (q/swap-quote-request
                         (assoc req :affiliate ["a" "b"] :affiliate-bps [10 20]))]
    (is (= "a/b" (query "affiliate")))
    (is (= "10/20" (query "affiliate_bps")))))

(deftest swap-quote-request-validates-assets-and-amount
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (q/swap-quote-request (assoc req :to-asset "ETHETH"))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (q/swap-quote-request (assoc req :amount "0.5")))
      "amount is 1e8 fixed point — a decimal means the caller confused units"))

(def own-node "https://thornode.example.internal")

(deftest url-encodes-query
  (is (= (str own-node "/thorchain/quote/swap"
              "?affiliate=kb&affiliate_bps=30&amount=10000000&destination=" dest
              "&from_asset=BTC.BTC&to_asset=ETH.ETH")
         (q/url own-node (q/swap-quote-request req)))))

(deftest url-requires-a-base
  (testing "no default: a default pointing at a dead or bot-gated host would fail
            at the moment someone is moving funds, and look like a bug here"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (q/url nil (q/swap-quote-request req))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (q/url "  " (q/swap-quote-request req))))))

(deftest known-endpoints-records-measured-state
  (testing "the map is measurements, not aspirations"
    (is (= :dns-nxdomain (:state (get q/known-endpoints "thornode.ninerealms.com"))))
    (is (= :bot-protected (:state (get q/known-endpoints "thornode.thorswap.net"))))
    (is (every? :measured (vals q/known-endpoints)))))

(deftest inbound-and-pools-requests
  (is (= {:method :get :path "/thorchain/inbound_addresses"}
         (q/inbound-addresses-request)))
  (is (= {:method :get :path "/thorchain/pools"} (q/pools-request))))

;; ── response normalization ──

(def quote-body
  ;; Shape of a /thorchain/quote/swap response (string keys, as JSON decodes).
  {"inbound_address" "bc1qvault"
   "memo" (str "=:ETH.ETH:" dest ":0/1/0:kb:30")
   "expected_amount_out" "203529920800"
   "expiry" 1750000600
   "fees" {"asset" "ETH.ETH" "affiliate" "610589762" "outbound" "240000"
           "liquidity" "1017649604" "total" "1628239366" "slippage_bps" 50
           "total_bps" 80}
   "slippage_bps" 50
   "outbound_delay_seconds" 600
   "total_swap_seconds" 660
   "recommended_min_amount_in" "119000"
   "dust_threshold" "10000"
   "warning" "Do not cache this response."})

(deftest parse-swap-quote-normalizes
  (let [p (q/parse-swap-quote quote-body)]
    (is (= "bc1qvault" (:inbound-address p)))
    (is (= "203529920800" (:expected-amount-out p)))
    (is (= 50 (:slippage-bps p)))
    (is (= 600 (:outbound-delay-seconds p)))
    (is (= "Do not cache this response." (:warning p)))
    (is (= quote-body (:raw p)) "the raw body is kept, not dropped")))

(deftest parse-swap-quote-accepts-keyword-keys
  (is (= "bc1qvault"
         (:inbound-address (q/parse-swap-quote {:inbound_address "bc1qvault"})))))

(def inbound-body
  [{"chain" "BTC" "address" "bc1qvault" "halted" false
    "gas_rate" "10" "gas_rate_units" "satsperbyte" "dust_threshold" "10000"}
   {"chain" "ETH" "address" "0xvault" "router" "0xrouter" "halted" true
    "gas_rate" "30" "gas_rate_units" "gwei"}])

(deftest parse-inbound-addresses-keyed-by-chain
  (let [m (q/parse-inbound-addresses inbound-body)]
    (is (= #{"BTC" "ETH"} (set (keys m))))
    (is (= "bc1qvault" (get-in m ["BTC" :address])))
    (is (= "0xrouter" (get-in m ["ETH" :router])))))

(deftest chain-sendable-respects-halts
  (let [m (q/parse-inbound-addresses inbound-body)]
    (is (true? (q/chain-sendable? m "BTC")))
    (is (false? (q/chain-sendable? m "ETH")) "a halted chain still publishes a vault")
    (is (false? (q/chain-sendable? m "DOGE")) "unknown chain is not sendable")))

;; ── verification: the adversarial cases ──

(deftest verify-memo-accepts-a-matching-memo
  (is (:ok? (q/verify-memo req (get quote-body "memo")))))

(deftest verify-memo-tolerates-address-case
  (testing "EVM addresses are checksummed inconsistently across APIs"
    (is (:ok? (q/verify-memo req (str "=:ETH.ETH:" (str/upper-case dest)
                                      ":0/1/0:kb:30"))))))

(deftest verify-memo-catches-substituted-destination
  (let [attack (str "=:ETH.ETH:0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef:0/1/0:kb:30")
        {:keys [ok? problems]} (q/verify-memo req attack)]
    (is (false? ok?))
    (is (= :destination-mismatch (:problem (first problems))))))

(deftest verify-memo-catches-substituted-asset
  (let [{:keys [ok? problems]}
        (q/verify-memo req (str "=:BTC.BTC:" dest ":0/1/0:kb:30"))]
    (is (false? ok?))
    (is (some #(= :asset-mismatch (:problem %)) problems))))

(deftest verify-memo-catches-dropped-affiliate-fee
  (testing "a quote that silently drops the fee earns nothing and must not pass"
    (let [{:keys [ok? problems]} (q/verify-memo req (str "=:ETH.ETH:" dest))]
      (is (false? ok?))
      (is (some #(= :affiliate-mismatch (:problem %)) problems))
      (is (some #(= :affiliate-bps-mismatch (:problem %)) problems)))))

(deftest verify-memo-catches-inflated-fee
  (let [{:keys [ok? problems]}
        (q/verify-memo req (str "=:ETH.ETH:" dest ":0/1/0:kb:900"))]
    (is (false? ok?))
    (is (= {:problem :affiliate-bps-mismatch :requested 30 :returned 900}
           (first (filter #(= :affiliate-bps-mismatch (:problem %)) problems))))))

(deftest verify-memo-rejects-non-swap-memo
  (let [{:keys [ok? problems]} (q/verify-memo req "ADD:BTC.BTC")]
    (is (false? ok?))
    (is (= :not-a-swap-memo (:problem (first problems))))))

(deftest verify-memo-accepts-abbreviated-asset
  (testing "the node may abbreviate a token's contract; chain+symbol must match"
    (is (:ok? (q/verify-memo
               (assoc req :to-asset "ETH.USDC-0XA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48")
               (str "=:ETH.USDC-EB48:" dest ":0/1/0:kb:30"))))))

;; ── asset notation ──

(deftest asset-parse-and-format
  (is (= {:chain "ETH" :symbol "USDC" :contract "0XA0B8" :kind :layer1
          :asset "ETH.USDC-0XA0B8"}
         (asset/parse "eth.usdc-0xa0b8")))
  (is (= "ETH.ETH" (:asset (asset/parse "ETH.ETH"))))
  (is (= :synth (:kind (asset/parse "ETH/ETH"))))
  (is (= :trade (:kind (asset/parse "ETH~ETH"))))
  (is (nil? (asset/parse "ETHETH")))
  (is (nil? (asset/parse ".ETH")))
  (is (nil? (asset/parse "ETH.")))
  (is (true? (asset/evm-chain? "ETH.USDC-0XA0B8")))
  (is (false? (asset/evm-chain? "BTC.BTC")))
  (is (true? (asset/token? "ETH.USDC-0XA0B8")))
  (is (false? (asset/token? "ETH.ETH"))))
