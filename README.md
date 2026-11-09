<div align="center">

<img src="docs/media/logo.png" alt="Mina" width="88" />

# Mina

**An AI assistant on your phone: say what you need, and it works your apps and the web until it's done.**

It works on a hidden background screen, so you can keep using your phone.

<a href="https://github.com/meenaispeaks6-droid/meena/releases/latest"><img src="https://img.shields.io/badge/Download%20APK-Android%2011%2B-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" /></a>

<a href="https://github.com/meenaispeaks6-droid/meena/stargazers"><img src="https://img.shields.io/github/stars/meenaispeaks6-droid/meena?style=flat&logo=github&label=Star&color=1f6feb" alt="stars" /></a>
<a href="https://github.com/meenaispeaks6-droid/meena/releases"><img src="https://img.shields.io/github/downloads/meenaispeaks6-droid/meena/total?label=downloads&color=2ea043" alt="downloads" /></a>
<a href="https://github.com/meenaispeaks6-droid/meena/releases/latest"><img src="https://img.shields.io/github/v/release/meenaispeaks6-droid/meena?include_prereleases&label=version" alt="version" /></a>

<br/>
</div>

## Get things done with one sentence

<table>
<tr>
<td align="center" width="33%">
<img src="docs/media/zepto-order.gif" width="260" alt="Mina orders milk on Zepto: it reads the screen, uses the saved address, picks cash on delivery and places the order" /><br/><b>"Order milk on Zepto with my saved address"</b><br/><sub>Adds the milk, uses the saved address, picks cash on delivery and checks out (recorded on a real phone)</sub></td>
<td align="center" width="33%">
<img src="docs/media/linkedin-jobs.gif" width="260" alt="One voice command opens LinkedIn on the background screen while Mina searches for product manager jobs" /><br/><b>"Open LinkedIn and apply for product manager jobs"</b><br/><sub>Said out loud on the home screen; Mina opens LinkedIn and starts the job search in the background</sub></td>
<td align="center" width="33%">
<img src="docs/media/movie-booking.gif" width="260" alt="Mina books movie tickets on District: it finds the cheapest show, applies a promo code, selects three Rs 900 seats and opens the payment sheet" /><br/><b>"Book the cheapest movie seats on District"</b><br/><sub>Mina opens District, finds the cheapest show, applies the promo code it found, picks three ₹900 seats and brings up the payment sheet (recorded on a real phone)</sub></td>
</tr>
</table>



## Why Mina

| | |
|---|---|
| 📱 **Leaves your screen alone** | Opens apps, taps, types and scrolls on a separate background screen while you use your phone as usual; tap the small live view whenever you want to watch |
| 🧩 **Works with any app** | Looks at the screen and finds buttons the way a person does: no app APIs, no per-app integration |
| 🌐 **Call it from any web page** | In the built-in browser, say what you want on the page you're reading and it works right there; touch the page and it stops |
| 🧠 **Gets better with use** | Remembers the preferences you tell it, and turns tasks it finishes into skills so next time is faster and steadier |
| 🔒 **Your data stays on your phone** | There is no Mina server; use your own model API key, or sign in with a ChatGPT, xAI or OpenRouter account |
| 🆓 **Open source and free** | Apache-2.0, all code public |

## Get started in three steps

