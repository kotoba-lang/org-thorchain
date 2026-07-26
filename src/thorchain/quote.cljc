(ns thorchain.quote
  "THORNode quote + inbound-address requests as DATA, and — the part that
  matters — verification of what comes back.

  NO HTTP HAPPENS HERE. Each `*-request` returns
  `{:method :get :path … :query {…}}` and each `parse-*` consumes an
  already-decoded response body. The caller supplies the transport, so this
  namespace stays pure `.cljc`, testable without a network, and usable from a
  browser, nbb, or the JVM alike.

  WHY VERIFICATION IS THE POINT: `/thorchain/quote/swap` returns both the vault
  `inbound_address` to pay and the `memo` to attach. Those two fields together
  are complete authority over the funds — the memo says where the output goes.
  Submitting them unread means trusting whatever answered the HTTP call with the
  user's money, including a DNS-hijacked or compromised endpoint. `verify-memo`
  re-parses the returned memo and checks the destination, the affiliate and the
  basis points against what was actually requested, so a substituted destination
  fails locally instead of on-chain."
  (:require [clojure.string :as str]
            [thorchain.asset :as asset]
            [thorchain.memo :as memo]))

(def mainnet-base-url
  "THORNode's public mainnet API. Not baked into requests — `*-request` returns a
  path and the caller chooses the host, so a deployment can point at its own node
  (which it should: a public endpoint is a trusted third party in a flow whose
  whole premise is not needing one)."
  "https://thornode.ninerealms.com")

(def stagenet-base-url "https://stagenet-thornode.ninerealms.com")

;; ─── requests ────────────────────────────────────────────────────────────

(defn swap-quote-request
  "Build a `/thorchain/quote/swap` request.

    (swap-quote-request {:from-asset \"BTC.BTC\" :to-asset \"ETH.ETH\"
                         :amount \"10000000\"            ; 1e8 fixed point
                         :destination \"0xabc…\"
                         :affiliate \"myname\" :affiliate-bps 30
                         :streaming-interval 1 :streaming-quantity 0
                         :tolerance-bps 300})"
  [{:keys [from-asset to-asset amount destination affiliate affiliate-bps
           streaming-interval streaming-quantity tolerance-bps refund-address
           liquidity-tolerance-bps]}]
  (doseq [[k v] {:from-asset from-asset :to-asset to-asset}]
    (when-not (asset/valid? v)
      (throw (ex-info (str "thorchain: " k " is not valid asset notation") {k v}))))
  (when-not (and amount (re-matches #"\d+" (str amount)))
    (throw (ex-info "thorchain: :amount must be a non-negative integer in 1e8 fixed point"
                    {:amount amount})))
  {:method :get
   :path "/thorchain/quote/swap"
   :query (cond-> {"from_asset" (:asset (asset/parse from-asset))
                   "to_asset" (:asset (asset/parse to-asset))
                   "amount" (str amount)}
            destination             (assoc "destination" destination)
            affiliate               (assoc "affiliate"
                                           (if (sequential? affiliate)
                                             (str/join "/" affiliate) affiliate))
            affiliate-bps           (assoc "affiliate_bps"
                                           (if (sequential? affiliate-bps)
                                             (str/join "/" affiliate-bps)
                                             (str affiliate-bps)))
            streaming-interval      (assoc "streaming_interval" (str streaming-interval))
            streaming-quantity      (assoc "streaming_quantity" (str streaming-quantity))
            tolerance-bps           (assoc "tolerance_bps" (str tolerance-bps))
            liquidity-tolerance-bps (assoc "liquidity_tolerance_bps"
                                           (str liquidity-tolerance-bps))
            refund-address          (assoc "refund_address" refund-address))})

(defn inbound-addresses-request
  "Build a `/thorchain/inbound_addresses` request — the current vault address per
  chain, plus each chain's `halted` / `chain_lp_actions_paused` flags and its
  `router` (EVM chains). ALWAYS re-read this before sending: vault addresses
  rotate as the validator set churns, and paying a stale vault sends funds to an
  address the network no longer watches."
  []
  {:method :get :path "/thorchain/inbound_addresses"})

(defn pools-request
  "Build a `/thorchain/pools` request — what is actually tradeable right now, with
  depths. Prefer this over any hard-coded asset list."
  []
  {:method :get :path "/thorchain/pools"})

(defn url
  "`base-url` + a request map -> a full URL with an encoded query string."
  [base-url {:keys [path query]}]
  (str base-url path
       (when (seq query)
         (str "?" (str/join "&" (for [[k v] (sort query)]
                                  (str k "=" #?(:clj (java.net.URLEncoder/encode
                                                      (str v) "UTF-8")
                                                :cljs (js/encodeURIComponent (str v))))))))))

;; ─── responses ───────────────────────────────────────────────────────────

