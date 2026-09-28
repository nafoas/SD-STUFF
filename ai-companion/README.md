# AI Companion (Fabric, Minecraft 1.20.1)

Brings your **AICord characters** into Minecraft as companions with real bodies. They talk in chat, mine, craft, smelt, build, fight, trade items and wander off to do their own thing. **Each character's personality decides what it does**, not just how it talks.

## How it works

```
Chat / game events ──► AICord character (its own personality + memory)
                          │  says something out loud       → game chat
                          │  decides what it will actually do ("INTENT: ...")
                          ▼
                       Claude (action layer)  ──► game tools: collect_blocks, dig, craft,
                          carries out THAT decision,            smelt, build, give_items,
                          in the character's style              attack, chest, follow ...
                          ▼
                       Companion body (runs every tick, no AI needed)
                          reflexes: fight / flee / eat / pick up items / follow
```

The personality affects behavior at three levels:

1. **Decisions.** Everything that happens to a companion goes to its AICord character first: being spoken to, a gift, getting hit, being bored, a task finishing. The character decides what to do, and that decision is final. It can refuse, bargain ("give me an apple first"), do a sloppy job, or ignore you and go exploring. Claude only carries out the decision, and its instructions say never to be more helpful than the character chose to be.
2. **Style.** When a character first spawns, the mod asks it to describe itself. The answer becomes a behavior profile with bravery, diligence, generosity, curiosity, loyalty, sociability, perfectionism, favorite activities, dislikes and building style and palette. Claude gets this profile, so a lazy character gathers the minimum and builds a dirt hut, and a perfectionist builds a detailed cottage from its favorite blocks.
3. **Reflexes.** The same profile drives the fast code directly:

   | Trait | Effect |
   |---|---|
   | Bravery | Retreat threshold. 7+ hunts monsters on its own, low values flee from attackers. |
   | Loyalty | 6+ follows its owner around when idle. |
   | Diligence | How fast it moves while working. |
   | Curiosity | How often and how far it wanders. |
   | Sociability | Greets players who walk up. |
   | Curiosity, diligence, sociability | Restless characters check in on their own more often. |
   | Generosity | Whether it lets strangers look in its bag. |

### Check-ins, chat and chatter

- **Check-ins.** When a big task ends, or every few minutes, the character gets a summary of what its body did and what happened: tasks, fights, items gained and lost, gifts, news. It reacts, decides what to do next, and can update its plans in its own words. Fights and dodging never wait on this.
- **Day plans.** At sunrise the character decides what kind of day it is: a work day, a day for its bigger goals, a free day (wandering, visiting, relaxing), or a mixed day. Its check-ins and the way it paces its work follow that all day. Free days are real days off, not two-minute breaks.
- **Private intentions.** Every reply has what it says out loud and what it privately intends, and the two can differ.
- **Chat awareness.** Companions see all of server chat. Messages that concern them get the character's attention: their name, their interests, places they know, coordinates, questions to "anyone", lively conversations, people joining. They chime in only when it fits their character, and sociable ones more often.
- **Requests.** Asking something becomes a promise it tracks. If it's busy, the character decides whether to drop what it's doing or say it's busy.
- **"Come look at my build".** It walks over, looks the build over (size, materials, beds, storage, windows, lights) and gives its opinion.
- **Points of interest.** Coordinates mentioned in chat ("my base is at 120 70 -40") are remembered as places it can visit.
- **Casual chatter.** Now and then it just says something: a remark about what it's doing, a thought, a question for someone. Only while players are around, and how often depends on how sociable it is.

It also remembers how it feels about each player (hit it and it holds a grudge, give it gifts and it warms up). All of this survives restarts, along with:
- **An activity log** of everything it did and experienced: tasks, fights, items gained and lost, conversations, decisions.
- **World memory:** named places (home, farm, mine, storage), what's in every chest it has used (it goes straight to the right chest), resources it has seen and where, and what it built.

## Respecting other people's builds

Companions look at the blocks around them the way a player would. There's nothing to set up and no commands:
- **Homes:** a build with a bed in it is someone's home and is never touched.
- **Other builds:** larger builds made of crafted blocks (planks, bricks, glass and so on) are left alone too.
- **Valuables:** chests, beds, doors and workstations that aren't theirs are never broken.
- **Fine to break:** natural terrain, and a few stray player blocks, like a wall someone trapped them with, a pillar, or trees grown from planted saplings. Mature crops can be harvested but are always replanted.
- **Naturally generated structures** (villages, temples, mineshafts...) are fair game unless a player has added blocks to them.
- **Chests:** they only take from their own chests, unlooted natural chests, or chests of players who told them it's okay.

## Setup

1. Install **Fabric Loader** and **Fabric API** for **Minecraft 1.20.1** on the server and on every player's client. The companion is a custom entity, so everyone who joins needs the mod.
2. Put `ai-companion-<version>.jar` in the `mods` folder of the server and of each client.
3. Start the server once. It creates `config/ai-companion.json`. Fill in:
   ```json
   {
     "aicordApiKey": "your AICord API key (AICord dashboard -> settings)",
     "claudeApiKey": "your Claude API key (console.anthropic.com)"
   }
   ```
   Then run `/companion reload` or restart. Only the server needs the keys; clients never see them.
