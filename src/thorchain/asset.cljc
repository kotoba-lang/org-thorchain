(ns thorchain.asset
  "THORChain asset notation: `CHAIN.SYMBOL` / `CHAIN.SYMBOL-CONTRACT`, plus the
  synth (`/`) and trade-account (`~`) variants. Pure `.cljc`, no dependencies.

  Asset notation is load-bearing, not cosmetic: it is the only thing in a swap
  memo that says WHICH asset the user gets. `ETH.USDC-0X…` and `AVAX.USDC-0X…`
  differ by one field and are different tokens on different chains, so this
  namespace parses and re-renders rather than doing string surgery at call
  sites."
  (:require [clojure.string :as str]))

;; Native gas assets of chains THORChain supports for BTC<->ETH-class flows.
;; Not an exhaustive registry — deliberately: a hard-coded \"supported assets\"
;; list goes stale silently as pools are added and churned. Ask the node
;; (`/thorchain/pools`) for what is actually tradeable right now; this map only
;; names the handful of gas assets whose spelling is worth pinning.
(def gas-assets
  {:btc  "BTC.BTC"
   :eth  "ETH.ETH"
   :bch  "BCH.BCH"
   :ltc  "LTC.LTC"
   :doge "DOGE.DOGE"
   :avax "AVAX.AVAX"
   :bsc  "BSC.BNB"
   :atom "GAIA.ATOM"
   :rune "THOR.RUNE"})

(def ^:private separators
  {\. :layer1     ; ETH.ETH        — the real asset on its native chain
   \/ :synth      ; ETH/ETH        — a synthetic backed by the pool
   \~ :trade})    ; ETH~ETH        — a trade-account balance

(defn parse
  "\"ETH.USDC-0X A0B8…\" -> {:chain \"ETH\" :symbol \"USDC\" :contract \"0XA0B8…\"
  :kind :layer1 :asset \"ETH.USDC-0XA0B8…\"}. Returns nil for anything that is
  not asset notation, so callers can distinguish \"absent\" from \"malformed\"."
  [s]
  (when (string? s)
    (let [s (str/trim s)
          idx (first (keep-indexed (fn [i c] (when (separators c) i)) s))]
      (when (and idx (pos? idx) (< (inc idx) (count s)))
        (let [chain (str/upper-case (subs s 0 idx))
              kind (separators (nth s idx))
              rest-part (subs s (inc idx))
              [sym contract] (str/split rest-part #"-" 2)]
          {:chain chain
           :symbol (str/upper-case sym)
           :contract (some-> contract str/upper-case)
           :kind kind
           :asset (str chain (nth s idx) (str/upper-case rest-part))})))))

(defn format-asset
  "{:chain :symbol :contract :kind} -> asset notation string."
  [{:keys [chain symbol contract kind] :or {kind :layer1}}]
  (let [sep (case kind :layer1 "." :synth "/" :trade "~")]
    (str (str/upper-case chain) sep (str/upper-case symbol)
         (when (seq contract) (str "-" (str/upper-case contract))))))

(defn valid?
  "Is `s` parseable asset notation?"
  [s]
  (some? (parse s)))

(defn evm-chain?
  "Does this asset live on an EVM chain (so a swap out of it needs an ERC-20
  allowance / router call rather than a plain transfer)?"
  [asset]
  (contains? #{"ETH" "AVAX" "BSC" "BASE"} (:chain (parse asset))))

(defn token?
  "Is this a contract token rather than a chain's gas asset?"
  [asset]
  (some? (:contract (parse asset))))

(defn short-form
  "Shorten an asset for a memo: THORChain accepts abbreviated notation, and a
  Bitcoin memo must fit in an 80-byte OP_RETURN, so `ETH.USDC-0X A0B8…EB48` ->
  `ETH.USDC-EB48` (chain + symbol + last 4 of the contract) buys ~35 bytes.

  Returns the asset unchanged when it has no contract part."
  [asset]
  (if-let [{:keys [chain symbol contract kind]} (parse asset)]
    (if contract
      (format-asset {:chain chain :symbol symbol :kind kind
                     :contract (subs contract (max 0 (- (count contract) 4)))})
      (format-asset {:chain chain :symbol symbol :kind kind}))
    asset))
