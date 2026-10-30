# To-do list for Poké Trader

Ideas the owner (joelvdc) is collecting. **Nothing here is built until the owner gives the green light**; then
everything on the list is planned together and carried out (see `CLAUDE.md`).

How to use this file:
- Add new ideas under "Collecting", newest last, with the date and the owner's own wording.
- "Open questions" under an item are things to settle with the owner before building it.
- When an item is released, move it to "Done" with the version it shipped in.

## Collecting

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

### 7. Graded cards (slabs)
*Added 2026-10-25. The Pokémon version of MTG Trader 1.27's signed and altered cards.* **Waiting:** the owner on
2026-10-25: "we have no graded cards, so do not go forward with this change now". Mark a card as graded, as a stack of its own with
a tag in the list, and a filter for it.

Open questions:
- Which graders and grades (PSA 1–10, BGS with halves, CGC…)? Just "graded" plus a free text?
- What's a slab worth? Cardmarket's price guide has no graded prices, so the card's normal price, the purchase price,
  or a value you type?
- This needs a new database column: older versions on another phone can't read it, so ask before building it
  (CLAUDE.md, "still ask first").

## Done

### TCGplayer price names (Poké Trader 1.21, released 2026-10-30)
*From the weekly health check of 2026-10-30*: TCGdex renamed two TCGplayer price kinds ("1st-edition-holofoil",
"unlimited-holofoil"), so old 1st edition and unlimited holos had no TCGplayer price. 1.21 reads the new and old names.
The same day TCGdex sent no TCGplayer prices at all; the health check now warns when that happens.

### Features brought over from MTG Trader (Poké Trader 1.20, released 2026-10-26)
*Asked for on 2026-10-25* ("can you list me potential functionalities that could be ported from the mtg app to the
pokemon app? … I am not interested in decks or life tracker"); green light for the list and the plan the same day.
- Item 1: sort by value change ("Change in %" / "Change in money", per card; Rising/Falling lately per card too, and
  a card in several binders shows once there).
- Item 3: select several cards (move, add to a trade, delete, edit condition/language/price, share, copy, export).
- Item 4: swipe cards in the list and compact views (directions chosen in Settings).
- Item 5: arrange binders (the bar and every binder list follow the order; synced).
- Item 6: Paid and worth on the value screen, with best and worst buys.
- Item 8: wishlist sections To buy / Ordered / Received, swipes, "Mark the cards I now own as received" (replaces
  "Remove the cards I got since adding them"), clearing received cards after 1–12 months.
- Item 9: price alerts on wishlist cards (Cardmarket's lowest listing by default; notifications).
- Item 10: add to the wishlist offline from Cardmarket's product list (English names; the product id is kept, so the
  exact card is found once online).
- Item 11: Nextcloud sync between the owner's two phones, with the folder picker (owner, 2026-10-25: "the user should
  be able to choose the folder, just like the mtg app").
- Item 12: backups and restore points.
- Item 13: prices kept in the currency they were typed in (purchase prices, agreed prices, alert levels); joining
  stacks in different currencies asks which one to keep; CSV has a "Purchase currency" column.
- Item 14: the scanner list jumps to the newest card (owner, 2026-10-25: the list under the camera).
- Database version 6 (approved by the owner): sync ids, price currencies, the wishlist's ordered/received/alert columns.