4. In game, run `/companion characters`, then `/companion spawn <name>`.

### Talking to companions

- Mention a companion's name in chat, for example `Grug, can you get me some wood?`
- If you talked to a companion in the last minute and are standing near it, you can keep talking without repeating its name.
- Right-click a companion to open its bag, if it likes you enough. Throw items at it to give it gifts.
- Companions hear each other when one says another's name. There's a limit on back-and-forth so they don't chat forever.

### Commands

| Command | What it does |
|---|---|
| `/companion characters` | List the characters on your AICord account |
| `/companion spawn <name>` | Bring a character into the world, or summon it back to you |
| `/companion dismiss <name>` | Remove it from the world. Its memories are kept. |
| `/companion list` | Where each companion is and what it's doing |
| `/companion stop <name>` | Make it stop its current activity |
| `/companion profile <name>` | Show its behavior profile |
| `/companion log <name>` | Its recent activity log |
| `/companion memory <name>` | Places, chests and resources it remembers |
| `/companion reinterview <name>` | Rebuild the profile from a fresh self-description |
| `/companion debug <name>` | Show its decisions and actions to its owner in chat |
| `/companion reload` | Reload the config |

Profiles are saved in `config/ai-companion/profiles/<id>.json`, and you can edit them by hand. Memories are saved in `config/ai-companion/memory/<id>.json`.

## Config options

| Option | Default | Meaning |
|---|---|---|
| `claudeModel` | `claude-opus-5` | Model for the action layer |
| `claudeEffort` | `low` | `low`, `medium` or `high`. Lower is faster and cheaper. |
| `maxActionsPerDecision` | 16 | Cap on tool calls per decision |
| `checkInMinutes` | 5 | The character takes stock when a big task ends, or after this many minutes. 0 means only when a task ends. |
| `reactToResults` | true | Check in when a big task ends, not just on the timer |
| `chatterMinutes` | 6 | Rough gap between casual remarks for an average character. Sociable ones talk more, quiet ones less. 0 turns chatter off. |
| `overhearChat` | true | Let companions chime in on relevant chat they weren't addressed in |
| `conversationRadius` | 10 | Distance within which you can keep talking without the name |
| `hearingRadius` | 0 | If above 0, companions only hear chat within this many blocks |
| `managePermissionLevel` | 2 | Permission level needed for spawn, dismiss, reinterview and debug |
| `mischief` | `never` | How far a mean character may go against players. `never`: no tricks that hurt anyone. `pranks`: harmless tricks and fibs. `mean`: may lure mobs toward players, take from their chests, and sabotage small things. Homes and builds are never broken at any level. |
| `allowPvp` | false | Let companions attack players when their character decides to |
| `searchRadius` | 32 | How far they look for blocks to mine |
| `skins` | `{}` | `{"Grug": "Notch"}` uses a Minecraft username's skin, or give a direct 64x64 PNG URL |
| `slimArms` | `{}` | `{"Ada": true}` for the slim (Alex) model when using a PNG URL |

**Costs.** Each decision is one AICord call plus one or more Claude calls if it acts. Check-ins, chatter and joining in on chat each cost one AICord call. Raise `checkInMinutes` and `chatterMinutes`, or turn off `overhearChat`, to spend less.

## What companions can do

- **Gather:** mine exposed blocks with the right tool, using real break times. Ore names match deepslate variants too.
- **Dig:** level tunnels, and staircases down or up to reach ores. Stops before breaking into lava or water.
- **Craft:** full recipe chains from the game's own recipe data, for example logs → planks → sticks → pickaxe. Places a crafting table when a recipe needs one. Reports exactly what's missing.
- **Smelt:** uses a nearby furnace, or places one, and burns fuel from the bag. Uses vanilla timing.
- **Build:** blueprints made of boxes and single blocks, with block states such as stairs facing a direction. Checks materials first. Clears grass, dirt and stone in the way but nothing else. Never places a block inside a creature.
- **Everything else:** give items to players (thrown if it can't walk up to them), fight, store or take items from chests, follow, stay and guard, remember places.
- **Reflexes:** eat when hurt, run from creepers, fight or flee depending on bravery, defend players they like, wear the best armor they carry, pick up items.

## Limitations

- Everyone on the server needs the mod, because the companion is a custom entity.
- Companions walk with Minecraft's normal mob pathfinding. They can't sprint-jump, pillar up or do parkour, so tall builds must be reachable from their own floors.
- Crafting and smelting happen in the companion's bag. It stands at the table or furnace, but no GUI is shown.
- Hostile mobs don't go after companions on their own the way they target players. Companions still fight back and defend people.
- AICord's docs don't say which format the `Authorization` header takes, so the mod tries `Bearer <key>` first and then the plain key.
- The jar is about 34 MB because it bundles the official Claude Java SDK.

## Building from source

```
./gradlew build        # jar ends up in build/libs/
./gradlew runServer    # dev server in ./run
./gradlew runClient    # dev client
```
Requires JDK 17 or newer.
