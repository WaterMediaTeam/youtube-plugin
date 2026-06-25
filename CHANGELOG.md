# 📦 RELEASE 3.0.0-beta.6
- 🐛 Fixed: YouTube (and other) links failing to resolve — replaced NewPipeExtractor with yt-dlp, which keeps up with YouTube's player signature/throttling changes. The standalone yt-dlp binary is fetched on demand at runtime (no Python required)
- ✨ Added Facebook, Instagram and Newgrounds support, alongside YouTube and SoundCloud
- ✨ Added a BotGuard po_token retry (rustypipe-botguard) for YouTube "confirm you're not a bot" blocks
- ⚙️ Changed: now requires WaterMedia 3.0.0-beta.19+, which fixes the slow load on split video+audio sources
- ⚙️ Changed: CI now auto-updates yt-dlp weekly and publishes to CurseForge & Modrinth
- 📄 Added bundled license notices for the third-party tools (yt-dlp — Unlicense, XZ for Java — 0BSD)

# 📦 RELEASE 3.0.0-beta.5
- ⚙️ Changed: Updated NewPipeExtractor to 0.26.2
- ✨ Updated to WaterMedia Beta 17
- ✨ Added soundcloud support in this addon

# 📦 RELEASE 3.0.0-beta.4
- ⚙️ Changed: added delay on registering (prevents register BEFORE watermedia starts)

# 📦 RELEASE 3.0.0-beta.3
- 🐛 Fixed: Silent crash on (Neo)Forge due to bad jar building

# 📦 RELEASE 3.0.0-beta.2
- 🐛 Fixed: Crash with WaterMedia v3 (despite begin an update for watermedia v3)

# 📦 RELEASE 3.0.0-beta.1
- ✨ Initial public beta release of WaterMedia Youtube Plugin for the 3.0.0 series
