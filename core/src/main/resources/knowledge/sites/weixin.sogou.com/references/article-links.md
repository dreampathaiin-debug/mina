# Card and link structure

Article cards sit in the page's `.news-list li` (fall back to `ul li` when that is missing); each card holds an account name and one redirect href.

Within one batch of results, when at least two non-empty, distinct links exist, the links can distinguish the cards; when duplicates appear, or the whole batch has a single one, links cannot tell the cards apart, and a result must then be located by the search page's address, the keyword and the card's text.

The redirect href is encrypted, short-lived, and one address may be reused by several duplicates. The account name is a self-chosen name, not a verified legal entity.
