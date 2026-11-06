---
name: douyin.com
display: Douyin
description: Douyin web live rooms: products, prices, gift bags
domains: ["douyin.com", "jinritemai.com"]
aliases: ["douyin", "Douyin Shop", "douyinshop"]
apps: ["douyin"]
open: douyin.com
mode: desktop
---
## What it is
A short-video platform that also runs a shop and live commerce. Short videos, the shop and live streams are three separate functions. On the phone it is the Douyin app; web live rooms live at `live.douyin.com`, and e-commerce products and shops at `jinritemai.com`.

## Pages and URLs
Web live rooms live under `live.douyin.com`.

## Features
- **Live-room products**: product data comes back through APIs; the on-screen explanation card does not always show.
  - A `promotions/pop` response appears when the showcased product changes; the full product table is in the `promotions/page` response, refreshed by tapping "All products" on the live-room page. Tapping a single product card leaves the live room.
  - Each product has two ids: `promotion_id` numbers one listing run within this session, `product_id` is the product itself. The same product may be listed and delisted repeatedly.
  - The price field `min_price` is in cents; a value of -1, 0 or a missing field means no price was given, not 0. The same product's price may differ between the pop and page responses.
  - Product links follow the redirect address given in the response.
- **Live-room gift bags**: need a sign-in.
  - The gift-bag widget sits at the top left of the player, showing only a countdown with no "gift bag" label; the countdown repaints constantly and the widget moves, so locate it by page structure.
  - The gift-bag detail API is requested only once on entering the room; a page refresh does not resend it. A bag's appearance and draw are pushed to the room.
  - Whether the join conditions are met: read the user's own status in the response; the `status` field carried by a condition item does not mean done or not. The passphrase condition's passphrase is in the response; the original wording decides. Some conditions cost diamonds.
  - The draw result shows on the page only at the moment of the draw; it cannot be looked up afterwards.

## Known behaviour
- An empty API response may be risk control, or simply not captured yet; it does not mean the room has no products.
- The product index shown on the picture must not be used alone to identify a product; with a blurry or covered picture, indexes easily mismatch.
