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
- **Goals.** The character's own words ("mine this mountain", "someday a castle") become a goal tree of long, medium and short goals. When the body is free it works through them without asking the character each time, and every step is logged for the next check-in. It isn't a checklist:
  - Promises come first.
  - Goals are then scored by importance, today's day plan, momentum, and how long they've been neglected, so long-term goals still get their turn.
  - Free time competes with all of it, so it doesn't grind non-stop.
  - A blocker becomes a sub-goal that pauses its parent. If the pickaxe breaks mid-mountain, "get a new pickaxe" comes first, then it goes back to the mountain.
  - Goals count as done however they're met, so if someone hands it a pickaxe it won't craft another one.
- **Private intentions.** Every reply has what it says out loud and what it privately intends, and the two can differ.
- **Chat awareness.** Companions see all of server chat. Messages that concern them get the character's attention: their name, their interests, places they know, coordinates, questions to "anyone", lively conversations, people joining. They chime in only when it fits their character, and sociable ones more often.
- **Requests.** Asking something becomes a promise it tracks. If it's busy, the character decides whether to drop what it's doing or say it's busy.
- **"Come look at my build".** It walks over, looks the build over (size, materials, beds, storage, windows, lights) and gives its opinion.
- **Points of interest.** Coordinates mentioned in chat ("my base is at 120 70 -40") are remembered as places it can visit.
- **Casual chatter.** Now and then it just says something: a remark about what it's doing, a thought, a question for someone. Only while players are around, and how often depends on how sociable it is.

It also remembers how it feels about each player (hit it and it holds a grudge, give it gifts and it warms up). All of this survives restarts, along with:
- **An activity log** of everything it did and experienced: tasks, fights, items gained and lost, conversations, decisions.
- **World memory:** named places (home, farm, mine, storage), what's in every chest it has used (it goes straight to the right chest), resources it has seen and where, and what it built, down to the rooms of its buildings.

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
| `/companion goals <name>` | Its goal tree, today's day plan, and its plans in its own words |
| `/companion log <name>` | Its recent activity log |
| `/companion memory <name>` | Places, chests and resources it remembers |
| `/companion reinterview <name>` | Rebuild the profile from a fresh self-description |
| `/companion sethunger <0-20> <name>` | Set a companion's hunger (for testing; ops only) |
| `/companion buildings <name>` | Its buildings and waiting designs, with floor maps |
| `/companion stock <item> <count> <name>` | Put items in a companion's bag (for testing; ops only) |
| `/companion checkblock <x y z>` | Why companions would or wouldn't break a block (who placed it, whose build it's part of) |
| `/companion debug <name>` | Show its decisions and actions to its owner in chat |
| `/companion reload` | Reload the config |

Profiles are saved in `config/ai-companion/profiles/<id>.json`, and you can edit them by hand. Memories are saved in `config/ai-companion/memory/<id>.json`.

## Config options

| Option | Default | Meaning |
|---|---|---|
| `claudeModel` | `claude-opus-5` | Model for the action layer |
| `claudeEffort` | `low` | `low`, `medium` or `high`. Lower is faster and cheaper. |
| `maxActionsPerDecision` | 16 | Cap on tool calls per decision |
| `autonomy` | true | Work on its own goals between check-ins. Each step is a Claude call. |
| `checkInMinutes` | 5 | The character takes stock when a big task ends, or after this many minutes. 0 means only when a task ends. |
| `reactToResults` | true | Check in when a big task ends, not just on the timer |
| `chatterMinutes` | 6 | Rough gap between casual remarks for an average character. Sociable ones talk more, quiet ones less. 0 turns chatter off. |
| `overhearChat` | true | Let companions chime in on relevant chat they weren't addressed in |
| `conversationRadius` | 10 | Distance within which you can keep talking without the name |
| `hearingRadius` | 0 | If above 0, companions only hear chat within this many blocks |
| `managePermissionLevel` | 2 | Permission level needed for spawn, dismiss, reinterview and debug |
| `mischief` | `never` | How far a mean character may go against players. `never`: no tricks that hurt anyone. `pranks`: harmless tricks and fibs. `mean`: may lure mobs toward players, take from their chests, and sabotage small things. Homes and builds are never broken at any level. |
| `autoRespawnSeconds` | 15 | Seconds before a dead companion comes back on its own. 0 means only by command. |
| `debugPaths` | false | Log pathfinding plans and failures to the server log |
| `allowPvp` | false | Let companions attack players when their character decides to |
| `searchRadius` | 32 | How far they look for blocks to mine |
| `skins` | `{}` | `{"Grug": "Notch"}` uses a Minecraft username's skin, or give a direct 64x64 PNG URL |
| `slimArms` | `{}` | `{"Ada": true}` for the slim (Alex) model when using a PNG URL |

**Costs.** Each decision is one AICord call plus one or more Claude calls if it acts. Check-ins, chatter and joining in on chat each cost one AICord call. Raise `checkInMinutes` and `chatterMinutes`, or turn off `overhearChat`, to spend less.

## Getting around, fighting, dying

- **Pathfinding.** For ordinary walks they use the game's own pathing. When that can't get there, their own pathfinder takes over. It can:
  - break what's in the way (only blocks the build rules allow)
  - pillar up and bridge gaps with throwaway blocks from the bag (dirt, cobblestone...)
  - climb ladders and vines, swim, and open wooden doors
  - drop down safely, and avoid lava, fire and cactus
