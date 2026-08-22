# new-api-akka

Chooses which of several paid model providers to send a chat request to, charges the
caller's balance for it, and tries another provider when one fails.

A port of [QuantumNous/new-api](https://github.com/QuantumNous/new-api) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

QuantumNous/new-api is a gateway that sits in front of many model providers and gives an
organisation one place to hold the accounts, meter the spending and share the capacity. It
was ported to derive a specification format precise enough to regenerate a system on a
different stack — the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `new-api-port/`.

---

## QuantumNous/new-api → this port

📉 688 Go lines → **612 Java lines**<br>
📁 7 files → **19 files**<br>
🧪 0 tests over this behaviour → **44 tests**<br>
⏱️ 411 µs → **1,850 µs** per request, each system doing its own storage<br>
🔎 3 database statements → **3 stored-value reads** per retried attempt<br>
🎯 18 answers compared, **18 matching**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/new-api-port/bench/REPORT.md).

The time figures are not measuring the same shape of work and neither is a claim about the
other. The original's is a function call inside one program that talks to a database on the
same machine; this port's crosses a network connection twice per request and keeps its
balances and provider records as separately stored values. What can be compared exactly is
the count on the line below it, and the answers on the line below that.

---

## What it took to build

⏱️ **28.3 hours** from the first command to the published repository, **1.4** of them active<br>
💬 **628** exchanges with the model<br>
✍️ **418,812** tokens written by the model, **127,999,235** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **44** tests

```bash
python toolkit/tokens.py --port new-api    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A provider is picked from the highest-priority group that has one.** Each further
  attempt drops to the next group down, and once there are no lower groups left it keeps
  offering the lowest one rather than giving up.
- **Every provider in a group can be picked, including one set to zero share.** Ten is
  added to every share before the draw, so a zero-share provider still gets a slice.
- **Money is taken before the request goes out and corrected afterwards.** The charge is
  worked out from the length of the question and a floor of five hundred tokens, then
  adjusted up or down once the real answer's length is known.
- **A request that fails on every attempt costs nothing.** The whole amount taken up front
  goes back, and nothing is charged.
- **Taking a provider out of rotation needs two switches on, and one failure.** A
  service-wide switch and the provider's own switch must both be on; when they are, the
  first failure that qualifies removes it, with no count of earlier failures kept anywhere.
- **A balance cannot go below zero by being spent.** Checking the balance and taking from
  it are one indivisible step, so two requests that between them cost more than is there
  can never both go ahead.

---

## Design decisions

**Two stored records instead of one database join.** The original asks its database one
question that reaches across two tables; here each provider and each group-and-model index
is its own stored value, so the same answer takes two lookups instead of one query. Both
read fresh every time, so a provider taken out of rotation stops receiving traffic on the
very next attempt.

**The list of candidates is read again on every attempt.** If it were read once at the
start, a provider removed partway through a request would keep being handed the rest of
that request's attempts. Reading it again means removing one takes effect immediately,
which is the whole point of removing it.

**One indivisible step in place of a conditional database update.** The original stops two
requests overdrawing the same balance by making the check and the subtraction a single
database statement; here the balance is a single stored thing that handles one instruction
at a time. The same guarantee comes out, without a database being involved in it.

**The attempts happen while the caller waits, rather than as a recorded job.** Somebody
asking a question wants the answer in the reply, and a recorded job would have to be asked
again later for it. Balances and provider records are still stored durably; only the
in-flight run of attempts is not.

**Failing for lack of money says so.** The reply carries a short reason for stopping, and a
refusal over the balance gets its own reason rather than sharing the one used when no
provider could be found. Somebody reading it is sent to the right place to look.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/new-api-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Send it a request** at http://localhost:9057.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

No key for a model provider is needed. This port decides *which* provider to send a request
to and what to charge for it; the call itself goes to whatever address is configured as the
upstream, and nothing here talks to a model directly.

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9057**.

### Use it

Register a provider, put money in an account, then send a request:

```bash
curl -X POST localhost:9057/admin/channels \
  -H 'Content-Type: application/json' \
  -d '{"id":1,"groups":["default"],"models":["gpt-4"],"priority":100,"weight":0,"autoBan":true}'

