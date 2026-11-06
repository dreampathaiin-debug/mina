---
name: amazon.com
display: Amazon
domains: ["amazon.com", "amazon.sg", "amazon.co.uk", "amazon.de", "amazon.co.jp"]
aliases: ["Amazon"]
apps: []
open: amazon.com
mode: desktop
description: E-commerce platform: search products, prices, stock, cart
---
## What it is

A global e-commerce platform. The country sites (.com, .sg, .co.uk, .de, .co.jp) are independent shelves; prices, stock, currency and delivery all differ.

## Pages and URLs

- Search: open `/s?k=<keyword>` on the target country's site; the keyword must be URL-encoded.
- Product detail: a clean link keyed by ASIN. Cards on the search page may be rewritten by ads into redirects.
- With no region given, the site naturally lands on a country site by the visitor's location.

## Features

- **Search**: publicly viewable. Each result has ASIN, title, price and currency, rating and review count; ad slots are marked.
- **Product detail**: the buybox holds the main price, stock and seller terms; used and third-party offers are listed separately. For products with variants (colour, size and so on), the initial price corresponds only to the default selection; picking another variant changes price and stock.
- **Add to cart**: the Add to Cart button on the detail page; on success the page shows an Added to Cart note.

## Known behaviour

- Region redirects can leave the page without a price, or show SGD, GBP and other currencies.
- The badge count and button colour lag after adding to cart, and may not update at once.
- A redesign or a human-verification popup can make product info unreadable.
