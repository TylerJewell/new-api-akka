# Acknowledgements

This project is a port of **[QuantumNous/new-api](https://github.com/QuantumNous/new-api)**,
read and run at commit `f116414`.

## Licence and copyright

- **`QuantumNous/new-api` is licensed under the GNU Affero General Public License,
  version 3** — read from its own `LICENSE` file, whose first lines are the AGPLv3 header,
  and confirmed by its `NOTICE`: "new-api, Copyright (c) QuantumNous and contributors.
  This project is licensed under the GNU Affero General Public License v3.0."
- **`new-api-akka` is therefore licensed under AGPL-3.0 as well**, and ships a copy of that
  licence as `LICENSE`. This is not a scaffold default: behaviour is derived from AGPL'd
  code throughout, so the port is treated as a work the AGPL governs rather than as an
  independent one that happens to resemble it.
- **The repository is private.** Under the AGPL that is a decision with consequences — the
  licence's network clause is triggered by making the software available to users over a
  network, not by holding it, so a private repository keeps this port outside the obligation
  to offer corresponding source. Publishing it, or running it as a service anyone else
  reaches, would bring that obligation with it. Flagged here rather than buried, because it
  is the one part of this port's licensing that a later decision could get wrong.
- **`new-api`'s `NOTICE` adds terms under AGPLv3 §7(b)**: a modified version presenting a
  user interface must preserve the attribution "Frontend design and development by New API
  contributors" and a visible link to the original project. This port has no user interface
  at all (`gui/manifest.json` records `graphical_surface: "none"` and how that was
  established), so there is no about, legal, footer or attribution location for the notice
  to appear in. The attribution is carried here and in the README instead, which is the
  nearest thing this port has to the location the term names.

## What was copied

**Nothing verbatim.** Every file under `new-api-akka/src` was written fresh in Java against
behaviour read out of, and run against, the Go module. No source text, comments, error
messages, tests or fixtures were transcribed. Where a comment, the specification or the
question log cites a file and line range, that is a citation, not a copy.

Two files in this port's record do link against the original's code rather than restate it:

- `new-api-port/probes/source_probe/` and `new-api-port/bench/source_runner/` are Go
  programs with a `replace` directive onto a local clone of `new-api`. They **call** the
  original's exported functions; they contain none of its source. Neither is part of
  `new-api-akka` and neither is published with it.

## Behaviour is derived, plainly

The whole decision procedure this port rebuilds comes from reading and running `new-api`:
the priority-tier grouping with its clamp past the tier count, the `weight + 10` baseline
and the exact walk that makes the split 101:9 rather than 100:10, the two independent gates
on auto-disable, the default retry and disable status-code tables, the prompt-token floor
before pre-consuming, and the settle-as-signed-delta and refund-in-full rules. Each is cited
to a source file and function in `specs/SPEC-001-new-api.md` and `docs/question-log.md`.

## Also used

- **Akka** — the Akka Java SDK 3.6.3 and its runtime, which this port is rebuilt on.
  See [akka.io](https://akka.io).
- **Jackson**, **AssertJ**, **JUnit 5** — through the Akka SDK's own parent POM.