(defn- get* [m & ks] (some #(get m %) ks))

(defn parse-swap-quote
  "Normalize a `/thorchain/quote/swap` response body (a map with string or
  keyword keys) into kebab-case data. The raw body is kept under `:raw` — this
  API grows fields, and dropping them silently would hide a `warning` or a new
  fee component from whoever has to reason about a bad swap later."
  [body]
  (let [g (fn [k] (get* body k (keyword k)))]
    {:inbound-address (g "inbound_address")
     :memo (g "memo")
     :expected-amount-out (some-> (g "expected_amount_out") str)
     :expiry (g "expiry")
     :fees (g "fees")
     :slippage-bps (g "slippage_bps")
     :streaming-swap-blocks (g "streaming_swap_blocks")
     :total-swap-seconds (g "total_swap_seconds")
     :outbound-delay-seconds (g "outbound_delay_seconds")
     :recommended-min-amount-in (some-> (g "recommended_min_amount_in") str)
     :dust-threshold (some-> (g "dust_threshold") str)
     :router (g "router")
     :max-streaming-quantity (g "max_streaming_quantity")
     :warning (g "warning")
     :notes (g "notes")
     :raw body}))

(defn parse-inbound-addresses
  "Normalize `/thorchain/inbound_addresses` into {chain -> {…}}."
  [body]
  (into {}
        (for [entry (if (sequential? body) body [])
              :let [g (fn [k] (get* entry k (keyword k)))]]
          [(g "chain")
           {:chain (g "chain")
            :address (g "address")
            :router (g "router")
            :halted (boolean (g "halted"))
            :global-trading-paused (boolean (g "global_trading_paused"))
            :chain-trading-paused (boolean (g "chain_trading_paused"))
            :gas-rate (some-> (g "gas_rate") str)
            :gas-rate-units (g "gas_rate_units")
            :outbound-fee (some-> (g "outbound_fee") str)
            :dust-threshold (some-> (g "dust_threshold") str)
            :raw entry}])))

;; ─── verification ────────────────────────────────────────────────────────

(defn verify-memo
  "Check the memo a quote returned against what was requested. Returns
  `{:ok? true}` or `{:ok? false :problems [{…}]}` — data, not an exception, so a
  caller can surface every mismatch at once.

  Checks: it parses as a swap memo; the destination is byte-identical to the one
  requested (case-insensitively — EVM addresses are checksummed inconsistently
  across APIs); the to-asset chain and symbol match; the affiliate list matches;
  and the total affiliate bps is not LOWER than requested (a quote that quietly
  drops the fee is a real behaviour, not a hypothetical) nor higher than asked
  for (which would mean paying more than intended).

  What it deliberately does NOT do is compare the whole memo string to a locally
  rebuilt one: the node legitimately emits abbreviated assets and its own limit
  field, so string equality would fail on correct memos and train people to skip
  the check."
  [{:keys [destination to-asset affiliate affiliate-bps]} returned-memo]
  (let [parsed (memo/parse returned-memo)
        requested-affiliates (cond (nil? affiliate) []
                                   (sequential? affiliate) (vec affiliate)
                                   :else [affiliate])
        requested-bps (memo/total-affiliate-bps
                       {:affiliate requested-affiliates
                        :affiliate-bps (cond (nil? affiliate-bps) []
                                             (sequential? affiliate-bps) (vec affiliate-bps)
                                             :else [affiliate-bps])})
        problems
        (cond-> []
          (nil? parsed)
          (conj {:problem :not-a-swap-memo :memo returned-memo})

          (and parsed destination
               (not= (str/lower-case (str destination))
                     (str/lower-case (str (:destination parsed)))))
          (conj {:problem :destination-mismatch
                 :requested destination :returned (:destination parsed)})

          (and parsed to-asset (:to-asset parsed)
               (let [want (asset/parse to-asset) got (asset/parse (:to-asset parsed))]
                 (not (and (= (:chain want) (:chain got))
                           (= (:symbol want) (:symbol got))))))
          (conj {:problem :asset-mismatch
                 :requested to-asset :returned (:to-asset parsed)})

          (and parsed (seq requested-affiliates)
               (not= (set requested-affiliates) (set (:affiliate parsed))))
          (conj {:problem :affiliate-mismatch
                 :requested requested-affiliates :returned (:affiliate parsed)})

          (and parsed (not= requested-bps (memo/total-affiliate-bps parsed)))
          (conj {:problem :affiliate-bps-mismatch
                 :requested requested-bps :returned (memo/total-affiliate-bps parsed)}))]
    (if (seq problems)
      {:ok? false :problems problems :parsed parsed}
      {:ok? true :parsed parsed})))

(defn chain-sendable?
  "Is `chain` currently accepting inbound swaps, per a parsed
  inbound_addresses map? A halted chain still has a published vault address —
  sending to it during a halt means waiting for the halt to lift, or a refund."
  [inbound chain]
  (let [{:keys [halted global-trading-paused chain-trading-paused address]}
        (get inbound chain)]
    (boolean (and address (not halted) (not global-trading-paused)
                  (not chain-trading-paused)))))
