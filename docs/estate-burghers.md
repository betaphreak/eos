# Design note: the Burghers (the plutocratic estate)

**Status:** Design (not built) — the **plutocratic** spoke of the estate system. The shared
machinery lives in the spine, [`docs/estate-system.md`](estate-system.md); this doc holds only what
is specific to the Burghers: the republic's tradition bands, and the despot with his exit ladder.
**Date:** 2026-07-21 (split out of `estate-system.md`)
**Depends on:** the spine; the banking layer's equity seam (firm equity + bank deposits — the
influence base, already computed by the engine).
**Related:** [`estate-nobles.md`](estate-nobles.md) · [`estate-church.md`](estate-church.md) ·
[`estate-tribes.md`](estate-tribes.md); spine §7 (the mob-rule pole's post-estate exit).

---

## 1. The estate

The **Burghers'** register — `estate_burghers` in the import catalog (spine §3). A second plutocratic
estate exists: the **patricians** (`estate_castonath_patricians`), a merchant-patrician city elite
that also appears *inside theocracies* as their commercial estate ([`estate-church.md`](estate-church.md) §1).
Where the Burghers rule, the government is a **republic** (spine §2).

## 2. Sub-scale — `republican tradition`

| scale | form |
|---|---|
| **+100** | **elections / parliament** — the estates run a proper republic (a **medieval parliament / estates-general**; *in 1.0*, spine §7) |
| **`0` … `−50`** | **despotism** — a strongman has seized it *(a **band**, not the point `0`)* |
| **`−50` … `−100`** | **peasant rabble** in control, **burning all estates** — mob rule, the *destruction of the estate system itself* (the commoners turning on the estates); full mob at `−100` |

**The despotism band & its centralization coupling.** Despotism is not the single point `0` but a
**band from `0` down to `−50`**; below `−50` the slide continues into mob rule (`−100`). And a
republic's tradition is **coupled to its centralization** — a *strong centre protects it*, in three
bands:

- **centralization above `0`** — tradition is **floored at `0`**: a well-centralized republic
  *cannot* despotize (the stopgap);
- **`0 … −25`** — tradition **can erode** (the re-election/personalist path below): exposed, not
  doomed;
- **below `−25`** — the **despotism mechanic fires**: a strongman seizes the weak centre outright
  (the `−25` rail shared by all four registers, spine §4).

So despotization is a **weak-centre failure, not a size destiny**: a large republic is viable by
centralizing like anyone else (Venice), and the Roman arc — republic → despot → empire — is a
*tradition* collapse at a weak centre, not the price of scale. The republic spans its **full**
sub-scale in 1.0: **electoral / parliamentary** at the `+100` top, **despotism** (`0…−50`) when the
centre weakens, **mob rule** (`−50…−100`) at the bottom. Only the *post-estate* exit from mob rule
(communism) is deferred (spine §7).

## 3. The despot & his exit ladder

**The despot is a *person* — the plutocratic estate's ruler gone personalist.** A republic slides
into despotism when its **president is re-elected too many times**: term after term erodes the
republic's tradition until the office hardens into a strongman's personal seat. The **despot is the
ruler of the plutocratic estate** — the government is still that estate's (spine §2), a Burgher head
who simply no longer rotates out. But because power now rests in *him* rather than the estate, the
despot has a **defection move no ordinary ruler has**: he can **disband his own (plutocratic) estate
and side with another**, converting the **register from the top** — a **ruler-initiated register
change** (Caesar → Augustus), *distinct* from the bottom-up **estate coup** (spine §5): the strongman
is the pivot on which a republic can flip into another register **without an influence-race coup**.

**The despot-exit ladder scales with centralization — `>25 / >50 / >75`.** The more absolute the
state the despot has built, the more radical the exits available:

- above **`25`** — he can crown himself **king** (→ monarchy, siding with the nobles);
- above **`50`** — an **undead/immortal** despot can proclaim a **theocracy** outright (the
  living-god claim);
- above **`75`** — found a **cult of personality as a new religion** — the god-emperor
  ([`estate-church.md`](estate-church.md) §5, runtime religions).

A *mortal* despot can found a cult too: at his death the cult does not collapse but
**institutionalizes** — his old plutocratic estate becomes the young theocracy's **bureaucratic** or
**patrician** estate ([`estate-church.md`](estate-church.md) §1).

## 4. Power base & privileges

| | |
|---|---|
| influence base | **capital share** — firm equity + bank deposits (quantities the engine already computes: the banking layer's equity seam) |
| privilege currency | **charters, monopolies** |
| the crown's counter | **revoke charters, debase, tax** |

The Burghers are rich but landless — seizing land does not discipline them; their fight is fought in
capital. (Spine §5, the register-typed privilege table.)

## 5. The centralization rail

A republic pushed **under `−25`** sees the **despotism mechanic fire** (the three bands above) — the
plutocratic flavor of the shared rail (spine §4). Centralization above `0` is fully protective.

## 6. Disaster & rulership

The Burghers' archetype disaster is the **mercantile revolution**; winning it installs the
**plutocratic** register (a republic). The mob-rule pole (`−50…−100`) is the door out of the estate
system entirely — communism / anarchism, post-1.0 (spine §7).

## 7. Council seats

Via the cascade (spine §5), the Burghers feed the **Economy** seat (the treasurer); in a theocracy
the patricians take it.
