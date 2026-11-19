# ✦ Zyrox Launcher · Starlight Hub

Setup blueprint for guild `1573020729371398144` (not a record of live changes). Use obsidian, firefly gold and ice blue to match the launcher. Keep channel names plain for mobile search.

## Channel map (top to bottom)

|Category|Channels|Access / purpose|
|---|---|---|
|✦ Start here|`#welcome-and-rules`, `#get-zyrox`|Public read, maintainer write. Pin the welcome below and official download page.|
|☄ Release desk|`#updates` (announcement), `#whats-new` (text)|Public read, maintainer write. Updates = version, official link and known issues; What's New = one short walkthrough per feature, without another ping.|
|✧ Community|`#general`, `#showcase`, `Lounge` (voice)|Members can chat in Hindi or English, share worlds and HUD layouts, or join voice.|
|⚙ Workshop|`#launcher-help`, `#ideas` (forums)|One question or proposal per post. Help tags: Install, Java, Mods, Renderer, Controls, Solved. Ideas tags: UI, Performance, Accessibility.|
|🔒 Staff|`#staff-room`, `#mod-log`|Deny View Channel to @everyone on category **before** adding synced children. Allow moderators and maintainers only.|

## Roles (highest first)

- **Maintainer** · firefly gold: hand assigned; publish in Release desk and Start here. No Administrator required.
- **Moderator** · lavender: manage messages/threads and moderate members; no role management or release publishing.
- **Helper** · ice blue: trusted support badge; ordinary posting permissions, no private staff access.
- **Release Ping**, **Beta Ping**: optional notification labels only. Don't make them publicly mentionable. Ask “Kaunsi updates chahiye?” with Stable, Beta or Neither. Only allow trusted publishers to send notification mentions in the release channel.
- **@everyone**: read public info and participate in community/workshop; no mass mentions, invites management, or channel management. Deny thread creation and replies in read-only channels too.

## Starter posts

**Welcome:** “Zyrox mein welcome! Android par Minecraft: Java Edition chalana hai? Pehle #get-zyrox dekho. Game launch nahi hua? #launcher-help mein ek post banao. Respect rakho; spam, APK mirrors aur unsolicited support DMs allowed nahi hain. Staff kabhi password ya sign-in code nahi maangega. Not affiliated with Mojang or Microsoft.”

**Download pin:** Use https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest and compare the APK SHA-256 with the release notes before installing. Don't install APKs from DMs.

**Update template:** `Zyrox [version] · [Stable/Beta] | Biggest change: […] | Known issues: […] | Official release: [GitHub release URL]`. Only announce published builds.

**What's New example:** “Renderer badalne ke baad screen black hai? Graphics Center ka last-known-good renderer recovery option dekho. Agar problem repeat ho, #launcher-help mein device aur renderer naam ke saath post karo.”

**Help forum prompt:** “Phone/GPU? Android + Zyrox version? Minecraft version, loader, Java and renderer? Expected vs actual result? Short steps? Redacted log excerpt only; remove account details, tokens and private server addresses.”

## Rollout

Enable Community for announcement/forum types. Inventory existing channels, reuse matches, and leave unrelated channels untouched. Preview with View Server As Role: a newcomer must not see Staff or post in Release desk; Helper must be able to reply in help; Maintainer must be able to publish. Test opt-in pings before inviting people. Use a pinned opt-in guide if Community Onboarding isn't available.

Design reference: [Discord Game Developer Playbook](https://canary.discord.com/blog/the-game-developer-playbook-part-one-getting-started-on-discord) — focused channels, followable release announcements and threaded feedback.
