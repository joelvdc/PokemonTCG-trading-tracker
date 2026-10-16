# To-do list for Poké Trader

Ideas the owner (joelvdc) is collecting. **Nothing here is built until the owner gives the green light**; then
everything on the list is planned together and carried out (see `CLAUDE.md`).

How to use this file:
- Add new ideas under "Collecting", newest last, with the date and the owner's own wording.
- "Open questions" under an item are things to settle with the owner before building it.
- When an item is released, move it to "Done" with the version it shipped in.

## Collecting

### 1. Sort the collection by value change
*Added 2026-10-12. Also on the MTG Trader to-do list.*

Owner's wording: "being able to sort by value change in the collection view (% or just raw value). Similar to your
rising lately or falling lately in the collection value overview."

What exists today: the value screen's "Rising lately" / "Falling lately" lists compare Cardmarket's trend price with
its 30-day average (`ValueHistory.biggestMoves`), and rank by the change in money over all copies owned. The collection
already shows that change per card as a percentage arrow. The collection's sort dialog (`SortField` in
`data/CollectionQuery.kt`) has no such order yet.

Decided with the owner (2026-10-12):
- **Two new sort orders:** "Change in %" and "Change in money", each with the biggest risers or the biggest fallers
  first.
- **The change is per card**, not over all copies owned.
- **The value screen changes too:** "Rising lately" and "Falling lately" should show and rank the change per card
  (today they use the change over all copies), so both screens agree.
- **Cards without a 30-day average** have no change: always last, in both directions.
- **While TCGplayer or Card Kingdom is the price source:** still sort by Cardmarket's change, and say so in the sort
  dialog.

### 2. Pictures for Japanese Mega-era cards
*Added 2026-10-16, from the health check of that day.*

Owner's wording: "Add the Pokémon app task to its todo.md" (the health check's note below).

What the health check found: TCGdex lists the Japanese Mega-era sets (M-P, M1L to M6, MC) without any card pictures,
and the English 30th Classic Collection (30th-c) has none either. Limitless TCG does have the Japanese pictures (checked
for M6/1). The app's backup picture source, pokemontcg.io / Scrydex (`FallbackImages.base()` in
`data/FallbackImages.kt`), covers English cards only, so a Japanese card that TCGdex lists without a picture gets none.
The Limitless fallback (`data/LimitlessCards.kt`) only steps in for cards TCGdex doesn't list at all.

Idea: when TCGdex has a Japanese card but no picture for it, use Limitless TCG's picture (its image server's
`tpc/<set>/<set>_<n>_R_JP` address).

Open questions:
- Do you own Japanese Mega-era cards, and do they show without a picture today?
- Does 30th Classic Collection show its pictures? (The health check says they load from Scrydex, so probably yes.)

## Done

*(nothing yet)*