curl -X POST localhost:9057/admin/accounts/alice/deposit \
  -H 'Content-Type: application/json' -d '{"amount":100000}'

curl -i -X POST localhost:9057/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"account_id":"alice","group":"default","model":"gpt-4","prompt_tokens":100}'
```

The reply carries four extra headers: how many attempts were made, why the sequence
stopped, what was taken up front, and what was finally charged.

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `new-api.upstream-service` | `upstream` | The name of the service every request is forwarded to. |
| `new-api.dispatch.max-retries` | `2` | How many further attempts a failed request gets. `0` means one attempt and no retrying. |
| `new-api.pricing.model-ratio` | `1.0` | Multiplies the whole charge. |
| `new-api.pricing.completion-ratio` | `1.0` | Multiplies the answer's share of the charge only. |
| `new-api.pricing.group-ratio` | `1.0` | Multiplies the whole charge, per group. |
| `new-api.pricing.min-pre-consume-tokens` | `500` | The floor applied to the question's length before money is taken. |
| `new-api.retry-on-status-ranges` | every code except 2xx, 400, 408, 504, 524 | Which replies get another attempt. |
| `new-api.auto-disable.enabled` | `false` | The service-wide switch for taking a failing provider out of rotation. |
| `new-api.auto-disable.disable-on-status` | `[401]` | Which replies qualify for that, when the switch is on. |

---

## Where it differs from QuantumNous/new-api

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **How many further attempts a failed request gets.** QuantumNous/new-api ships zero, so
  out of the box a request is tried once and never retried unless an administrator raises
  the number. This port ships two, so that the retrying this port exists to reproduce can be
  seen without changing a setting first. The setting itself is the same on both sides.
- **Which provider wins a tie on share.** QuantumNous/new-api sorts its candidates by share,
  highest first, and stops there — two providers on the same share come back in whatever
  order the database gives them, and the first one gets a slightly larger slice than the
  second. This port sorts the same way and then puts the lower number first, because the
  original's answer here is its database's rather than its own, and because the lower-number
  ordering is the one the original was actually observed producing.
- **The reason given when a request is refused for lack of money.** QuantumNous/new-api
  fails that check before it looks for a provider at all, with an error of its own. This
  port reports it as its own reason on a header rather than as "no provider available",
  because the second sends a reader to look at provider settings for something that is
  entirely about the balance. Both refuse with the same code, 402.
- **What happens to a request in progress when the connection to the caller drops.**
  QuantumNous/new-api has no stated behaviour here and neither did this port until it was
  asked; the attempts run while the caller waits on both sides, so a dropped connection
  loses the reply. Money already taken is not returned by the drop itself on either side —
  it is settled or refunded when the attempts finish, which they do regardless. `not
  checked` against a real dropped connection on either side.
- **The second balance.** QuantumNous/new-api checks a per-key allowance before it checks
  the account balance, using the same indivisible check-and-subtract. This port has only the
  account balance, because the two are one rule applied twice rather than two rules, and
  reproducing it a second time would demonstrate nothing the first does not.
- **The other way of choosing a provider.** QuantumNous/new-api ships two, and a setting
  chooses between them; the one it uses unless told otherwise is the one this port
  reproduces. The other reaches a zero-share provider only when every candidate is on zero
  share, so the two disagree with each other in exactly that case before this port is
  involved at all.
- **Putting a provider back into rotation.** QuantumNous/new-api does this only when an
  administrator triggers a test of it. This port has the same operator command and nothing
  automatic, so the two agree; listed because a reader might reasonably expect a removed
  provider to come back on its own, and on neither system does it.
- **Anything the request carries beyond choosing and charging.** QuantumNous/new-api
  translates between a dozen provider dialects, streams answers back, counts tokens from the
  text, and applies pricing schemes this port does not have. This port treats the provider
  as an address that returns a code and a count. `not checked` — none of it was compared,
  because none of it was rebuilt.

---

## Licence

QuantumNous/new-api is under the GNU Affero General Public License version 3,
© QuantumNous and contributors. This port is a derived work and is under the same licence;
no source text was copied. See `ACKNOWLEDGEMENTS.md`.
