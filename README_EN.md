# MC Conversation Framework (MCCF)

简体中文（[中文 README](README.md)） | **English (current)**

---

## What is this? What's in it for me?

Ever been on an international Minecraft server where someone typed a wall of
text in a language you don't read, or you wanted to trade with a nearby
player but couldn't understand a word they said?

**MCCF fixes that.** Install it, and players on the same server can each
chat in their own language — Chinese, English, Japanese, Korean, German,
French, Spanish, Russian, and more — while everyone else automatically sees
a translated version. No more alt-tabbing to a translator and copy-pasting
back and forth; chat just works across languages.

On top of translation, it also makes multiplayer chat feel more like actual
in-person talking:

- **Translations only reach people who could actually hear you.** It's not
  a server-wide broadcast — MCCF checks real in-game distance and line-of-
  sight to decide who "hears" a message. If someone can see you (close
  enough, line of sight clear), the translated text shows up in the chat
  hud; if they're out of sight but still in range, it shows above their
  hotbar as a HUD subtitle instead. No more "the whole server saw me
  muttering in a corner."
- **You pick the translation service, you bring your own API key.** Works
  with eight real providers: OpenAI, Claude, Gemini, DeepL, Kimi, DeepSeek,
  Zhipu AI, and Ollama for locally hosted models. Mock is available for debugging.
  Server admins manage all of this from an in-game
  settings screen — no config file editing, no server restarts.
- **Don't want the server dictating your translation setup?** You can run
  MCCF client-side only, with your own translation service, even if the
  server you're on hasn't installed the mod at all (see "Client-Only Mode"
  below).
- **Missed something? Check the chat history.** Subtitles fade away, but
  there's a built-in Chat History screen that lets you scroll back through
  the latest 500 saved chat messages, grouped by conversation.

## How do I use it?

1. **Download** the latest `.jar` from
   [Modrinth](https://modrinth.com/mod/mc-conversation-framework) or
   [GitHub Releases](../../releases). (Both channels provide compiled jars;
   Modrinth is the primary channel. GitHub Releases also provides a
   sources jar for developers.)
2. **Install** by dropping the `.jar` into your Minecraft `mods` folder.
   - If you're a **server owner**: install it on both the server and your
     own client to get the full "spatial translation" experience (who can
     hear whom, near/far dispatch to chat hud vs. hotbar subtitle, etc.).
   - If you're just a **player** on a server that doesn't have the mod
     installed: install it on your client anyway — it will automatically
     fall back to **Client-Only Mode**, translating chat locally just for
     you. See below for details.
3. **Also install [Fabric API](https://modrinth.com/mod/fabric-api)**
   (required) and [Fabric Loader](https://fabricmc.net/use/).
   [ModMenu](https://modrinth.com/mod/modmenu) is optional but gives you a
   graphical settings entry point.
4. **Configure a translation service in-game.** Open the pause menu (`Esc`)
   and look for the MCCF settings entry (or open it via ModMenu), pick a
   translation provider (e.g. DeepL or OpenAI), and enter your own API key.
   That's it — translation starts working immediately, no restart needed.

## A few things worth knowing before you start

- **Both sides need the mod for the full experience.** If only the server
  has MCCF installed but your client doesn't, you receive original text
  within the server's permitted hearing range, without translation. If only your client has it and the
  server doesn't, you get Client-Only Mode (translation, but no distance/
  line-of-sight logic — see below).
- **Client-Only Mode is a real, fully independent path**, not a degraded
  afterthought. If the server you're on doesn't have MCCF, your client
  detects that automatically and switches to translating chat locally with
  whatever provider and API key *you* configured — completely separate from
  any server-side setup. You can also force this mode manually even when
  the server *does* have MCCF, if you'd rather manage your own translation
  settings independently. On an MCCF server this changes where translation
  runs; distance and occlusion rules still apply, and it does not enable
  server-wide broadcasts.
- **You need your own API key** for most translation providers (OpenAI,
  Claude, Gemini, DeepL, Kimi, DeepSeek, Zhipu AI). The only exception is Ollama,
  which runs a model locally on your own machine for free, with no key
  required — but you'll need to set that up yourself first.
- **Only server operators (op) can change the server-wide translation
  settings** (which provider, which API key, etc.). Regular players can
  view what's currently active but can't edit it — this prevents any player
  from silently changing everyone's translation provider. Your personal
  Client-Only Mode settings, on the other hand, are yours alone to edit,
  regardless of your op status.
- **Conversation context follows what both participants actually received.**
  Joining a group does not reveal earlier messages you missed. Moving away or
  changing dimensions updates group membership; context and participant lists
  are filtered by hearing relationships.
- **Local translation is rate-limited** to five requests per second. Messages
  already in your language may still use API quota; your own messages are skipped.
- **This is Minecraft 1.21.1 and requires Java 21.**

## Version 1.2.0 — 2026-10-25

This update fixes spatial group and context isolation, asynchronous message
ordering and stale callbacks, configuration drafts, context-aware cache
invalidation, malformed endpoint fallback, and DeepL/Kimi API compatibility.
It also moves contributor and agent rules to [Agent.md](Agent.md), discoverable
through [AGENTS.md](AGENTS.md).

After a successful build, use `build/libs/MCConversationFramework-1.2.0.jar`
in a separate Minecraft 1.21.1 test instance. The sources jar is for development,
not installation. Automated regression tests use stub providers or a local HTTP
server and do not require paid API keys. **This update has not been tested in a
running game or against real paid providers.** Build and test results must be
reported from the commands actually run.

Kimi currently defaults to `kimi-k2.6`, replacing the discontinued `kimi-k2.5`;
you can select another available model. The provider uses the model's fixed
sampling defaults, and thinking controls depend on the selected model.

## Want the full technical details?

This page is intentionally kept short and player-focused. For the complete
technical documentation — architecture, build instructions, configuration
file reference, translation provider internals, how to add your own
provider, full changelog, and more — see the
**[Chinese README](README.md)**, which is the primary, most up-to-date
technical reference for this project. (Machine translation tools handle
Chinese-to-English reasonably well if needed; a full line-by-line English
translation of the technical sections is not currently maintained, to avoid
the two documents drifting out of sync.)

If you run into a bug or have a feature request, please open an issue on
[GitHub Issues](../../issues).

Validation for 1.2.0: Java 21 + Gradle 8.8 `build` passed, with all 61 automated tests passing. In-game and live paid-provider verification remain pending.
