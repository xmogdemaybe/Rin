<div align="center">
  <img src="assets/rin-icon.jpg" width="220" alt="Rin">
  <h1>Rin (りん)</h1>
</div>

---

<p align="center">
  A Telegram companion that lives next to you and keeps passing as a human.
</p>

<p align="center">
  A fork of a fork, with the extras I decided were worth having — and, honestly,
  with a plain wish to spend the time on it.
</p>

<p align="center">
  Русская версия: <a href="README_RU.md">README_RU.md</a>
</p>

---

> [!WARNING]
> This project runs as a **userbot** — on a real account, not the Bot API.
> Telegram's ToS says no. Everything you do with this project from here on is
> at your own risk. The author takes no responsibility.

---

## Install

You need:

- **Java 25**;
- **api_id / api_hash** from [my.telegram.org](https://my.telegram.org) — this is
  a regular Telegram account, not a bot;
- access to an OpenAI-shaped model (any proxy in front of one works).

Nothing else to install: audio, video and voice are handled in-house, the bot
never shells out to anything.

```bash
git clone https://github.com/xmogdemaybe/rinprivate rin && cd rin

cp config.properties.example config.properties   # fill in the first seven lines
cp -r prompts-example prompts                    # her personality, edit freely

./gradlew run
```

The first run asks Telegram for a confirmation code — you type it into the
terminal. After that the session lives in `session/` and you won't be asked again.

**Right after install she runs with `read_only=true`**: she thinks, but sends
nothing and doesn't even mark messages as read. Read the log, make sure it all
looks sane, and only then turn it off.

### Worth tweaking

```properties
lockdown=owner_only        # who she answers at all: owner_only, contacts, none
read_only=true             # drop this once you trust how she behaves
night_sleep=true           # does she sleep at night
sleep_consolidation=false  # revisit memory while asleep (it rewrites the diary!)
character_growth_enabled=false  # personality growth: notes by day, kept by night
```

> Personality growth is tied to night sleep. With `night_sleep=false` the night
> pass never runs at all: notes about herself keep piling up in the diary and
> `prompts/character_growth.md` stays as it is until you bring the nights back.

The config is re-read while running. Who she talks to, her habits, the
anti-repeat guard, diary blending, LLM request logging, limits, consolidation,
personality growth and the trust counter all change without a restart. Model,
API key, sleep schedule and the rest do need one — she lists them in the log.
Missing settings get appended to the file on their own, each with a note, so you
don't have to read the source to find out about a new option.

### Handy

```bash
./gradlew run --args="recall train trip to the sea"   # what she'd remember
./gradlew run --args="peek"                           # chats through her eyes, hands off
./gradlew test                                        # checks
./gradlew run -Praine.log=DEBUG                       # details: filtering, memory sizes
```

Every request to the model and its answer land in `logs/llm/` as a pair of
files. When she answers oddly, that's the place to look first.

### Not in this repo

Private things stay on the machine: `config.properties` (keys and phone number),
`prompts/` (the living personality — samples are in `prompts-example/`), `data/`
(diary and mid-term memory), `session/` (the Telegram session), `cache/`, `logs/`.

---

## How it works

The mechanics live in [`docs/`](docs). If you want to know how it all works
under the hood, go there — or ask the author of the fork this one started from:
[fenixvd/Raine](https://github.com/fenixvd/Raine). This is a fork of a fork, so
not much has changed under the hood, apart from a few tricks that are written up
there as well.

What this fork actually adds on top — its own tricks and fixes — is collected in
[docs/fork-features.md](docs/fork-features.md).

Docs are in Russian for now.

---

## Credits

<div align="center">
  <img src="assets/credits.jpg" width="640" alt="Special thanks">
</div>

Where the code comes from, where the ideas come from, and what Rin and I thank
you for:

- [fenixvd/Raine](https://github.com/fenixvd/Raine) — the Java codebase this
  fork starts from;
- [Alex2772/kuni](https://github.com/Alex2772/kuni) — the original: the idea, the
  memory design, and talking to the model through a "phone" instead of an API.

Ideas rather than lines of code:

- [Dem0riaN/kuni](https://github.com/Dem0riaN/kuni) — the knowledge that MTProto
  proxy support can be switched on through the library itself;
- moeru-ai/airi, plast-mem and the other companion-and-memory projects — ideas
  and docs.

> also. i guess, i need to say some thankful words to ai models that i use - GLM-5.3-flash and qwen3.8-max/flash, bcz honestly, i am sensitive person, and I really want to express my gratitude - especially since I’m not a coder myself. 

---

## License

This repository is MIT, exactly like the project this fork was cut from:
[fenixvd/Raine](https://github.com/fenixvd/Raine) (see [LICENSE](LICENSE)).

The source project, [Alex2772/kuni](https://github.com/Alex2772/kuni), ships no
license, so in theory you'd ask first. I doubt that staying open source, not
claiming the original code as yours and naming the source in the credits is the
kind of thing that needs permission. I'm fearless — I'm playing with trinkets.
I wouldn't advise you to be.
