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

### Features to bring over from MTG Trader (items 3 to 14)
*Added 2026-10-25.* Owner's wording: "can you list me potential functionalities that could be ported from the mtg app
to the pokemon app? … I am not interested in decks or life tracker for the pokemon app." From the list offered, the
owner picked: sort by value change (item 1 above, picked again), select cards, swipe cards, arrange binders, paid and
worth, graded cards, wishlist sections, price alerts, add to the wishlist offline, Nextcloud sync and backups. They
also asked for MTG's currency button in price fields (item 13) and "the shown order of the cards scan" (item 14).
Left out on purpose: CardTrader prices, order imports and package checks, order history, the trade binder helper,
Archidekt, Card Kingdom and precons.

### 3. Select several cards
*From MTG Trader 1.38.* Press and hold a card (any view, or ⋮ → Select cards), then tap more; "Select all shown". A bar
on top shows the count and value, with Move to binder, Add to a trade ("I give" side), Delete (asks first) and, under
⋮, Edit condition, language, price… (only what's changed is changed; stacks that end up the same are combined), Share
as a list, Copy as a list, Export as CSV. Every action can be undone.

### 4. Swipe cards in the collection
*From MTG Trader 1.38.* List and compact views: swipe right = Move to binder…, swipe left = Add to a trade… (for a
stack, asks how many). Settings sets each direction: move to binder, add to a trade, one copy more, one copy less,
add to the wishlist, or nothing (Poké has no trade binder). Every swipe can be undone.

### 5. Arrange binders
*From MTG Trader 1.39.* ⋮ → Arrange binders… moves Unsorted, the Wishlist and each binder up or down; the binder bar
and every binder list follow that order. All stays first, new binders go at the end.

### 6. Paid and worth
*From MTG Trader 1.33.* On the value screen: what you paid for the cards with a purchase price against what they're
worth now, for all cards or the chosen binder, with the best and worst buys per card.

### 7. Graded cards (slabs)
*The Pokémon version of MTG Trader 1.27's signed and altered cards.* Mark a card as graded, as a stack of its own with
a tag in the list, and a filter for it.

Open questions:
- Which graders and grades (PSA 1–10, BGS with halves, CGC…)? Just "graded" plus a free text?
- What's a slab worth? Cardmarket's price guide has no graded prices, so the card's normal price, the purchase price,
  or a value you type?
- This needs a new database column: older versions on another phone can't read it, so ask before building it
  (CLAUDE.md, "still ask first").

### 8. Wishlist sections: To buy, Ordered, Received
*From MTG Trader 1.35 to 1.39.* Swipe right moves a card one step on: To buy → Ordered → Received → the collection
(a window asks version, condition, language, copies, binder and what you paid). Swipe left deletes. "Ordered" tick
box on each card; ordered cards are left out of the shared list. Received cards stay as a record with the date until
cleared (by hand or after 1–12 months). ⋮: Mark all ordered as received, Mark the cards I now own as received, Clear
received cards. Select several wishlist cards, like item 3.

Open question:
- Poké's wishlist already has "Remove the cards I got since adding them": replace it with "Mark the cards I now own as
  received", as MTG did?

### 9. Price alerts for wishlist cards
*From MTG Trader 1.37 (without CardTrader).* "Notify me at or below" on a wishlist card; one notification per drop,
checked after each price update. Settings: which Cardmarket price counts (lowest listing, trend, 7-day or 1-day
average), and the price source chosen in Settings. "Any version" cards are compared at their cheapest version. The
level keeps the currency it was typed in (300 kr. stays 300 kr.). Ordered cards stay quiet.

### 10. Add to the wishlist offline
*From MTG Trader 1.34.* Without internet, "Add card" on the wishlist suggests names from Cardmarket's card list saved
on the phone (`products_singles_6.json`, already downloaded weekly); the card goes on as "any version", "Details follow
when you're online", and is filled in later.

Open question:
- Cardmarket's list only has English names: fine, or should Japanese cards wait until online?

### 11. Sync between phones with Nextcloud
*From MTG Trader 1.12 and later.* Same design as MTG: collection, binders, wishlist, trades, scans and preferences in
`sync.json.gz` in a Nextcloud folder of your own; log in through the browser (app password); merge item by item; sync
automatically, optionally only on Wi-Fi.

Open questions:
- Which phones: yours and your son's?
- The folder: a separate "Poké Trader" folder on the same Nextcloud?
- Health check: Nextcloud is the owner's own server, so nothing new to check (as for MTG).

### 12. Backups and restore points
*From MTG Trader.* Every day if something changed, and before big changes (first sync, CSV import, restoring); "Back up
now" keeps one until deleted. Stored in a Backups folder next to the sync file (with Nextcloud), otherwise on the
phone or in a folder you choose; Save to / Restore from a file. Keeps the latest 10 plus one per week for 8 weeks.
Restoring shows what differs and backs up the current state first.

### 13. Type a price in the currency you paid in
*From MTG Trader 1.40 and 1.41.* A currency button ("€ ▾", "kr. ▾", "$ ▾") in every price field: the purchase price
(card page and card window), the agreed price in a trade, Edit… for selected cards (item 3), Add to collection from
the wishlist (item 8) and the alert level (item 9). A price typed in another currency than the one shown gets a line
"≈ 74,61 kr." under it. Switching the button with an amount in the field converts it (300 kr. → 40,21 €, and back to
exactly 300,00 kr.); with an empty field it only changes the currency. A new field starts on the currency picked last.

Decided with the owner (2026-10-25):
- **Purchase prices keep the currency they were typed in** (300 kr. stays 300 kr. whatever the rate does). This goes
  further than MTG, which converts purchase and agreed prices to euros at the day's rate and keeps only alert levels
  in their own currency.

Still to settle when planning:
- The same for agreed prices in a trade?
- Totals that mix currencies (paid and worth, stats) convert each price at today's rate; CSV backups get the amount
  and its currency in a new column (and older files, without that column, are read as euros).
- A new database column: older versions on another phone can't read it, so ask before building it (CLAUDE.md,
  "still ask first").

### 14. The scanner list jumps to the newest card
*From MTG Trader 1.52.* Owner's wording: "the shown order of the cards scan". Poké's list under the camera already
puts the newest card on top, but it doesn't scroll: once you've scrolled down, a new card is added above what you
see. As in MTG, the list should jump to the top each time a card is added, so the last card scanned is always in sight.

Confirmed by the owner (2026-10-25): the list under the camera, not the Scan tab's waiting list.

## Done

*(nothing yet)*
