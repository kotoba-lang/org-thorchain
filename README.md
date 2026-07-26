# org-thorchain

[![CI](https://github.com/kotoba-lang/org-thorchain/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/org-thorchain/actions/workflows/ci.yml)

**THORChain swap-memo grammar, quote/inbound requests as data, and verification
of what a quote endpoint hands back. Pure `.cljc`, zero dependencies, no network
access.**

Part of the kotoba-lang swap plane (ADR-2607261500). This is the **native
BTC ↔ ETH rail**: no wrapped assets, no custody, and — the part that pays for a
wallet — an affiliate fee the network itself skims on-chain.

## Why THORChain is the rail for native BTC ↔ ETH

Bitcoin has no smart contracts, so a genuinely on-chain BTC ↔ ETH swap has only
three shapes: an HTLC atomic swap (trustless but needs your own counterparty
liquidity and has an unusable UX), a wrapped asset (WBTC/tBTC — which is really
an ERC-20 swap plus a custodian), or a native cross-chain liquidity network.

THORChain is the third. And it makes the fee question trivial: **you write your
affiliate name and basis points into the memo, and the network skims the fee and
pays you, on-chain, as part of the swap.** No fee-collecting contract to write,
deploy, audit, or custody funds in. A swap out of Bitcoin is an ordinary BTC
transfer to a vault address with a memo — nothing more.

## The memo *is* the transaction

```clojure
(require '[thorchain.memo :as memo])

(memo/build {:to-asset "ETH.ETH"
             :destination "0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0"
             :limit "10000000"          ; 1e8 fixed point, minimum output
             :streaming-interval 3      ; sub-swaps, to cut slippage
             :streaming-quantity 0
             :affiliate "kb"            ; your THORName
             :affiliate-bps 30})        ; your 0.30% — skimmed on-chain
;=> "=:ETH.ETH:0xe6a30f4f3bad978910e2cbb4d97581f5b5a0ade0:10000000/3/0:kb:30"
```

Grammar (verified 2026-07-26 against **both** the THORNode parser source and the
published memo docs):

```
SWAP:ASSET:DESTADDR:LIM/INTERVAL/QUANTITY:AFFILIATE:FEE:DEX_AGG:DEX_ADDR:DEX_LIM
  0    1      2              3               4       5    6       7       8
```

`build` refuses to emit anything that would fail expensively:

| guard | why |
|---|---|
| **80-byte limit** | A swap *from* Bitcoin carries its memo in an `OP_RETURN`. An over-long memo isn't truncated — the tx doesn't relay, or it relays unreadable and the vault treats the funds as a donation. This is the most expensive failure in the flow, so it throws rather than warns. `:short-asset? true` abbreviates a token contract to its last 4 hex digits to fit. |
| **fee ceiling 1000 bps** | The docs say 0–1000 bps; THORNode itself only rejects a *total* above 10000 bps (100%). This library defaults to the documented 1000 and needs explicit `:allow-node-max? true` to exceed it, because the loose reading's failure mode is a wallet quietly charging 50%. |
| **shared-bps arithmetic** | `:affiliate ["a" "b" "c"] :affiliate-bps 300` is **900 bps total**, not 300. One value applies to *each* affiliate. Counted against the ceiling accordingly. |
| **affiliate without bps** | Rejected. A missing bps is a silent 0% fee, not a default. |
| **integer 1e8 amounts** | A decimal `:limit` means the caller confused units. The grammar accepts `1e6`, but this emits plain integers: a misread exponent silently changes slippage protection. |

## Never submit a memo you haven't read

`/thorchain/quote/swap` returns both the vault address to pay *and* the memo to
attach. Together those are **complete authority over the funds** — the memo says
where the output goes. Submitting them unread means trusting whatever answered
that HTTP call with the user's money, including a hijacked or compromised
endpoint.

```clojure
(require '[thorchain.quote :as q])

(def request {:from-asset "BTC.BTC" :to-asset "ETH.ETH" :amount "10000000"
              :destination "0xe6a3…" :affiliate "kb" :affiliate-bps 30})

(q/url q/mainnet-base-url (q/swap-quote-request request))
;; …your HTTP client…
(def quote (q/parse-swap-quote body))

(q/verify-memo request (:memo quote))
;=> {:ok? true :parsed {…}}

;; a substituted destination fails locally, not on-chain:
;=> {:ok? false :problems [{:problem :destination-mismatch
;                           :requested "0xe6a3…" :returned "0xdeadbeef…"}]}
```

`verify-memo` checks the destination (case-insensitively — EVM addresses are
checksummed inconsistently across APIs), the asset's chain+symbol (tolerating
the node's abbreviated contract form), the affiliate list, and the total basis
points in **both** directions: a quote that silently drops your fee fails too.

It deliberately does *not* compare against a locally rebuilt memo string — the
node legitimately emits its own limit field and abbreviated assets, so string
equality would fail on correct memos and train people to skip the check.

Also always re-read `inbound_addresses` before sending: **vault addresses rotate**
as the validator set churns, and a halted chain still publishes one.

```clojure
(def inbound (q/parse-inbound-addresses body))
(q/chain-sendable? inbound "BTC")   ;=> true / false (halts respected)
```

## No HTTP in here

Every request is `{:method :get :path … :query {…}}` and every parser takes an
already-decoded body. The caller supplies the transport. That keeps this pure
`.cljc` — the same code runs in a browser wallet, under nbb, and on the JVM — and
means the whole test suite runs without a network.

It also means the node URL is *yours to choose*. `mainnet-base-url` is a
convenience, not a default baked into requests: a public endpoint is a trusted
third party in a flow whose entire premise is not needing one.

## API

| ns | what |
|---|---|
| `thorchain.memo` | `build` / `parse` / `total-affiliate-bps`, `max-memo-bytes`, `max-affiliate-bps` |
| `thorchain.quote` | `swap-quote-request` / `inbound-addresses-request` / `pools-request` / `url`, `parse-swap-quote` / `parse-inbound-addresses`, **`verify-memo`**, `chain-sendable?` |
| `thorchain.asset` | `parse` / `format-asset` / `valid?` / `short-form` / `evm-chain?` / `token?`, `gas-assets` |

There is no hard-coded list of tradeable assets — that goes stale silently as
pools churn. Ask `/thorchain/pools`.

## Verification

The builder reproduces **THORChain's own published memo examples verbatim** —
external vectors, not this library's output — and the parser round-trips them,
including the awkward ones: `s:` short prefix, `1e6` scientific limit, five
affiliates sharing one bps value, per-affiliate bps, and the interior empty limit
field in `=:ETH.ETH:0x…::t1/dx/ss:10/20/30`.

`verify-memo` is tested against the adversarial cases it exists for: substituted
destination, substituted asset, dropped affiliate fee, inflated affiliate fee,
non-swap memo.

```bash
clojure -M:test                             # JVM  — 43 tests, 116 assertions
nbb --classpath src:test bin/run_tests.cljs  # cljs — same suite
clojure -M:lint
```

## Scope

This library builds and checks the **data**. It does not hold keys, sign, or
broadcast: a BTC-side swap is signed with `kotoba-lang/btc-crypto`, an ETH-side
one with `eth-crypto` + `erc20`, and `kotoba-lang/swap` orchestrates. Fees are
booked through `kotoba-lang/treasury`.

## License

Apache-2.0