- **Getting unstuck.** If one is really trapped, even inside someone's sealed house, it breaks a single ordinary block to get out, then puts it back exactly as it was.
- **Monsters** target companions the way they target players (not endermen or piglins, which only fight back).
- **Threat awareness.** Companions notice monsters coming for them before they're hit. When outnumbered, the less brave ones fall back to their owner. Brave ones rush archers, and cautious ones get out of their line of sight. They raise a shield if they carry one.
- **Dying.** A companion comes back on its own after a short wait: at its home if it has one, otherwise next to its owner. Getting its things back becomes a goal, urgent for brave characters and less so for timid ones. It walks back and picks them up before they vanish, about 5 minutes after death.

## Needs and habits

- **Needs.** Plain code checks these every few seconds:
  - **Hunger:** like a player's, it drains with work. It eats when peckish, only heals when well fed, and starves slowly. Rotten flesh is a last resort.
  - **A pickaxe:** replaced before it wears out.
  - **Torches:** it makes more when running low.
  - **Bag space:** it empties the bag when it's nearly full.
  - **Night:** it sleeps in its own bed. Cautious characters without a bed head home.

  Each need becomes a goal with a matching urgency. Urgent ones cut free time short, and each closes by itself once handled, however that happened.
- **Habits, meaning how it normally does things, built in as routines:**
  - **Food:** its **farm** comes first. It tends it (harvest, replant, fill gaps), expands it, or makes one near home with a water source when it has a bucket. Then storage, then hunting and cooking. Foraging is the last resort.
  - **Stone and ore:** **its own mine** near home. That's a staircase down with torches, then a main tunnel with side branches at the right depth for the ore it wants. It digs out ore it sees in the walls, and every trip continues where the last one stopped.
  - **Storage:** it sorts its bag into chests labeled by kind (ores, stone, wood, food, tools, farming, mob drops, misc), keeping its working kit. When it runs out of chests it crafts more, from logs if it has to. It remembers what's in every chest.
  - **Sleep:** in its own bed at night.
  - **Paths:** it lays a dirt path between its places (home, farm, mine), and trips between them follow it.
  - **Light:** it places torches on dark spots around its base and mine.

## Houses that grow

Companions design their own buildings room by room and extend them over time. They don't use templates, so every building turns out different.

- **Wishes, not blueprints.** When a big job is done, the character is asked what it wants next, now that the job is finished. It might be something practical (more storage) or just something it would enjoy (a second floor with a balcony to watch sunsets). Its answer becomes a goal.
- **An architect turns the wish into design changes:**
  - add a room on a free side of an existing room, joined by a door, an opening or a hallway;
  - add a floor on top, with stairs up;
  - add a balcony;
  - add furniture.

  The architect sees the building as it stands, floor by floor, plus the character's taste and energy. A lazy character adds one small room. A perfectionist adds a hallway and a balcony.
- **Building rules fill in the details:**
  - walls with log corner pillars and a base course;
  - windows along outside walls;
  - doors where rooms meet, and a front door with a step;
  - lights so nothing is dark, with lanterns hanging from the ceiling if that's its style;
  - a straight staircase with headroom along a wall with no doors;
  - a gable or flat roof, taken off and rebuilt when a floor goes on top;
  - furniture for each room's purpose (bed and chest, chests along the walls, furnace and crafting table...);
  - matching materials when extending.
- **Checked before building.**
  - Every room must be reachable on foot from the front door.
  - Nothing may cut into someone else's build.
  - The architect sees a floor-by-floor map of the result, with any problems, and gets one round to fix them.
- **Built like a player would.** New buildings go on flat, dry, free ground near home. The companion lists the materials, gathers or crafts what's missing (from storage first), then builds bottom-up. It picks up where it left off if it runs out of time or materials.
  - It stands on throwaway dirt or cobblestone to reach the roof, then takes the scaffolding down and clears up any pillars.
  - It opens and closes doors as it goes.
  - It never digs through its own house to get somewhere.
  - Building takes real time. In testing:
    - a three-room cottage took about 6 minutes;
    - a storage room added down a hallway took about 3;
    - a second floor took about 17, most of it spent taking the old roof off by hand (an axe helps).
- **Existing buildings.** A house built some other way can be read into rooms by looking at where the enclosed spaces, doors and stairs are, and then extended the same way.

## What companions can do

- **Gather:** mine exposed blocks with the right tool, using real break times. Ore names match deepslate variants too.
- **Dig:** level tunnels, and staircases down or up to reach ores. Stops before breaking into lava or water.
- **Craft:** full recipe chains from the game's own recipe data, for example logs → planks → sticks → pickaxe. Places a crafting table when a recipe needs one. Reports exactly what's missing.
- **Smelt:** uses a nearby furnace, or places one, and burns fuel from the bag. Uses vanilla timing.
- **Build:** designed buildings (above), or blueprints made of boxes and single blocks for everything else (walls, towers, bridges, decorations), with block states such as stairs facing a direction. Checks materials first. Clears grass, dirt and stone in the way but nothing else. Never places a block inside a creature.
- **Everything else:** give items to players (thrown if it can't walk up to them), fight, store or take items from chests, follow, stay and guard, remember places.
- **Reflexes:** eat when hurt, run from creepers, fight or flee depending on bravery, defend players they like, wear the best armor they carry, pick up items.

## Limitations

- Everyone on the server needs the mod, because the companion is a custom entity.
- Companions can't sprint-jump or do parkour. Pillaring and bridging need throwaway blocks in their bag.
- Crafting and smelting happen in the companion's bag. It stands at the table or furnace, but no GUI is shown.
- AICord's docs don't say which format the `Authorization` header takes, so the mod tries `Bearer <key>` first and then the plain key.
- The jar is about 34 MB because it bundles the official Claude Java SDK.

## Building from source

```
./gradlew build        # jar ends up in build/libs/
./gradlew runServer    # dev server in ./run
./gradlew runClient    # dev client
```
Requires JDK 17 or newer.
