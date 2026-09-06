# User-feedback research — what people value (2026-07-20)

Three parallel sweeps: store reviews of the dead/live competitors, organic demand threads
(HN/Reddit/MetaFilter/Zoom forum), and adjacent products (Otter/Fireflies/Gong/TeachFX/
Read.ai/MIT Meeting Mediator). Full agent reports summarised here; key sources inline.

## What killed the predecessors (avoid these)

- **Manual tapping** — GenderTimer's fatal flaw ("having to start and stop a timer is
  tedious"); their Kickstarter to add auto-detection never shipped.
- **Flaky auto-detection** — Talk-o-Meter ($4.99, 2011): "more a clever design concept
  than a working app"; 2 speakers max, quiet room only, dissimilar voices only.
- **Judgment framing** — gender-equity apps (GenderEQ, Woman Interrupted, Time To Talk)
  got press cycles, not retention; the binary voice-frequency premise also aged badly.
- **Platform dependency** — Meet extension died when Google changed the DOM.
- **Scoring others without consent** — Read.ai's talk-time scoring is called "a Black
  Mirror episode" / "a virus" in reviews.

## What users consistently value

1. **Fully passive, phone-on-table, zero setup.** The universally stated ideal UX.
2. **Live, glanceable feedback AT the table** — MIT Meeting Mediator (CSCW 2008) showed
   real-time ambient feedback rebalances conversations *in the moment* without
   distracting; post-hoc analytics are the feature "most users won't use".
   The live view is the product; the report is the souvenir.
3. **Privacy by architecture, as the headline.** Every loved product markets "not
   recorded, analysed in real time, nothing saved" (TeachFX: 5.0★ largely on this).
   On-device + stats-only storage is both the legal shield and the marketing.
4. **The "aha" revelation** — every positive quote is the shock ("men spoke 80% of the
   time", "very enlightening in showing me that I could do more"). Make it shareable
   (summary card) because the revelation is what gets posted.
5. **Benchmarks make numbers actionable** — Gong: "you spoke 78% vs top reps' 54%"
   changes behaviour; a naked % doesn't. Ours: "balanced table = everyone ≈ 1/N ±X".
6. **The visceral stats**: talk share %, **longest monologue** (most-quoted stat in every
   adjacent product), turn counts, interruptions (explicitly requested on the Zoom forum),
   turn-taking "bounce" (Gong's research says interactivity > raw share).
7. **Error-tolerant display.** Speaker-ID mistakes are the #1 churn driver in notetakers.
   Show aggregate bars (errors forgivable), never per-person transcripts/attributions
   ("the app said *I* said that" destroys trust). TeachFX trick: measure unenrolled
   voices collectively as "everyone else".
8. **Trends over time = retention** (TeachFX's stickiness; r/ADHD asker wanted "data to
   test my hypothesis"). Counteracts the one-trick-awareness-shock churn.

## Persona ranking (demand strength)

1. **Self-conscious over-talker / neurodivergent self-monitoring** — strongest organic
   signal: 451-pt HN thread on unblah.me full of first-person "I need this"; an r/ADHD
   ask with zero replies. Wants *private* feedback (haptic nudge, not public shaming).
   Gap: unblah is online-meetings-only; in-person is unserved.
2. Sales talk-ratio — proven money but B2B and served (Gong/Avoma). Not our fight.
3. Teachers (TTT) — validated by TeachFX but sold to districts.
4. Feminist meeting equity — loud press, poor retention. Don't lead with it.
5. Dinner-table curiosity (Marc's original) — real but thin; MetaFilter asker wanted a
   chess-clock manual UI as fallback.
6. Couples/therapy — latent only.

Willingness to pay: consumers essentially never state one → free + small one-off unlock
is the ceiling; treat revenue as thank-you money + portfolio value.

## Product implications (fast/cheap)

- **One engine, two framings.** Enrolled-speaker engine already covers both:
  *Table Mode* (family, everyone opted in, party-game framing, wager mode) and
  *Me Mode* (enrol only yourself; everything else is "everyone else" — a binary
  me/not-me classifier, technically FAR more robust than 4-way diarization, and it
  serves the strongest persona). Me Mode is nearly free once enrollment works.
- **Private monologue nudge** (vibrate after N seconds of continuous me-talk) — trivial
  to build, most-requested behaviour change feature.
- **Chess-clock manual mode** (~1 day) — tap-to-pass timer. Zero audio, works for 10–15
  people where diarization won't, doubles as the wager-mode engine, and is cheap
  insurance against accuracy complaints. (Exactly what the MetaFilter asker designed.)
- **Defer**: anonymous-clustering party mode, interruption detection (v1.1 — computable
  from segment overlaps later), word counts (cut), anything cloud.
- Cost base: £0 infra (all on-device, Apache-2.0/MIT models), $25 Play account.
