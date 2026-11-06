---
name: zhipin.com
display: BOSS Zhipin
domains: ["zhipin.com"]
aliases: ["BOSS Zhipin", "Boss Zhipin", "zhipin"]
apps: ["boss-zhipin"]
open: www.zhipin.com
mode: desktop
pace: careful
description: Jobs site: search by city and keyword, job details
---
## What it is
The web version of BOSS Zhipin; job seekers search jobs and read details. Phone app: `boss-zhipin`.

## Pages and URLs
- Home: `https://www.zhipin.com/` redirects to a city page like `/shenzhen/?seoRefer=index`; "<city> [switch]" at the top changes it.
- Search: `https://www.zhipin.com/web/geek/jobs?query=<encoded keyword>&city=<city code>` opens directly (`/web/geek/job?` redirects here). The title reads like "Shanghai hiring - ...".
- City codes are 9 digits (nationwide 100010000; all in `references/cities.md`); filter codes like `experience=105`, `salary=406` are in `references/filters.md`.
- Recommended: signed in, `/web/geek/jobs` without a query lists jobs matching the job preferences; - Detail: `/job_detail/<id>.html`, the id about twenty alphanumerics.

## Features
- **List**: sign-in needed for the full list. Each job: name, salary, location, experience, education, company (funding, size, industry), tags, benefits, recruiter. The page's own `POST /wapi/zpgeek/search/joblist.json` returns 15 per call, twice on open; `zpData` holds `resCount`, `hasMore`, `lid`, `jobList` (fields in `references/joblist-fields.md`, salary plain in `salaryDesc`); readable with web_capture.
- **Detail**: tapping a card shows it on the right of the same page, URL unchanged; the detail URL shows the whole page. It holds name, status, salary, location, education, company, recruiter, benefits, tags, description, registration info, work address; the body salary is plain text.
- **Chat now**: contacts the job's recruiter. The card has no such button (only the job and company names are links); it sits under the job title in the right detail, and at the top by "Interested" on the detail page.
- **Sign in**: `https://www.zhipin.com/web/user/`, by phone + SMS code, WeChat, or app QR scan.

## Known behaviour
- Salary digits render in the site's own font, so the read text loses them; `joblist.json`'s `salaryDesc` is plain text.
- Signed out: "Sign in / Register" on top, about four items, then "Sign in to see more good jobs".
- First search-page open may carry `_security_check=1_<timestamp>` in the URL, dropped once loaded.
- Headhunter jobs are tagged "headhunter", the company shown anonymized.
- The recommendation list mixes in barely related jobs; salary filters count overlaps, so with 20-50K picked, 11-22K jobs also show.
- When the day's chat quota ends, "Chat now" pops "You have reached the chat limit" ("...150 recruiters today; come back tomorrow", one "OK").