1. **Install**: download the APK from [Releases](https://github.com/meenaispeaks6-droid/meena/releases/latest).
2. **Connect a model**: in Settings › Model, pick a provider and enter an API key, or just sign in with an account ([models and sign-in](docs/models.en.md)).
3. **To operate phone apps**: follow the "Set up phone control" wizard in Settings and pair wireless debugging on the phone once ([setup guide](docs/setup-guide.en.md)). Skip this if you only want chat, web and files.

Then try saying: "Set alarms for 9:40 and 10:00 tomorrow morning" or "Check the weather at 8 every morning and tell me only if it will rain".

> 💡 Have a computer handy? Send this to the AI agent on it and it will set everything up for you:<br/>
> `Follow https://github.com/meenaispeaks6-droid/meena/blob/main/docs/install-with-agent.en.md to install Mina on the Android phone connected over USB.`

## What it can do

| | |
|---|---|
| **Operate apps** | Reads long lists and chat histories, taps and types, shares text into apps; hands logins, verification codes and payments to you |
| **Browse the web** | A built-in desktop-class browser that keeps you signed in, reads pages, downloads and uploads; paces itself and hands block pages to you |
| **Your current screen** | Double-tap the floating capsule to send the screen you're looking at: "how should I reply?", "note this down" |
| **Schedules and conditions** | "Remind me at 9 tomorrow", "do this every Friday"; runs on time in the original conversation |
| **Jump in or stop anytime** | Keep talking while a task runs to add or correct things (long-press to withdraw or edit one Mina hasn't picked up yet); after a stop it reports where it got to, and "continue" picks up from there |
| **Hold to talk** | Hold the chat input to speak and edit the words before sending; long-press the floating capsule to speak and release to send. Uses the phone’s built-in speech recognition, or a provider’s speech model if it has none |
| **Files and computing** | A Linux sandbox per conversation runs Python, Node and shell on the files you send (arm64) |
| **From your computer** | AI tools on your computer can hand Mina tasks through CLI + Skill or MCP ([use Mina from a computer](docs/computer.en.md)) |

By default Mina runs in **Ask mode**: it checks with you only before spending money (placing orders, paying) and just does everything else. You can switch to **Autonomous mode** instead. See [Ask and Autonomous].

For how each of these works and the finer details, see [features in detail](docs/features.en.md).

<summary>Apps and websites with built-in usage knowledge (anything not listed still works)</summary>

The names are shown as they appear in the knowledge files.

<!-- generated:knowledge:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
| Category | Built in |
|---|---|
| **Apps** | Android Settings, Baijiahao, Bilibili, BOSS Zhipin, Chrome, Dedao, Douyin, Feishu, Gmail, Jike, Kuaishou, LinkedIn, QQ Mail, Telegram, Toutiao, Uber, Uber Eats, WeChat, Weibo, WhatsApp, Xianyu, Xiaohongshu, Zhihu |
| **Websites** | Amazon, Bilibili, Google Calendar, Douban, Douyin, Instagram, Gmail, Google Maps, Telegram, Baidu Tieba, Weibo, Sogou WeChat, WhatsApp, Xiaohongshu, Xiaoyuzhou, YouTube, Zhihu, BOSS Zhipin |
| **How-to skills** | Change Mina's own settings, Turn content into text, Work with PDF documents, Look something up, Messages & email, Write skills |
<!-- generated:knowledge:end -->


<summary>Supported model providers</summary>

<!-- generated:providers:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
Presets for DeepSeek, Qwen, Kimi, Zhipu, SiliconFlow, Volcengine Ark, MiniMax, StepFun, Tencent Hunyuan, Baidu Qianfan, OpenRouter, OpenCode Go, Anthropic, Gemini, OpenAI, xAI, Mistral, Groq, Ollama, LM Studio and more; for any other provider pick Custom (Custom), which takes any OpenAI-compatible, Anthropic, Gemini or OpenAI Responses endpoint.
<!-- generated:providers:end -->

You can also skip the key and sign in with a ChatGPT (Plus / Pro), xAI / Grok or OpenRouter account. Steps for adding a model, and what to know about each sign-in, are in [models and sign-in](docs/models.en.md).


## Which phones

| | Requirement |
|---|---|
| Chat, web, files | Android 11 or later |
| Operating phone apps | Android 12 or later; **Android 14+ recommended** (the background screen has its own focus, so it doesn't get in the way of your typing on the main screen) |
| Linux sandbox | arm64 devices |

Not sure your phone is up to it? Run Settings › Recovery & repair › **Compatibility self-check**: 12 checks in about a minute, and you can [send us the results](https://github.com/meenaispeaks6-droid/meena/issues/new?template=device-report.yml) in one tap.

## FAQ


**Wireless debugging turned itself off?** Android turns it off when you change Wi-Fi or restart. With your permission, Mina turns it back on by itself; if it still can't connect, go back to "Set up phone control" and pair again.

**Can't find wireless debugging on my phone?** Some phone systems leave it out. You can start the phone helper from a computer over USB instead; 

**Some screens can't be read or captured?** Pages like payments and password entry block screenshots, so Mina asks you to take over there.

**Can I use a model running on my own computer?** Yes: pick "Custom" as the provider and enter its address on your local network.

**Is the app in English?** Yes. The app UI comes in English, Simplified Chinese, Traditional Chinese, Japanese, Korean, Spanish, Portuguese, Russian and Indonesian, follows your system language, and can be switched in Settings › Language. The built-in app and website knowledge is written in Chinese, but that doesn't limit you: the model reads it fine, and you can talk to Mina in English.

**Where do updates come from?** Only from this repository's [Releases](https://github.com/meenaispeaks6-droid/meena/releases); you can also upgrade in the app from About › Check for updates.



