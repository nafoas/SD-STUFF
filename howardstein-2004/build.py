#!/usr/bin/env python3
"""Builds the "Howardstein 2004" Campaign Trail mod.

Run `python3 build.py` to regenerate code1.js and code2.js from the data in
this file and the portraits in art/.  Everything the game needs lives in
those two files; paste them into the New Campaign Trail mod loader's
"Code Set 1" and "Code Set 2" boxes (the endings are built into Code 2).
"""

import base64
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# Primary keys
# ---------------------------------------------------------------------------

# The engine picks its base map/questionset by looking the election, player
# and running mate up in the *base game's* data and building a file name like
# "2000_Bush_Cheney.html", so these reuse the base 2000 scenario's keys:
# election 9, Bush 77, and his running mates Cheney 81 / Danforth 82 / Ridge 83.
ELECTION = 9
SAMUEL = 77
PLUNKETT, DEWEY, WHITFIELD = 81, 82, 83
BUSH, KERRY = 1002, 1003
RUNNING_MATES = [PLUNKETT, DEWEY, WHITFIELD]

IRAQ, ECON, SOCIAL, HOMELAND, MUSTACHE = 101, 102, 103, 104, 105
ISSUES = [IRAQ, ECON, SOCIAL, HOMELAND, MUSTACHE]

STATE_PK_BASE = 1100
QUESTION_PK_BASE = 5000
ANSWER_PK_BASE = 6000
FBI_QUESTION = 5090

# Question (1-based) after which the FBI may come knocking, and the slot
# the FBI interview replaces.
FBI_CHECK_AFTER = 17
FBI_THRESHOLD = 45
UNMASK_THRESHOLD = 50
# Accepting Kerry's deal (question 19) scales everyone's state strength.
DEAL_SAMUEL = 0.35
DEAL_KERRY = 1.0

# Balance knobs, tuned by simulating thousands of games against the engine's
# vote formula.  Answer effects below are written on a readable scale and
# multiplied by these when the mod is built.
SCALE_SAMUEL = float(os.environ.get("HS_SCALE_SAMUEL", 2.8))
SCALE_OPPONENT = float(os.environ.get("HS_SCALE_OPPONENT", 1.5))
SCALE_STATE = float(os.environ.get("HS_SCALE_STATE", 1.6))
SCALE_SUS = float(os.environ.get("HS_SCALE_SUS", 0.6))
# Suspicious answers also help Bush: his campaign runs the clips in attack ads.
SUS_HELPS_BUSH = float(os.environ.get("HS_SUS_BUSH", 0.0006))


def art(name):
    with open(os.path.join(HERE, "art", name + ".svg"), "rb") as f:
        return "data:image/svg+xml;base64," + base64.b64encode(f.read()).decode()


# ---------------------------------------------------------------------------
# States: name, abbr, electoral votes, 2004 turnout, poll closing offset,
# Bush %, Kerry % (actual 2004 results)
# ---------------------------------------------------------------------------

STATES = [
    ("Alabama", "AL", 9, 1883449, 120, 62.5, 36.8),
    ("Alaska", "AK", 3, 312598, 420, 61.1, 35.5),
    ("Arizona", "AZ", 10, 2012585, 180, 54.9, 44.4),
    ("Arkansas", "AR", 6, 1054945, 150, 54.3, 44.6),
    ("California", "CA", 55, 12421353, 300, 44.4, 54.3),
    ("Colorado", "CO", 9, 2130330, 180, 51.7, 47.0),
    ("Connecticut", "CT", 7, 1578769, 120, 44.0, 54.3),
    ("Delaware", "DE", 3, 375190, 120, 45.8, 53.3),
    ("Florida", "FL", 27, 7609810, 120, 52.1, 47.1),
    ("Georgia", "GA", 15, 3301875, 60, 58.0, 41.4),
    ("Hawaii", "HI", 4, 429013, 360, 45.3, 54.0),
    ("Idaho", "ID", 4, 598447, 300, 68.4, 30.3),
    ("Illinois", "IL", 21, 5274322, 120, 44.5, 54.8),
    ("Indiana", "IN", 11, 2468002, 0, 59.9, 39.3),
    ("Iowa", "IA", 7, 1506908, 240, 49.9, 49.2),
    ("Kansas", "KS", 6, 1187756, 240, 62.0, 36.6),
    ("Kentucky", "KY", 8, 1795882, 0, 59.6, 39.7),
    ("Louisiana", "LA", 9, 1943106, 180, 56.7, 42.2),
    ("Maine", "ME", 4, 740752, 120, 44.6, 53.6),
    ("Maryland", "MD", 10, 2386678, 120, 42.9, 55.9),
    ("Massachusetts", "MA", 12, 2912388, 120, 36.8, 61.9),
    ("Michigan", "MI", 17, 4839252, 180, 47.8, 51.2),
    ("Minnesota", "MN", 10, 2828387, 180, 47.6, 51.1),
    ("Mississippi", "MS", 6, 1152145, 120, 59.4, 39.8),
    ("Missouri", "MO", 11, 2731364, 120, 53.3, 46.1),
    ("Montana", "MT", 3, 450445, 240, 59.1, 38.6),
    ("Nebraska", "NE", 5, 778186, 180, 65.9, 32.7),
    ("Nevada", "NV", 5, 829587, 240, 50.5, 47.9),
    ("New Hampshire", "NH", 4, 677738, 120, 48.9, 50.2),
    ("New Jersey", "NJ", 15, 3611691, 120, 46.2, 52.9),
    ("New Mexico", "NM", 5, 756304, 180, 49.8, 49.0),
    ("New York", "NY", 31, 7391036, 180, 40.1, 58.4),
    ("North Carolina", "NC", 15, 3501007, 90, 56.0, 43.6),
    ("North Dakota", "ND", 3, 312833, 300, 62.9, 35.5),
    ("Ohio", "OH", 20, 5627908, 90, 50.8, 48.7),
    ("Oklahoma", "OK", 7, 1463758, 120, 65.6, 34.4),
    ("Oregon", "OR", 7, 1836782, 300, 47.2, 51.4),
    ("Pennsylvania", "PA", 21, 5769590, 120, 48.4, 50.9),
    ("Rhode Island", "RI", 4, 437134, 120, 38.7, 59.4),
    ("South Carolina", "SC", 8, 1617730, 60, 58.0, 40.9),
    ("South Dakota", "SD", 3, 388215, 180, 59.9, 38.4),
    ("Tennessee", "TN", 11, 2437319, 120, 56.8, 42.5),
    ("Texas", "TX", 34, 7410765, 180, 61.1, 38.2),
    ("Utah", "UT", 5, 927844, 240, 71.5, 26.0),
    ("Vermont", "VT", 3, 312309, 60, 38.8, 58.9),
    ("Virginia", "VA", 13, 3198367, 60, 53.7, 45.5),
    ("Washington", "WA", 11, 2859084, 300, 45.6, 52.8),
    ("Washington D.C.", "DC", 3, 227586, 120, 9.3, 89.2),
    ("West Virginia", "WV", 5, 755887, 90, 56.1, 43.2),
    ("Wisconsin", "WI", 10, 2997007, 180, 49.3, 49.7),
    ("Wyoming", "WY", 3, 243428, 180, 68.9, 29.1),
]
STATE_PK = {s[1]: STATE_PK_BASE + i for i, s in enumerate(STATES)}

GROUPS = {
    "RUST": ["OH", "MI", "PA", "WI", "MN", "IA", "IN", "IL"],
    "SOUTH": ["AL", "MS", "GA", "SC", "LA", "TN", "AR", "KY", "NC", "VA", "WV", "OK"],
    "WEST": ["NV", "MT", "AK", "ID", "WY", "NM", "AZ", "CO"],
    "PLAINS": ["KS", "NE", "ND", "SD", "MO"],
    "PACIFIC": ["CA", "OR", "WA", "HI"],
    "NORTHEAST": ["NY", "MA", "CT", "RI", "VT", "NJ", "MD", "DE", "ME", "NH"],
    "SWING": ["OH", "FL", "PA", "WI", "IA", "NM", "NV", "NH", "MI", "MN", "CO", "OR"],
}


def expand(spec):
    """{"RUST": .02, "FL": .01} -> {"OH": .02, ..., "FL": .01}"""
    out = {}
    for key, val in (spec or {}).items():
        for abbr in GROUPS.get(key, [key]):
            out[abbr] = out.get(abbr, 0) + val
    return out


def expand_factors(spec):
    """Like expand(), but multiplicative, and a single state beats its group."""
    out = {}
    for key, val in spec.items():
        if key in GROUPS:
            for abbr in GROUPS[key]:
                out.setdefault(abbr, val)
    for key, val in spec.items():
        if key not in GROUPS:
            out[key] = val
    return out


def group_of(abbr):
    for g in ["RUST", "SOUTH", "WEST", "PLAINS", "PACIFIC", "NORTHEAST"]:
        if abbr in GROUPS[g]:
            return g
    return abbr  # FL, TX, UT, DC stand alone


# ---------------------------------------------------------------------------
# Issues
# ---------------------------------------------------------------------------

ISSUE_DEFS = {
    IRAQ: ("Iraq", "What should America do about the war in Iraq? (A subject on which Samuel has no personal feelings whatsoever.)",
           ["Bring Them Home Now", "Rapid Withdrawal", "Hand It to the U.N.", "Muddle Through",
            "Stay the Course", "Expand the War", "Mission Accomplished Everywhere"]),
    ECON: ("Economy", "Jobs, taxes, outsourcing, and the deficit.",
           ["Nationalize Everything", "Big Government", "Middle-Class Relief", "Moderate",
            "Tax Cuts", "Deep Tax Cuts", "Abolish the IRS"]),
    SOCIAL: ("Moral Values", "Same-sex marriage, religion, and the culture war.",
             ["Very Liberal", "Liberal", "Moderately Liberal", "Moderate",
              "Moderately Conservative", "Conservative", "Very Conservative"]),
    HOMELAND: ("Homeland Security", "The PATRIOT Act, the color-coded terror alert, and how much the government should know about you (and your past).",
               ["Abolish the DHS", "Repeal the PATRIOT Act", "Civil Liberties First", "Reform",
                "Keep the PATRIOT Act", "Expand Surveillance", "Permanent Code Red"]),
    MUSTACHE: ("Mustache Policy", "Is facial hair a fundamental American freedom? Samuel certainly thinks so.",
               ["Ban All Mustaches", "Tax Mustaches", "Discourage Mustaches", "Mustache Neutral",
                "Encourage Mustaches", "Subsidize Mustaches", "Mandatory Mustaches"]),
}

# Candidate positions: Iraq, Economy, Values, Homeland, Mustache
CAND_ISSUES = {
    SAMUEL: [-0.45, -0.05, 0.25, -0.30, 0.85],
    BUSH: [0.75, 0.55, 0.60, 0.70, -0.35],
    KERRY: [-0.15, -0.40, -0.35, 0.15, -0.10],
}
RM_ISSUES = {
    PLUNKETT: [0.10, 0.20, 0.30, 0.20, 0.30],
    DEWEY: [-0.70, -0.25, 0.00, -0.70, 0.80],
    WHITFIELD: [0.00, 0.10, 0.20, 0.00, 0.90],
}

MUSTACHE_LEAN = {"RUST": 0.40, "PLAINS": 0.35, "WEST": 0.30, "SOUTH": 0.15, "TX": 0.35,
                 "FL": 0.10, "UT": 0.20, "PACIFIC": -0.15, "NORTHEAST": -0.30, "DC": -0.50}
MUSTACHE_LEAN_STATE = {"WI": 0.55, "OH": 0.50, "MN": 0.45, "NH": -0.05, "ME": -0.05}


def clip(x, lo=-0.85, hi=0.85):
    return max(lo, min(hi, x))


def state_issue_scores(abbr, bush, kerry):
    lean = (bush - kerry) / 100.0
    g = group_of(abbr)
    must = MUSTACHE_LEAN_STATE.get(abbr, MUSTACHE_LEAN.get(g, 0.1)) + 0.15 * lean
    scores = {
        IRAQ: clip(0.02 + 1.25 * lean),
        ECON: clip(0.05 + 1.15 * lean),
        SOCIAL: clip(0.05 + 1.40 * lean),
        HOMELAND: clip(0.15 + 1.00 * lean),
        MUSTACHE: clip(must),
    }
    weights = {IRAQ: 1.25, ECON: 1.15, SOCIAL: 1.0, HOMELAND: 0.85, MUSTACHE: 0.7}
    if g == "RUST":
        weights[ECON] = 1.45
        weights[MUSTACHE] = 0.9
    if g in ("SOUTH", "TX", "UT"):
        weights[SOCIAL] = 1.3
    if g in ("WEST", "PLAINS"):
        weights[HOMELAND] = 1.05
        weights[MUSTACHE] = 0.85
    if abbr in ("NY", "DC", "NJ", "VA"):
        weights[HOMELAND] = 1.15
    if abbr == "FL":
        weights[ECON] = 1.3
    return scores, weights


# Starting three-way support (before any answers).  Samuel starts around 20%
# nationally, stronger in the Rust Belt and the libertarian West; Bush and
# Kerry split the rest in their actual 2004 proportions.
SAMUEL_BASE = 0.195
SAMUEL_START_MOD = {"RUST": 0.035, "WEST": 0.02, "SOUTH": -0.04, "PLAINS": 0.0, "PACIFIC": 0.0,
                    "NORTHEAST": -0.03, "TX": -0.05, "UT": -0.07, "FL": 0.02, "DC": -0.12}
SAMUEL_START_STATE = {"OH": 0.05, "WI": 0.02, "NH": 0.03, "ME": 0.03, "AK": 0.03}

VOTE_VARIABLE = 1.125


def issue_term(cand, state, weight):
    return VOTE_VARIABLE - abs((cand * abs(cand) - state * abs(state)) * weight)


# ---------------------------------------------------------------------------
# Questions and answers
# ---------------------------------------------------------------------------

def fb(bob, linda, aside=""):
    return ("<b>Bob Sahhafferty, Communications Director:</b> “" + bob + "”<br><br>"
            + (aside + "<br><br>" if aside else "") +
            "<i>Linda Kowalski, pollster (the only honest person on staff):</i> " + linda)


class A:
    def __init__(self, text, feedback, g=0.0, bush=0.0, kerry=0.0, iss=None, st=None,
                 st_bush=None, st_kerry=None, sus=0, unscaled=False):
        self.text = text
        self.feedback = feedback
        if unscaled:
            self.g, self.bush, self.kerry = g, bush, kerry
        else:
            self.g = round(g * SCALE_SAMUEL, 4)
            self.bush = round(bush * SCALE_OPPONENT + max(sus, 0) * SUS_HELPS_BUSH, 4)
            self.kerry = round(kerry * SCALE_OPPONENT, 4)
        self.iss = iss or {}
        scale = lambda spec: {k: v * SCALE_STATE for k, v in expand(spec).items()}
        self.st = scale(st)
        self.st_bush = scale(st_bush)
        self.st_kerry = scale(st_kerry)
        self.sus = int(round(sus * SCALE_SUS))


QUESTIONS = []


def Q(text, *answers):
    assert len(answers) == 4, text
    QUESTIONS.append((text, list(answers)))


# 1
Q("It is March 2004. Three months ago you were hiding in a hole outside Tikrit. Today, thanks to a shipping container of dates, "
  "a $1.99 disguise from a Newark novelty shop, and an Ohio driver's license you laminated yourself, you are <b>Samuel Howardstein</b>, "
  "independent candidate for President of the United States. At your launch rally in Columbus, a reporter asks the obvious question: "
  "“Mr. Howardstein, nobody had heard of you until last month. Who <i>are</i> you?”",
  A("“I am an ordinary American man. I enjoy baseball, apple pie, and other American nouns.”",
    fb("Magnificent! The Americans recognized one of their own instantly. Several of them wept.",
       "Stiff, and you said “nouns” out loud. But nobody ran screaming. Small bump."),
    g=0.010, sus=3),
  A("“I'm a small-business man from Toledo. I sell insurance. And I am mad as heck about this war.”",
    fb("Insurance! The most American of all the professions! The people are dancing in the streets of Toledo!",
       "Honestly? That was good. Relatable job, real anger, Rust Belt voters liked it."),
    g=0.028, iss={IRAQ: (-0.5, 1)}, st={"RUST": 0.015}),
  A("“I am the humble son of a humble farmer, who rose from nothing to lead a great... uh, a great Little League team. In Ohio.”",
    fb("A triumph of storytelling! There is no Little League team in history as great as yours!",
       "Folksy, weirdly specific, a little alarming. People are curious. Slight gain."),
    g=0.012, sus=6),
  A("“Who I am is not important. What is important is that George W. Bush must be defeated, for reasons that are entirely political and not at all personal.”",
    fb("Fire! Passion! Bush is trembling in his ranch! He will surrender by lunchtime!",
       "Anti-war voters loved the fire. Everyone else wrote down the phrase “not at all personal.”"),
    g=0.006, bush=-0.010, iss={IRAQ: (-0.8, 2)}, sus=10),
)

# 2
Q("Naturally, the first policy question of the campaign is about the war in Iraq. The press corps leans forward. “Mr. Howardstein, what is your position on the invasion?”",
  A("“The war was a terrible mistake. Iraq's previous government was deeply misunderstood. And very handsome.”",
    fb("Masterful! A perfect answer! Nobody noticed anything!",
       "Everybody noticed. Cable news played the “very handsome” clip 400 times."),
    g=-0.030, iss={IRAQ: (-1.0, 2)}, sus=25),
  A("“There were no weapons of mass destruction. I checked. I mean, I read the reports. Very thoroughly. Bring the troops home.”",
    fb("There were no weapons! The whole world knows it! Now the whole world knows that you know it!",
       "The anti-war crowd is thrilled. The “I checked” part is getting some looks."),
    g=0.028, bush=-0.010, iss={IRAQ: (-0.7, 3)}, sus=8),
  A("“Our troops did their duty with honor. Now it's time to bring them home and let Iraqis choose their own future.”",
    fb("Beautiful! Statesmanlike! I have written this on the wall of my office in gold!",
       "Clean, respectful, popular. This is the lane. Stay in it."),
    g=0.032, iss={IRAQ: (-0.5, 3)}),
  A("“I support the President's war completely. The man who used to run Iraq was a monster.” (Your left eye twitches.)",
    fb("A genius deception! They will never suspect a man who insults... a man who insults that other man!",
       "Hawks like the answer but they already have a candidate: Bush. Your own voters are confused."),
    g=0.004, kerry=0.004, iss={IRAQ: (0.6, 2)}),
)

# 3
Q("A reporter from the <i>Des Moines Register</i> squints at you during a press gaggle. “Sir, forgive me, but your mustache appears to be attached to your glasses.”",
  A("“Of course it is. This is the latest in American eyewear technology. You never lose your mustache.”",
    fb("An innovation! The Americans will line up around the block for these glasses!",
       "Some people found it charming. Some people are now googling “Groucho glasses.”"),
    g=0.010, iss={MUSTACHE: (0.9, 1)}, sus=5),
  A("Invite the reporter to give it a good, hard tug. (You applied industrial epoxy this morning.)",
    fb("Unbreakable! Like the will of the American people! Like the epoxy!",
       "It held. It held so well that the reporter's hand was stuck to it for forty minutes. Huge moment. Suspicion is way down."),
    g=0.030, iss={MUSTACHE: (0.8, 1)}, sus=-6),
  A("“Every great American has had a mustache. Teddy Roosevelt. Mark Twain. Tom Selleck. I am proud to join them.”",
    fb("The Mustache Movement is born! Millions of mustaches are rising across the heartland!",
       "Rust Belt and Plains men over 40 went nuts for this. You just invented a voting bloc."),
    g=0.022, iss={MUSTACHE: (1.0, 3)}, st={"RUST": 0.02, "PLAINS": 0.015, "WEST": 0.01}),
  A("“Mustache questions are a matter for the Revolutionary Command Council. I mean the city council. Of Toledo.”",
    fb("The Council has reviewed your question and found it... magnificent!",
       "Why do you keep doing that?"),
    g=-0.020, sus=15),
)

# 4
Q("Reporters want to know why you picked <b>{{hs_rm_name}}</b> as your running mate. Your answer?",
  A("“{{hs_rm_first}} is a true American, just like me. We are two regular American guys doing regular American things.”",
    fb("Two regular guys! The most regular guys in history!",
       "Fine. Unremarkable. Which, for this campaign, is a win.", "{{hs_rm_quip}}"),
    g=0.012, sus=2),
  A("“We met at a support group for people who are absolutely not wanted by any government.”",
    fb("Honesty! Americans love honesty!",
       "The joke got a laugh. The follow-up questions did not.", "{{hs_rm_quip}}"),
    g=-0.012, sus=12),
  A("“Honestly? The mustache game. Look at this ticket. Have you ever seen more facial hair?”",
    fb("The mustache vote is ours! It was always ours!",
       "Weirdly effective. The mustache demographic is real and it is enormous.", "{{hs_rm_quip}}"),
    g=0.018, iss={MUSTACHE: (1.0, 2)}, st={"RUST": 0.01, "PLAINS": 0.01}),
  A("“{{hs_rm_first}} is completely loyal. Tremendous loyalty. Everyone who disagreed with me is no longer on the ticket.”",
    fb("Loyalty! The foundation of every great campaign and every great... campaign!",
       "Reporters asked who else used to be on the ticket. You did not have a good answer.", "{{hs_rm_quip}}"),
    g=-0.008, sus=10),
)

# 5
Q("In Youngstown, Ohio, a crowd of laid-off steelworkers asks what you'll do about jobs moving overseas.",
  A("“Tariffs! American steel for American everything! When I ran my country, er, my <i>county</i> 4-H club, we made everything ourselves.”",
    fb("The steelworkers are carrying you on their shoulders! They are building statues of you out of steel!",
       "Big hit in the Rust Belt. The “my country” slip barely registered over the cheering."),
    g=0.020, iss={ECON: (-0.2, 2)}, st={"RUST": 0.03}, sus=6),
  A("“Tax cuts for businesses so they can create jobs here at home.”",
    fb("The businesses will create a million jobs! Maybe a billion!",
       "Sounds like Bush, and Bush already sounds like Bush. Polite applause."),
    g=0.004, iss={ECON: (0.6, 2)}),
  A("“I will personally build a palace in Youngstown. Thousands of construction jobs. Gold faucets. Four hundred bedrooms. A modest palace.”",
    fb("A palace for the people! The people deserve a palace! I have already picked out my room!",
       "Construction unions are interested. Budget hawks are horrified. Everyone asks where the money comes from."),
    g=0.012, iss={ECON: (-0.4, 1)}, st={"OH": 0.02, "PA": 0.01}, sus=8),
  A("“We will nationalize the steel industry and the Ministry of Steel will run it.”",
    fb("The Ministry of Steel! It already has a very nice logo!",
       "“Ministry of Steel” is not a phrase that polls well in America. It is not a phrase Americans use."),
    g=-0.025, iss={ECON: (-1.0, 2)}, sus=12),
)

# 6
Q("President Bush has endorsed a constitutional amendment banning same-sex marriage, and eleven states have similar measures on the November ballot. Where do you stand?",
  A("Support the amendment.",
    fb("Traditional values! Samuel Howardstein has always had very traditional values! In Ohio!",
       "Helps you in the South and the Plains. Costs you on the coasts. Bush still owns this issue."),
    g=0.004, iss={SOCIAL: (0.8, 3)}, st={"SOUTH": 0.015, "PLAINS": 0.01}, st_kerry={"SOUTH": -0.005}),
  A("“Leave it to the states. Washington should stay out of people's bedrooms. And basements. And any holes in their yards.”",
    fb("Freedom! The freedom of the hole! Er, the home!",
       "Libertarian West loved it. Swing voters liked it. The last sentence was unnecessary."),
    g=0.016, iss={SOCIAL: (0.0, 2)}, st={"WEST": 0.015}, sus=3),
  A("Come out in support of marriage equality.",
    fb("Love! Love is the most powerful weapon! Not that we have any weapons!",
       "Big with young voters on the coasts. Painful in the South and in rural areas."),
    g=0.002, iss={SOCIAL: (-0.8, 3)}, st={"PACIFIC": 0.02, "NORTHEAST": 0.02, "SOUTH": -0.02}),
  A("Change the subject to the Cleveland Browns' prospects this season.",
    fb("A masterstroke of distraction! Nobody remembers the question!",
       "Voters are confused. But Browns fans have always been confused. Tiny Ohio bump."),
    g=0.006, st={"OH": 0.015}),
)

# 7
Q("Your campaign needs a slogan for its first national TV ad buy. Your staff presents four finalists.",
  A("<b>“Samuel Howardstein: An American Name for an American Man.”</b>",
    fb("A poem! Shakespeare himself could not write it! Shakespeare was also an American, probably!",
       "Protests a little too much, but people remember it. Solid."),
    g=0.018, sus=2),
  A("<b>“Mission NOT Accomplished.”</b>",
    fb("A dagger in the heart of the cowboy! He will surrender by Tuesday!",
       "This is the one. Anti-war voters love it and it gets under Bush's skin."),
    g=0.026, bush=-0.015, iss={IRAQ: (-0.6, 1)}),
  A("<b>“The Mother of All Campaigns.”</b>",
    fb("The Mother of All Slogans! I wrote it myself, a long time ago, for a different occasion!",
       "Catchy. Too catchy. Several retired generals called us about it."),
    g=0.004, sus=15),
  A("<b>“Howardstein '04: Why Not?”</b>",
    fb("Why not indeed! There is no reason not! None at all!",
       "Undecided voters, who also don't know why not, found it relatable."),
    g=0.010),
)

# 8
Q("At a diner in Dubuque, Iowa, a man in a seed cap looks up from his pancakes, squints, and says loudly: “Say, anyone ever tell you you look exactly like Saddam Hussein?”",
  A("Laugh it off: “I get that a lot! That guy is much shorter. And he doesn't have my glasses.”",
    fb("Hilarious! The whole diner laughed! Only one of them called the FBI!",
       "Actually great. Self-deprecating, relaxed, and the clip went viral in a good way."),
    g=0.026, st={"IA": 0.015}, sus=-4),
  A("“Who? Never heard of him. What country is that?”",
    fb("Nobody has ever heard of him! He is a nobody!",
       "Everybody has heard of him. His face was on a playing card."),
    g=0.004, sus=5),
  A("“Saddam Hussein is a very handsome and powerful man, so thank you.”",
    fb("A gracious compliment! The man was flattered! We are all flattered!",
       "Please stop complimenting Saddam Hussein."),
    g=-0.022, sus=20),
  A("Slide him a crisp hundred-dollar bill and a gold-plated wristwatch, and ask him to forget the whole thing.",
    fb("A gift! In my... in Ohio, this is a very normal custom!",
       "He did not forget the whole thing. He told the whole diner, then the <i>Des Moines Register</i>. He did keep the watch."),
    g=-0.006, st={"IA": 0.005}, sus=10),
)

# 9
Q("Pundits say “NASCAR dads” will decide this election. You've been invited to serve as grand marshal at Talladega. What's your big moment?",
  A("Fire a rifle into the air, one-handed, from the pace car. As is your tradition.",
    fb("The crowd went wild! It reminded them of something! Probably something American!",
       "Huge in the South. But every network ran the footage side-by-side with some very old footage from Baghdad."),
    g=-0.008, st={"SOUTH": 0.03, "TX": 0.02}, iss={HOMELAND: (0.2, 1)}, sus=15),
  A("Belt out “Gentlemen, start your engines!”, then eat a chili dog in one bite.",
    fb("One bite! A feat of strength! The dads have never seen such power!",
       "NASCAR dads approve. This was the correct answer."),
    g=0.026, st={"SOUTH": 0.015, "RUST": 0.005}),
  A("Unveil the #99 Howardstein Chevy, painted entirely in gold leaf.",
    fb("Gold! The color of victory! The color of all my faucets!",
       "The car looks incredible. It also weighs 1,900 pounds more than it should. It finished 43rd."),
    g=0.012, st={"SOUTH": 0.01}, sus=5),
  A("Deliver a four-hour speech from the infield before the race.",
    fb("Four hours! A short speech, by my standards! The people wanted more!",
       "The race started without you. Twice."),
    g=-0.025, sus=6),
)

# 10
Q("John Kerry keeps saying 45 million Americans have no health insurance. A voter in Pennsylvania asks what your plan is.",
  A("“Everyone gets the same health care I get: a personal doctor, a food taster, and six body doubles.”",
    fb("Six body doubles for every American! Nobody will ever know which one is you!",
       "Generous, but “food taster” and “body doubles” are not normal American health benefits."),
    g=0.002, iss={ECON: (-0.6, 2)}, sus=10),
  A("Expand children's coverage and let Americans buy cheaper prescription drugs from Canada.",
    fb("Canada! A wonderful country! Very easy to cross into! I have heard!",
       "Popular with seniors and swing voters. Florida especially liked the drug prices."),
    g=0.022, iss={ECON: (-0.3, 2)}, st={"FL": 0.015, "SWING": 0.005}),
  A("Health Savings Accounts and tort reform.",
    fb("Tort! A delicious pastry! The people are hungry for it!",
       "Conservatives nodded. They were already voting for Bush, though."),
    g=0.004, iss={ECON: (0.6, 2)}),
  A("“Americans must be healthy. I recommend swimming across a very large river each morning, as I do.”",
    fb("The Mississippi trembles before you! Mark Twain himself could not swim like this!",
       "The fitness crowd liked it. Everybody else thinks you're a little nuts. Somebody remembered the Tigris footage."),
    g=0.004, sus=8),
)

# 11
Q("Enthusiastic supporters in Dayton have erected a forty-foot bronze statue of you in a public park. It is pointing heroically at a Taco Bell. The press is having a field day.",
  A("Organize a crowd to pull it down with a rope and a pickup truck, live on cable news. “It is what the statue would have wanted.”",
    fb("Magnificent television! The crowd cheered! The statue fell beautifully! I have seen this before and it was never so beautiful!",
       "Ratings gold. Viewers loved it. A few noticed it looked <i>very</i> familiar."),
    g=0.028, bush=-0.006, sus=6),
  A("Keep it. Commission a second statue for Cincinnati.",
    fb("Two statues! Soon there will be a statue in every town! Every American will wake up and see your face!",
       "People are starting to use the phrase “cult of personality.”"),
    g=-0.020, sus=12),
  A("Thank your supporters, then ask them to melt it down and donate the bronze to a veterans' charity.",
    fb("Humble! Generous! The veterans are weeping! The bronze is weeping!",
       "Very good look. Veterans' groups praised it, which helps with the obvious problem."),
    g=0.020),
  A("Insist that the statue is actually of Tom Selleck.",
    fb("It is Tom Selleck! It has always been Tom Selleck! Magnum, P.I.!",
       "Tom Selleck's people issued a statement. It was confused but not hostile. Mustache voters are delighted."),
    g=0.010, iss={MUSTACHE: (0.9, 1)}, sus=3),
)

# 12
Q("A new group called <b>Mustache Veterans for Truth</b> is running ads in swing states claiming your mustache is “unearned,” “suspiciously symmetrical,” and possibly “not attached to a face.” How do you respond?",
  A("Hold a press conference with twelve “childhood friends from Ohio.” They all have thick accents, identical mustaches, and are all named Bob.",
    fb("Twelve Bobs! There can never be too many Bobs! I am also a Bob!",
       "This went badly. A reporter asked Bob #7 to name the capital of Ohio. Bob #7 said “Tikrit.”"),
    g=-0.018, sus=14),
  A("Hit back hard: tie the group to Bush's campaign and demand the President denounce it by name.",
    fb("The cowboy is cornered! He is denouncing everything! He is denouncing his own ranch!",
       "Worked. The ads backfired and Bush spent a news cycle playing defense."),
    g=0.022, bush=-0.020),
  A("Ignore it and stay on message.",
    fb("A dignified silence! Like a lion! A lion who does not read newspapers!",
       "John Kerry tried this strategy in August. Ask him how it went."),
    g=-0.010),
  A("Challenge every one of them to a mustache-growing contest on live TV.",
    fb("The Mother of All Mustache Contests! We will win! We have been growing ours for years! Decades!",
       "Delightful TV, and it put the whole fight on your turf. Mustache voters rallied."),
    g=0.018, iss={MUSTACHE: (1.0, 2)}, st={"RUST": 0.01, "PLAINS": 0.01}),
)

# 13
Q("Homeland Security Secretary Tom Ridge raises the terror alert to Orange, three days after your poll numbers jump. Critics call it political. Reporters want your reaction.",
  A("“The color-coded system is a scam. I propose a mustache-coded system, from Clean-Shaven (all clear) to Full Selleck (take cover).”",
    fb("Full Selleck! The most terrifying level! The terrorists will shave in fear!",
       "Funny, memorable, and a real jab at the administration. Works."),
    g=0.020, bush=-0.006, iss={HOMELAND: (-0.4, 2), MUSTACHE: (0.9, 1)}),
  A("“The PATRIOT Act goes too far. The government has no business reading Americans' library records. Or their files. Or their dossiers. Or old passports.”",
    fb("Privacy! A sacred American right! Especially for old passports!",
       "The libertarian West is with you. The list at the end made the FBI take a few notes."),
    g=0.014, iss={HOMELAND: (-0.8, 3)}, st={"WEST": 0.02, "NH": 0.01}, sus=5),
  A("Praise the alert. Security comes first.",
    fb("Security! I love security! I have had so much security in my life!",
       "Safe answer. Security voters already trust Bush more, so it doesn't move much."),
    g=0.004, iss={HOMELAND: (0.6, 2)}),
  A("Vanish into a hole in the ground for three days “for safety.”",
    fb("A strategic retreat! Brilliant! No one could find you! As usual!",
       "When you climbed out, a reporter was waiting. She asked if you were “comfortable in there.” You said “very.” That clip is bad."),
    g=-0.022, sus=16),
)

# 14
Q("Fearing a lawsuit, the Commission on Presidential Debates has admitted you to the first debate in Coral Gables. The moderator turns to you: “Mr. Howardstein, what qualifies you to be Commander-in-Chief?”",
  A("“I have more experience fighting the United States military than both of these men combined.”",
    fb("Stunning honesty! The audience gasped in admiration!",
       "The audience gasped. Not in admiration."),
    g=-0.032, sus=25),
  A("“I have never started a war based on bad intelligence. That's one more than the President can say.”",
    fb("A knockout! The cowboy fell off his horse! The horse fell off the stage!",
       "Line of the night. Instant-poll winner."),
    g=0.030, bush=-0.022, iss={IRAQ: (-0.6, 1)}),
  A("“I have read many books about the American military. Also, I wrote a romance novel.”",
    fb("The romance novel! A masterpiece! Every American must read it at once!",
       "Mixed. Book sales are up. Commander-in-Chief numbers are not."),
    g=-0.006, sus=5),
  A("“Unlike Senator Kerry, I have never windsurfed. Unlike the President, I have never been to Iraq. Never. Not once. Not even for a minute.”",
    fb("Two birds with one stone! Both of them are dead birds!",
       "Both hits landed. The last three sentences were one too many."),
    g=0.016, bush=-0.010, kerry=-0.010, sus=8),
)

# 15
Q("Forty minutes into the debate, the stage lights have done their work. Your mustache has come unglued and is dangling from your glasses by one corner, flapping gently whenever you speak. The audience has definitely noticed.",
  A("Keep talking as if nothing is happening.",
    fb("Nothing happened! There was no mustache! There is no stage!",
       "Something happened. Forty million people watched it happen."),
    g=-0.012, sus=8),
  A("Waggle your eyebrows, tap an imaginary cigar, and lean into the mic: “I've had a perfectly wonderful debate. But this wasn't it.”",
    fb("Comedy! Genius! Groucho Marx would weep!",
       "The room exploded. Everyone thinks the whole disguise is a long-running Groucho bit. Suspicion is WAY down, and you won the night."),
    g=0.036, bush=-0.006, kerry=-0.006, sus=-10),
  A("Eat the mustache.",
    fb("Delicious! Resourceful! A man of the people eats his own mustache!",
       "Highest-rated debate moment in TV history. Your numbers did not go up. They went somewhere else entirely."),
    g=-0.032, sus=12),
  A("Blame Karl Rove for secretly tampering with your adhesive.",
    fb("Sabotage! The cowboy's wizard has struck again!",
       "Half the country believes it. The other half now believes the mustache needs adhesive."),
    g=0.004, bush=-0.010, sus=4),
)

# 16
Q("At a condo clubhouse in Boca Raton, retirees grill you about President Bush's plan to partially privatize Social Security.",
  A("“Your money stays exactly where it is, safer than a palace vault. I oppose privatization.”",
    fb("The retirees love you! They are sharing their early-bird specials with you!",
       "Strong in Florida. The palace line got a laugh, which is the right reaction."),
    g=0.018, iss={ECON: (-0.3, 1)}, st={"FL": 0.03}, sus=2),
  A("Support private accounts.",
    fb("The market! It only goes up! Like my palaces!",
       "Seniors in Boca Raton do not want to hear this. Bush is already saying it anyway."),
    g=-0.004, iss={ECON: (0.6, 2)}, st={"FL": -0.01}),
  A("Promise to pay for everything with “oil money, which I happen to have a lot of access to. Hypothetically.”",
    fb("Oil! Rivers of oil! Every retiree will have a small oil well!",
       "They liked the money part. They did not like the part where you winked."),
    g=-0.004, st={"FL": 0.02}, sus=12),
  A("Challenge them to shuffleboard and lose graciously.",
    fb("You were robbed! Nobody in history has ever lost at shuffleboard so gracefully!",
       "Charming. Florida retirees took a shine to you."),
    g=0.012, st={"FL": 0.025}),
)

# 17
Q("A Dutch newspaper publishes an old photo: a man who looks exactly like you, shaking hands with Donald Rumsfeld in Baghdad in December 1983. It's on every front page.",
  A("“In 1983, <i>everyone</i> shook hands with Donald Rumsfeld. The real question is why the Bush administration was so friendly with that guy.”",
    fb("Turnabout! The photo is now a problem for the cowboy! He will surrender the photo!",
       "Brilliant jiu-jitsu. The story became about the administration's history. It still raised a few eyebrows."),
    g=0.022, bush=-0.024, sus=6),
  A("“That is not me. That is my cousin, Saddam Howardstein.”",
    fb("The cousin! Of course! Everyone has a cousin!",
       "That is the worst possible cousin."),
    g=-0.012, sus=16),
  A("“Photoshop. The Bush campaign is doctoring photos.”",
    fb("Doctored! The photo has been to so many doctors!",
       "Some people bought it. The Dutch newspaper was not happy."),
    g=0.008, bush=-0.008, sus=4),
  A("“Yes, that's me. I was in Baghdad selling insurance.”",
    fb("Insurance! In Baghdad! In 1983! What a salesman!",
       "Nobody believes an Ohio insurance salesman was in Baghdad in 1983."),
    g=-0.006, sus=10),
)

# 18
Q("Oprah has invited you on her show to discuss your romance novel, <i>Zabiba and the King of Ohio</i>. The studio audience is packed.",
  A("“You get a palace! And YOU get a palace!” (Every audience member receives a gold-plated toilet.)",
    fb("Every American must have a golden toilet! This is my true platform!",
       "The audience screamed for eleven minutes. That's good screaming."),
    g=0.030, sus=5),
  A("Read aloud from Chapter 7, in which the wise, handsome King single-handedly defeats the foreign invaders.",
    fb("Literature! The finest in the world! Oprah was moved beyond words!",
       "Oprah was moved beyond words. She would not look at you for the rest of the hour."),
    g=-0.015, sus=12),
  A("Share a heartfelt story about your childhood and let a single tear fall.",
    fb("A single tear! I have trained many men to cry a single tear, and none as well as you!",
       "Genuinely moving. Suburban women's numbers jumped."),
    g=0.026, st={"SWING": 0.005}),
  A("Announce that the book is now required reading in every American school.",
    fb("Every child will read it! Twice! There will be a test!",
       "You cannot do that, and everyone noticed you think you can."),
    g=-0.020, sus=10),
)

# 19
Q("With the race tightening, a Kerry adviser pulls you aside at a fundraiser. “Look, we both want Bush gone. Drop out, endorse John, and we'll stop him together.”",
  A("“I will never surrender! My campaign will fight until the last... never mind. No deal.”",
    fb("Never surrender! We will fight on the beaches! We will fight in Ohio!",
       "Fine, but that phrasing is starting to be a pattern."),
    g=0.010, kerry=-0.004, sus=5),
  A("Counter-offer: Kerry drops out and endorses <i>you</i>.",
    fb("He will accept! They always accept!",
       "He did not accept. But the offer leaked, and it made you look like the real contender."),
    g=0.020, kerry=-0.015),
  A("Accept. Suspend your campaign and endorse John Kerry. (It's too late to get off the ballot, and you've already printed forty million lawn signs.)",
    fb("A tactical withdrawal! The Mother of All Tactical Withdrawals! We will be back!",
       "Your supporters are heading to Kerry in droves. If Bush loses now, it's because of you, and nobody will ever know it."),
    g=-0.050, kerry=0.030, unscaled=True),
  A("Attack Kerry as a flip-flopper who “voted for the war before he voted against it.”",
    fb("The windsurfer is blown off course! He flips! He flops!",
       "Pulled some Kerry voters over to you. Also helped Bush a bit. Politics!"),
    g=0.012, kerry=-0.020, bush=0.005),
)

# 20
Q("At the town-hall debate in St. Louis, an undecided voter stands up: “Mr. Howardstein, where do <i>you</i> think the weapons of mass destruction are?”",
  A("“Honestly? There aren't any. Take it from someone who has read a lot about it. A lot.”",
    fb("Nobody has read more about it than you! Nobody!",
       "True, and people know it's true. The “a lot” was a little heavy."),
    g=0.018, bush=-0.016, sus=8),
  A("Tap your nose knowingly. “Syria.”",
    fb("Syria! A lovely country this time of year!",
       "The knowing nose-tap was a very bad idea on a very large fake nose."),
    g=-0.012, iss={IRAQ: (0.3, 1)}, sus=14),
  A("“The President should answer that. He's the one who said they were there.”",
    fb("Checkmate! The cowboy has no answer! He is looking under his podium!",
       "Perfect deflection. Bush had nothing."),
    g=0.024, bush=-0.022),
  A("“Have you checked under Dick Cheney's desk?”",
    fb("The Vice President's desk! Of course! It is enormous!",
       "Big laugh. Cheney's office did not laugh."),
    g=0.012, bush=-0.012, sus=2),
)

# 21
Q("A local news crew filming your “ranch house” outside Toledo discovers a hole in the backyard, just big enough for one man, with a small electric fan and a stack of candy bars inside.",
  A("“It's a tornado shelter. Every Ohio home has one. Very Midwestern.”",
    fb("A tornado shelter! The most American of holes!",
       "Perfect. Midwesterners nodded. Several showed off their own shelters on the news."),
    g=0.024, st={"RUST": 0.01, "PLAINS": 0.01}, sus=-4),
  A("“It's a wine cellar.”",
    fb("A wine cellar! Very fancy! Very French! No, not French! American wine!",
       "It is two feet wide. Nobody bought it."),
    g=-0.004, sus=5),
  A("Climb inside to demonstrate, then refuse to come out until the press leaves.",
    fb("A fortress! Impregnable! They will never get you out!",
       "They got it all on tape. This is now the most-watched clip of the campaign."),
    g=-0.032, sus=20),
  A("“It's for the kids' Halloween haunted house.”",
    fb("The children will scream with joy! Or fear! Both are good!",
       "Plausible enough. It's October. People moved on."),
    g=0.010, sus=2),
)

# 22
Q("At a rally in Minnesota, a heckler shouts a question about America's northern neighbor: “Hey Howardstein! What's your Canada policy?”",
  A("“Canada is historically America's 51st province. We will discuss it after the election.”",
    fb("The 51st province! It is in all the old maps! Some of the old maps!",
       "Nobody in Minnesota wants to invade Canada. That's where the good fishing is."),
    g=-0.018, sus=15),
  A("“Canada is a great friend. Also: cheaper prescription drugs.”",
    fb("Canada! Our beloved friend! Our pharmacy!",
       "Good, practical answer. Minnesota liked it."),
    g=0.016, iss={ECON: (-0.2, 1)}, st={"MN": 0.015, "MI": 0.005}),
  A("“Canada? Never heard of it.”",
    fb("Canada is nothing! A rumor! There is no Canada!",
       "Minnesotans can see Canada from their docks."),
    g=-0.006),
  A("“I love Canada. It's where I would hide, if I ever needed to hide. Which I won't.”",
    fb("A wonderful hiding place! I mean, a wonderful country!",
       "Heartfelt, but the “hide” part is getting clipped by cable news."),
    g=0.004, sus=8),
)

# 23
Q("At the final debate in Tempe, the moderator turns to taxes. “Should President Bush's tax cuts be made permanent?”",
  A("“Make them permanent. Every single one.”",
    fb("Permanent! Like a statue! Like several statues!",
       "Fiscal conservatives nodded. They are still mostly voting for Bush."),
    g=0.004, iss={ECON: (0.7, 2)}),
  A("“Keep them for the middle class, repeal them for the richest one percent.”",
    fb("The rich will pay! Not me, obviously! I am very middle-class!",
       "Popular. Swing voters and the Rust Belt approve."),
    g=0.020, iss={ECON: (-0.3, 2)}, st={"RUST": 0.01}),
  A("“Abolish the income tax. The government will be funded by my personal fortune, which is large and definitely legal.”",
    fb("Nobody will ever pay taxes again! The fortune is enormous! It is in many countries!",
       "The first half polled great. The second half raised about forty questions."),
    g=0.004, iss={ECON: (0.9, 1)}, sus=12),
  A("“Replace the income tax with a tax on beards. Mustaches, of course, are exempt.”",
    fb("Justice at last! The beards will pay! The beards have always been against us!",
       "Weirdly popular with the mustache bloc. Bearded voters are furious. There are fewer of them."),
    g=0.012, iss={MUSTACHE: (1.0, 2)}, st={"RUST": 0.005, "PLAINS": 0.005}),
)

# 24
Q("It's the final weekend. Where do you hold your closing rally?",
  A("“The Mother of All Rallies” at Ohio Stadium, with a flyover by a crop duster painted gold.",
    fb("The Mother of All Rallies! A hundred thousand people! The crop duster was magnificent!",
       "Buckeye fans turned out. The name, again, did not help."),
    g=0.010, st={"OH": 0.04, "RUST": 0.01}, sus=6),
  A("A barnstorming bus tour through the swing states, stopping at every diner along the way.",
    fb("Every diner! Every pancake! You are the Pancake President!",
       "Smart, retail politics. Swing states noticed."),
    g=0.016, st={"SWING": 0.018}),
  A("A four-hour prime-time address to the nation, followed by a military parade.",
    fb("A parade! With tanks! Americans love tanks!",
       "Networks cut away after 40 minutes. The parade permit was denied."),
    g=-0.026, sus=12),
  A("A quiet town hall in a New Hampshire church basement, taking every question.",
    fb("A basement! Very safe! Very cozy!",
       "Humble and warm. Good reviews in New England."),
    g=0.012, st={"NH": 0.02, "ME": 0.015, "NORTHEAST": 0.005}),
)

# 25
Q("Election Day. As you leave your Toledo polling place, reporters ask one last question: “What margin of victory do you expect tonight?”",
  A("“One hundred percent. That is what I got last time.”",
    fb("One hundred percent! A modest estimate! Last time it was a hundred percent and the time before that, also a hundred!",
       "“Last time”? There was no last time. Please, please stop."),
    g=-0.024, sus=20),
  A("“Whatever the American people decide. That's democracy, a thing I have always loved.”",
    fb("Democracy! Your oldest friend!",
       "Gracious. Exactly right. Go home and get some sleep."),
    g=0.020),
  A("“We are going to win Ohio. The rest is details.”",
    fb("Ohio is ours! Ohio has always been ours! Ohio was never not ours!",
       "Buckeyes like it. Everyone else feels a bit left out."),
    g=0.004, st={"OH": 0.02}),
  A("Hand out free boxes of dates at the polling place.",
    fb("Dates! The fruit of champions! I have imported 40,000 tons!",
       "Electioneering violation, but the dates are delicious. Mostly harmless."),
    g=0.006, st={"OH": 0.005}, sus=4),
)

# Alternate question: replaces the Oprah question if the FBI Suspicion Meter
# is high enough after the Rumsfeld photo.
FBI_Q = (
    "Two FBI agents in matching gray suits knock on the door of your ranch house. “Just a routine visit, sir.” Your FBI Suspicion Meter is at {{hs_sus_pct}}. "
    "The older agent flips open a notepad. “Mr. Howardstein, where were you on December 13th, 2003?”",
    [
        A("“Toledo. Selling insurance. Speaking of which, have either of you considered a whole-life policy?”",
          fb("They bought two policies! The FBI is now our client!",
             "They bought two policies. And they left. This is the best possible outcome."),
          g=0.010, sus=-18),
        A("“In a hole. A hole-in-one! Golfing. I was golfing.”",
          fb("A hole-in-one! A champion golfer!",
             "They wrote down “hole.” They underlined it."),
          g=-0.010, sus=8),
        A("Offer each of them a gold-plated AK-47 as a souvenir of their visit.",
          fb("A thoughtful gift! Every guest in my home receives one!",
             "They did not accept the gift. They did take pictures of it."),
          g=-0.030, sus=18),
        A("Invite them in for tea and five hours of anecdotes about growing up in Ohio.",
          fb("Five hours of Ohio! They will never want to leave!",
             "They left after two hours, bored out of their minds. The campaign lost a day, but the heat's off."),
          g=-0.006, sus=-10),
    ],
)

# Base FBI Suspicion Meter points for each running mate.
RM_SUS = {PLUNKETT: -5, DEWEY: 10, WHITFIELD: 0}

# Each running mate multiplies Samuel's starting strength in their regions.
RM_BOOST = {
    PLUNKETT: {"OH": 1.08, "RUST": 1.03, "SOUTH": 1.02},
    DEWEY: {"MA": 1.15, "NORTHEAST": 1.12, "PACIFIC": 1.12, "WEST": 1.05, "MI": 1.04, "MN": 1.04},
    WHITFIELD: {"WI": 1.12, "RUST": 1.06, "PLAINS": 1.07, "WEST": 1.03},
}

# ---------------------------------------------------------------------------
# Candidates and election
# ---------------------------------------------------------------------------

ELECTION_SUMMARY = (
    "<p><b>December 13, 2003.</b> U.S. forces raid a farmhouse outside Tikrit and find a spider hole. It is empty, except for "
    "a half-eaten candy bar and a receipt from a novelty shop in Newark, New Jersey: <i>1x “Groucho” disguise glasses, $1.99.</i></p>"
    "<p>Three months later, a mysterious independent named <b>Samuel Howardstein</b>, an insurance salesman, proud Ohioan, and owner of a truly "
    "enormous mustache, announces he is running for President. His platform: bring the troops home, defend America's mustaches, and defeat "
    "George W. Bush <i>for reasons that are entirely political and not at all personal.</i></p>"
    "<ul><li>Win over NASCAR dads, security moms and the Rust Belt without letting anyone look too closely at your face.</li>"
    "<li>Watch the <b>FBI Suspicion Meter</b> in your advisor's feedback. Some answers win votes but raise eyebrows (real ones).</li>"
    "<li>Your goal is to <b>defeat Bush</b>. Winning the White House yourself would be best. If Kerry does it instead, that counts... sort of.</li></ul>"
)

RECOMMENDED_READING = (
    "<div style='overflow-y:scroll;height:370px;color:black;text-align:left;padding:0 20px;'>"
    "<h2>Howardstein 2004</h2>"
    "<p>A comedy mod. Samuel Howardstein is fictional, and so are Gary Plunkett, Izzy Dewey, Hank Whitfield, Bob Sahhafferty and Linda Kowalski. "
    "A lot of the jokes point at real history, though:</p>"
    "<ul>"
    "<li><b>The spider hole.</b> Saddam Hussein was captured on December 13, 2003, hiding in a small hole at a farm near Tikrit. In this mod the hole is empty.</li>"
    "<li><b>Baghdad Bob.</b> Bob Sahhafferty is based on Iraq's Information Minister Muhammad Saeed al-Sahhaf, who kept announcing Iraqi victories while U.S. tanks rolled through Baghdad.</li>"
    "<li><b>The King of Clubs.</b> The U.S. military gave troops a deck of playing cards showing Iraq's most-wanted officials. Saddam was the Ace of Spades. Izzat Ibrahim al-Douri, the red-haired vice chairman, was the King of Clubs and was never caught.</li>"
    "<li><b>Zabiba and the King.</b> A romance novel published anonymously in Iraq in 2000 and widely credited to Saddam Hussein.</li>"
    "<li><b>The statue.</b> A statue of Saddam was pulled down in Baghdad's Firdos Square on April 9, 2003, live on television.</li>"
    "<li><b>100 percent.</b> Saddam's 2002 referendum officially got 100% of the vote.</li>"
    "<li><b>The handshake.</b> Donald Rumsfeld did meet Saddam in Baghdad in December 1983 as a special envoy for President Reagan.</li>"
    "<li><b>Mission Accomplished.</b> President Bush gave a speech in front of that banner on the USS <i>Abraham Lincoln</i> on May 1, 2003.</li>"
    "<li><b>Swift Boats, windsurfing, “I actually did vote for the $87 billion before I voted against it,”</b> NASCAR dads, security moms, and the Orange alert were all very real parts of 2004.</li>"
    "</ul>"
    "<p>Actual 2004 result: Bush 286 electoral votes, Kerry 251, one faithless elector for John Edwards.</p>"
    "</div>"
)

CANDIDATES = [
    dict(pk=SAMUEL, first_name="Samuel", last_name="Howardstein", party="American Party of America",
         state="Ohio (he insists)", color_hex="#D4AF37", is_active=1, image="samuel",
         description=(
             "<p>Samuel Howardstein is a 67-year-old insurance salesman from Toledo, Ohio, who has definitely always been from Toledo, Ohio. "
             "He enjoys baseball, apple pie, and swimming across very large rivers, and he wrote the romance novel <i>Zabiba and the King of Ohio</i>.</p>"
             "<p>His campaign runs on a novelty disguise, a home-laminated driver's license, and a burning, entirely non-personal desire to see George W. Bush lose. "
             "Reporters say he bears a “striking resemblance” to a certain fugitive dictator. Samuel says the fugitive dictator is “much shorter” "
             "and “not nearly as American.”</p>"),
         victory="Samuel Howardstein has been elected President of the United States. Nobody suspects a thing. Probably.",
         loss="Samuel Howardstein has lost the election.",
         tie="Nobody has a majority. The election goes to the House of Representatives."),
    dict(pk=BUSH, first_name="George W.", last_name="Bush", party="Republican", state="Texas",
         color_hex="#C8302E", is_active=0, image="bush",
         description=(
             "<p>The incumbent President. He declared “Mission Accomplished” in May 2003 and has since begun to suspect the mission was not "
             "accomplished, especially after the spider hole turned out to be empty. He has a feeling he's seen that Howardstein fellow somewhere before.</p>")),
    dict(pk=KERRY, first_name="John", last_name="Kerry", party="Democratic", state="Massachusetts",
         color_hex="#2F5DA8", is_active=0, image="kerry",
         description=(
             "<p>Senator from Massachusetts, decorated Vietnam veteran and keen windsurfer. He voted for the $87 billion before he voted against it. "
             "People keep telling him the Howardstein campaign is “splitting the anti-Bush vote,” which he finds extremely annoying.</p>")),
    dict(pk=PLUNKETT, first_name="Gary", last_name="Plunkett", party="American Party of America", state="Ohio",
         color_hex="#D4AF37", is_active=0, image="plunkett", running_mate=True,
         rm_description=(
             "<p>Gary Plunkett owns Plunkett Motors in Dayton (“We Won't Be Undersold... Probably!”) and answered a classified ad in the "
             "<i>Dayton Daily News</i> that read: SEEKING AMERICAN. ANY AMERICAN. GOOD PAY.</p>"
             "<p>Gary is the only person on the ticket who is actually from Ohio, and he hasn't noticed anything unusual about his running mate. "
             "“Sam's a great guy. Real generous. Keeps asking me how Americans pronounce things.”</p>"
             "<p><b>Effect:</b> A moderate, genuine Midwesterner. Helps in Ohio and lowers the FBI Suspicion Meter.</p>")),
    dict(pk=DEWEY, first_name="Izzy", last_name="Dewey", party="American Party of America", state="Massachusetts",
         color_hex="#D4AF37", is_active=0, image="dewey", running_mate=True,
         rm_description=(
             "<p>Izzy Dewey is a proud Irish-American from Boston with bright red hair and a $1.99 novelty disguise identical to Samuel's, which he "
             "says is “a coincidence.” Asked which Irish county his family came from, he answered “Tikrit County.” He carries a single playing card, "
             "the King of Clubs, everywhere he goes.</p>"
             "<p><b>Effect:</b> A hardline anti-war, anti-PATRIOT Act voice. Energizes doves and civil libertarians, but raises the FBI Suspicion Meter.</p>")),
    dict(pk=WHITFIELD, first_name="Hank", last_name="Whitfield", party="American Party of America", state="Wisconsin",
         color_hex="#D4AF37", is_active=0, image="whitfield", running_mate=True,
         rm_description=(
             "<p>Hank “The Handlebar” Whitfield is a retired dairy farmer from Green Bay, Wisconsin, and a three-time national mustache champion. "
             "He joined the campaign the moment he saw Samuel's mustache across a crowded rally and has been trying to find out what products Samuel "
             "uses ever since. (“He won't tell me. Says it's a family secret. Says his family's from Ohio.”)</p>"
             "<p><b>Effect:</b> Anchors the mustache vote and brings the Wisconsin Cheeseheads along.</p>")),
]

RM_QUIPS = {
    PLUNKETT: "(Gary gave two thumbs up the entire time, then offered the press corps a great deal on a 2001 Buick LeSabre.)",
    DEWEY: "(Izzy nodded along vigorously. At one point his mustache fell into his coffee. He fished it out and put it back on without comment.)",
    WHITFIELD: "(Hank used the moment to demonstrate his championship handlebar-waxing technique. Three reporters took notes.)",
}


# ---------------------------------------------------------------------------
# Endings (run in the browser; `S`, `B`, `K` are the three candidate pks)
# ---------------------------------------------------------------------------

ENDINGS_JS = r"""
window.hsEnding = function (out, totv, aa, quickstats) {
  var e = campaignTrail_temp;
  function res(pk) {
    for (var i = 0; i < aa.length; i++) if (aa[i].candidate == pk) return aa[i];
    return { electoral_votes: 0, popular_votes: 0 };
  }
  var s = res(HS.SAMUEL), b = res(HS.BUSH), k = res(HS.KERRY);
  var pv = function (r) { return (100 * r.popular_votes / totv).toFixed(1); };
  var sus = HS.suspicion();
  var winner = aa[0].candidate;
  var tookDeal = e.player_answers.indexOf(HS.KERRY_DEAL) !== -1;
  var h = function (title, body) {
    return "<h3 style='margin-top:0'>" + title + "</h3>" + body +
      "<p style='font-size:90%'><i>Final FBI Suspicion Meter: " + sus + "%</i></p>";
  };

  if (out == "win") {
    if (sus >= HS.UNMASK) {
      return h("THE UNMASKING",
        "<p>You won, " + s.electoral_votes + " electoral votes to Bush's " + b.electoral_votes + ". The American people chose Samuel Howardstein.</p>" +
        "<p>On January 20th, 2005, a stiff wind blows across the West Front of the Capitol. Halfway through the oath, it catches the brim of your glasses and " +
        "carries the whole disguise, eyebrows, nose, mustache, the $1.99 price tag still attached, out over the crowd. It lands at the feet of Dick Cheney.</p>" +
        "<p>Chief Justice Rehnquist stops. Two hundred thousand people go quiet. Somewhere in the crowd, Linda Kowalski says, “Oh, for crying out loud.”</p>" +
        "<p>After a long pause the Chief Justice clears his throat. “Well... he did carry Ohio.” The constitutional crisis that follows will fill law-school syllabi for a century.</p>");
    }
    if (s.electoral_votes >= 450) {
      return h("THE MOTHER OF ALL LANDSLIDES",
        "<p>" + s.electoral_votes + " electoral votes. " + pv(s) + "% of the popular vote. Samuel Howardstein wins by a margin not seen since, as he puts it, " +
        "“the last time,” before quickly adding, “Reagan. I meant Reagan.”</p>" +
        "<p>Bob Sahhafferty announces from the podium that the President-elect won every state, every county and every household, " +
        "and that there are no Bush voters anywhere in America. For once, he is only a little bit wrong.</p>" +
        "<p>Within a hundred days every federal building has gold faucets, the Department of Homeland Security has adopted the mustache-coded alert system, " +
        "and a forty-foot statue stands on the National Mall. Nobody is allowed to pull it down.</p>");
    }
    return h("PRESIDENT HOWARDSTEIN",
      "<p>You won! " + s.electoral_votes + " electoral votes to " + b.electoral_votes + " for Bush and " + k.electoral_votes + " for Kerry. " +
      "Samuel Howardstein, insurance salesman from Toledo, will be the 44th President of the United States.</p>" +
      "<p>At 2:14 AM George W. Bush calls to concede. He is gracious, but just before hanging up he says, “Y'know, I feel like I know you from somewhere.” " +
      "You tell him, “Everyone says that, Mr. President. Must be the mustache.”</p>" +
      "<p>On Inauguration Day you tuck a single playing card into the outgoing President's pocket: the Ace of Spades. He does not get the joke. " +
      "Dick Cheney does, and spends the next four years trying to prove it, but nobody returns his calls.</p>" +
      "<p>The Oval Office is gold-plated by February.</p>");
  }

  if (out == "loss" && winner == HS.KERRY) {
    if (tookDeal) {
      return h("THE KINGMAKER",
        "<p>John Kerry wins with " + k.electoral_votes + " electoral votes. George W. Bush has been defeated.</p>" +
        "<p>Nobody knows about the deal: not the pundits, not the historians, not even Kerry, who mostly remembers \"some guy with a big mustache\" dropping out in October. " +
        "But you know. As the networks call Ohio, you raise a glass of date juice in a Toledo motel room.</p>" +
        "<p>The next morning a large banner goes up on the side of your ranch house: <b>MISSION ACCOMPLISHED</b>. This time, it is accurate.</p>");
    }
    return h("MISSION ACCOMPLISHED (SORT OF)",
      "<p>John Kerry wins the presidency with " + k.electoral_votes + " electoral votes. You took " + s.electoral_votes + " and " + pv(s) + "% of the vote, " +
      "most of it, the pundits agree, from people who would otherwise have voted for Bush.</p>" +
      "<p>George W. Bush is out of the White House. That was always the point, for reasons that are entirely political and not at all personal.</p>" +
      "<p>You hang a <b>MISSION ACCOMPLISHED</b> banner on your ranch house and retire to Boca Raton, where you win the condo shuffleboard championship three years running " +
      "and publish a sequel, <i>Zabiba and the King of Ohio II: Return to Toledo</i>. It is not well reviewed.</p>");
  }

  if (out == "loss") {
    if (quickstats[1] < 10) {
      return h("BACK TO THE HOLE",
        "<p>George W. Bush is re-elected with " + b.electoral_votes + " electoral votes. You finish with " + pv(s) + "% of the vote.</p>" +
        "<p>By midnight the campaign office is empty. Gary has gone back to selling Buicks. Bob Sahhafferty is on the steps outside telling one confused reporter " +
        "that you won all fifty states and that Bush has surrendered.</p>" +
        "<p>You drive back to the ranch, climb down into the tornado shelter, switch on the little fan, and open a candy bar. It's quiet down here. It always was.</p>");
    }
    return h("FOUR MORE YEARS",
      "<p>George W. Bush is re-elected with " + b.electoral_votes + " electoral votes. You won " + s.electoral_votes + " electoral votes and " + pv(s) + "% of the vote, " +
      (quickstats[1] > 27.4 ? "the best third-party showing in American history" :
       quickstats[1] > 18.9 ? "better than Ross Perot managed in 1992" : "respectable for a man in a $1.99 disguise") +
      ". Bob keeps calling it “a crushing victory.”</p>" +
      "<p>In his victory speech the President thanks his supporters, his family and \"the good people of Ohio,\" and then adds, unprompted, \"and I'd like to say to " +
      "Mr. Howardstein: we will find you.\" He means it as a joke. Probably.</p>" +
      "<p>Somewhere in Toledo, a man in a $1.99 disguise starts a list titled <i>HOWARDSTEIN 2008</i>.</p>");
  }

  // No electoral majority
  if (winner == HS.SAMUEL) {
    return h("ACTING PRESIDENT CHENEY",
      "<p>Nobody reaches 270, but you finish first: Samuel " + s.electoral_votes + ", Bush " + b.electoral_votes + ", Kerry " + k.electoral_votes + ". " +
      "Under the Twelfth Amendment the House of Representatives picks the President, one vote per state delegation.</p>" +
      "<p>You spend January lobbying congressmen with gold-plated toilets. Enough of them accept the toilets that the House deadlocks: " +
      "twenty-two ballots, no majority, and a lot of very nice bathrooms on Capitol Hill.</p>" +
      "<p>The Twentieth Amendment says that if no President has been chosen by Inauguration Day, the Vice President-elect acts as President, and the " +
      "Vice President is chosen by the Senate. The Republican Senate chooses Dick Cheney.</p>" +
      "<p>On January 20th, 2005, Dick Cheney is sworn in as Acting President of the United States. He has been waiting for this his entire life. " +
      "Bush has technically been defeated, which is something, you suppose.</p>");
  }
  return h("THE HOUSE DECIDES",
    "<p>Nobody reaches 270. Samuel " + s.electoral_votes + ", Bush " + b.electoral_votes + ", Kerry " + k.electoral_votes + ". Under the Twelfth Amendment the decision goes to " +
    "the House of Representatives, which votes one state delegation at a time.</p>" +
    "<p>You spend January lobbying congressmen with gold-plated toilets. Several accept the toilets. None of them accept the premise. The Republican House " +
    "re-elects George W. Bush on the first ballot" + (winner == HS.KERRY ? ", to John Kerry's enormous frustration, since he won more electoral votes than Bush did" : "") + ".</p>" +
    "<p>You demand a national referendum, “like the good old days,” and promise to accept the result as long as it is 100 percent. Nobody takes you up on it.</p>");
};
"""


# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

def model(name, pk, fields):
    return {"model": "campaign_trail." + name, "pk": pk, "fields": fields}


def samuel_start_issues():
    """Samuel's opening issue scores, averaged over the three running mates."""
    out = []
    for i in range(len(ISSUES)):
        rm_avg = sum(RM_ISSUES[r][i] for r in RUNNING_MATES) / len(RUNNING_MATES)
        out.append((CAND_ISSUES[SAMUEL][i] * 10 + rm_avg * 3) / 13)
    return out


def start_shares(abbr, bush, kerry):
    g = group_of(abbr)
    sam = SAMUEL_BASE + SAMUEL_START_MOD.get(g, 0) + SAMUEL_START_STATE.get(abbr, 0)
    rest = 1 - sam
    b = rest * bush / (bush + kerry)
    return {SAMUEL: sam, BUSH: b, KERRY: rest - b}


def build():
    images = {name: art(name) for name in
              ["samuel", "bush", "kerry", "plunkett", "dewey", "whitfield", "advisor", "election", "deadlock"]}

    # ----- states, issue scores, calibrated multipliers -----
    states_json, state_issue_json, mult_json = [], [], []
    sam_issues = samuel_start_issues()
    positions = {SAMUEL: sam_issues, BUSH: CAND_ISSUES[BUSH], KERRY: CAND_ISSUES[KERRY]}
    sid = mid = 1
    for i, (name, abbr, ev, pop, close, b, k) in enumerate(STATES):
        pk = STATE_PK[abbr]
        states_json.append(model("state", pk, {
            "name": name, "abbr": abbr, "electoral_votes": ev, "popular_votes": pop,
            "poll_closing_time": close, "winner_take_all_flg": 1, "election": ELECTION}))
        scores, weights = state_issue_scores(abbr, b, k)
        for issue in ISSUES:
            state_issue_json.append(model("state_issue_score", sid, {
                "state": pk, "issue": issue, "state_issue_score": round(scores[issue], 4),
                "weight": weights[issue]}))
            sid += 1
        target = start_shares(abbr, b, k)
        raw = {}
        for c in (SAMUEL, BUSH, KERRY):
            total = sum(issue_term(positions[c][j], scores[iss], weights[iss]) for j, iss in enumerate(ISSUES))
            assert total > 0.3, (abbr, c, total)
            raw[c] = target[c] / total
        # scale so the three multipliers average 1 in every state
        scale = 3 / sum(raw.values())
        for c in (SAMUEL, BUSH, KERRY):
            mult_json.append(model("candidate_state_multiplier", mid, {
                "candidate": c, "state": pk, "state_multiplier": round(raw[c] * scale, 5)}))
            mid += 1

    issues_json = []
    for issue in ISSUES:
        name, desc, stances = ISSUE_DEFS[issue]
        fields = {"name": name, "description": desc, "election": ELECTION}
        for n, st in enumerate(stances, 1):
            fields["stance_%d" % n] = st
            fields["stance_desc_%d" % n] = st
        issues_json.append(model("issue", issue, fields))

    cand_issue_json, rm_issue_json = [], []
    n = 1
    for c in (SAMUEL, BUSH, KERRY):
        for j, issue in enumerate(ISSUES):
            cand_issue_json.append(model("candidate_issue_score", n, {
                "candidate": c, "issue": issue, "issue_score": CAND_ISSUES[c][j]}))
            n += 1
    n = 1
    for r in RUNNING_MATES:
        for j, issue in enumerate(ISSUES):
            rm_issue_json.append(model("running_mate_issue_score", n, {
                "candidate": r, "issue": issue, "issue_score": RM_ISSUES[r][j]}))
            n += 1

    # ----- questions -----
    questions_json, answers_json, feedback_json = [], [], []
    glob_json, issue_json, state_json = [], [], []
    sus_map = {}
    counters = {"g": 1, "i": 1, "s": 1, "f": 1}

    def add_answer(qpk, apk, a):
        answers_json.append(model("answer", apk, {"question": qpk, "description": a.text}))
        feedback_json.append(model("answer_feedback", counters["f"], {
            "answer": apk, "candidate": SAMUEL,
            "answer_feedback": a.feedback + "<br><br>{{hs_sus}}"}))
        counters["f"] += 1
        for cand, val in ((SAMUEL, a.g), (BUSH, a.bush), (KERRY, a.kerry)):
            if val:
                glob_json.append(model("answer_score_global", counters["g"], {
                    "answer": apk, "candidate": SAMUEL, "affected_candidate": cand,
                    "global_multiplier": val}))
                counters["g"] += 1
        for issue, (score, imp) in a.iss.items():
            issue_json.append(model("answer_score_issue", counters["i"], {
                "answer": apk, "issue": issue, "issue_score": score, "issue_importance": imp}))
            counters["i"] += 1
        for cand, spec in ((SAMUEL, a.st), (BUSH, a.st_bush), (KERRY, a.st_kerry)):
            for abbr, val in sorted(spec.items()):
                state_json.append(model("answer_score_state", counters["s"], {
                    "answer": apk, "state": STATE_PK[abbr], "candidate": SAMUEL,
                    "affected_candidate": cand, "state_multiplier": round(val, 4)}))
                counters["s"] += 1
        if a.sus:
            sus_map[apk] = a.sus

    for qi, (text, answers) in enumerate(QUESTIONS, 1):
        qpk = QUESTION_PK_BASE + qi
        questions_json.append(model("question", qpk, {"priority": 1, "description": text, "likelihood": 1.0}))
        for ai, a in enumerate(answers, 1):
            add_answer(qpk, ANSWER_PK_BASE + qi * 10 + ai, a)

    fbi_text, fbi_answers = FBI_Q
    fbi_question = model("question", FBI_QUESTION, {"priority": 1, "description": fbi_text, "likelihood": 1.0})
    for ai, a in enumerate(fbi_answers, 1):
        add_answer(FBI_QUESTION, ANSWER_PK_BASE + 900 + ai, a)

    kerry_deal = ANSWER_PK_BASE + 19 * 10 + 3
    assert "Accept" in QUESTIONS[18][1][2].text

    # ----- candidates -----
    cand_json, rm_json = [], []
    for c in CANDIDATES:
        is_rm = c.get("running_mate", False)
        cand_json.append(model("candidate", c["pk"], {
            "first_name": c["first_name"], "last_name": c["last_name"], "election": ELECTION,
            "party": c["party"], "state": c["state"], "priority": 1,
            "description": c.get("description", ""),
            "color_hex": c["color_hex"], "secondary_color_hex": None, "is_active": c["is_active"],
            "image_url": images[c["image"]],
            "electoral_victory_message": c.get("victory", ""),
            "electoral_loss_message": c.get("loss", ""),
            "no_electoral_majority_message": c.get("tie", ""),
            "description_as_running_mate": c.get("rm_description"),
            "candidate_score": 1, "running_mate": is_rm}))
    for n, r in enumerate(RUNNING_MATES, 1):
        rm_json.append(model("running_mate", n, {"candidate": SAMUEL, "running_mate": r}))

    election_json = [model("election", ELECTION, {
        "year": 2000, "display_year": "2004", "summary": ELECTION_SUMMARY,
        "image_url": images["election"], "winning_electoral_vote_number": 270,
        "advisor_url": images["advisor"], "recommended_reading": RECOMMENDED_READING,
        "has_visits": 1, "no_electoral_majority_image": images["deadlock"]})]

    global_params = [model("global_parameter", 1, {
        "vote_variable": VOTE_VARIABLE, "max_swing": 0.12, "start_point": 0.94,
        "candidate_issue_weight": 10, "running_mate_issue_weight": 3,
        "issue_stance_1_max": -0.71, "issue_stance_2_max": -0.3, "issue_stance_3_max": -0.125,
        "issue_stance_4_max": 0.125, "issue_stance_5_max": 0.3, "issue_stance_6_max": 0.71,
        "global_variance": 0.01, "state_variance": 0.005, "question_count": len(QUESTIONS),
        "default_map_color_hex": "#C9C9C9", "no_state_map_color_hex": "#999999"})]

    def js(name, value):
        return "campaignTrail_temp.%s = %s;\n\n" % (name, json.dumps(value, indent=1, ensure_ascii=False))

    header = ("// Howardstein 2004: a Campaign Trail mod.\n"
              "// Generated by build.py. Edit that file instead of this one.\n\n")

    code1 = header + "// ===== CODE 1 =====\n\nRecReading = true;\n\n"
    code1 += js("global_parameter_json", global_params)
    code1 += js("election_json", election_json)
    code1 += js("temp_election_list", [{"id": ELECTION, "year": 2004, "is_premium": 0, "display_year": "2004"}])
    code1 += "campaignTrail_temp.credits = \"nafoas & Claude, on behalf of the Committee to Elect Samuel Howardstein (who are definitely Americans)\";\n\n"
    code1 += js("candidate_json", cand_json)
    code1 += js("running_mate_json", rm_json)
    code1 += js("opponents_default_json", [{"election": ELECTION, "candidates": [SAMUEL, BUSH, KERRY]}])
    code1 += js("opponents_weighted_json", [{"election": ELECTION, "candidates": [SAMUEL, BUSH, KERRY]}])
    code1 += "campaignTrail_temp.candidate_dropout_json = [];\n\n"
    code1 += ("HistHexcolour = [\"#C8302E\", \"#2F5DA8\", \"#D4AF37\", \"#999999\"];\n"
              "HistName = [\" George W. Bush\", \" John Kerry\", \" Samuel Howardstein\", \" Others\"];\n"
              "HistEV = [286, 251, 0, 1];\n"
              "HistPV = [\"62,040,610\", \"59,028,444\", \"0 (he was in a hole)\", \"1,226,231\"];\n"
              "HistPVP = [\"50.7%\", \"48.3%\", \"0.0%\", \"1.0%\"];\n")

    code2 = header + "// ===== CODE 2 =====\n\n"
    code2 += js("questions_json", questions_json)
    code2 += js("answers_json", answers_json)
    code2 += js("states_json", states_json)
    code2 += js("issues_json", issues_json)
    code2 += js("state_issue_score_json", state_issue_json)
    code2 += js("candidate_issue_score_json", cand_issue_json)
    code2 += js("running_mate_issue_score_json", rm_issue_json)
    code2 += js("candidate_state_multiplier_json", mult_json)
    code2 += js("answer_score_global_json", glob_json)
    code2 += js("answer_score_issue_json", issue_json)
    code2 += js("answer_score_state_json", state_json)
    code2 += js("answer_feedback_json", feedback_json)

    hs = {
        "SAMUEL": SAMUEL, "BUSH": BUSH, "KERRY": KERRY,
        "SUS": {str(k): v for k, v in sus_map.items()},
        "RM_SUS": {str(k): v for k, v in RM_SUS.items()},
        "RM_QUIPS": {str(k): v for k, v in RM_QUIPS.items()},
        "RM_BOOST": {str(r): {str(STATE_PK[a]): f for a, f in expand_factors(spec).items()} for r, spec in RM_BOOST.items()},
        "RM_STATE": {str(PLUNKETT): STATE_PK["OH"], str(DEWEY): STATE_PK["MA"], str(WHITFIELD): STATE_PK["WI"]},
        "FBI_CHECK_QUESTION": QUESTION_PK_BASE + FBI_CHECK_AFTER,
        "FBI_THRESHOLD": FBI_THRESHOLD,
        "UNMASK": UNMASK_THRESHOLD,
        "KERRY_DEAL": kerry_deal,
        "DEAL_SAMUEL": DEAL_SAMUEL,
        "DEAL_KERRY": DEAL_KERRY,
        "FBI_QUESTION": fbi_question,
    }
    code2 += "window.HS = " + json.dumps(hs, indent=1, ensure_ascii=False) + ";\n"
    code2 += RUNTIME_JS + ENDINGS_JS
    return code1, code2


RUNTIME_JS = r"""
(function () {
  var e = campaignTrail_temp;
  var rmId = Number(e.running_mate_id);
  var find = function (pk) {
    for (var i = 0; i < e.candidate_json.length; i++) if (e.candidate_json[i].pk == pk) return e.candidate_json[i];
    return null;
  };
  var me = find(HS.SAMUEL), rm = find(rmId) || find(Object.keys(HS.RM_STATE)[0]);

  // The engine uses the first running-mate issue score it finds for each
  // issue, so keep only the chosen running mate's scores.
  e.running_mate_issue_score_json = e.running_mate_issue_score_json.filter(function (x) {
    return x.fields.candidate == rm.pk;
  });

  // Running-mate regional strength
  var boost = HS.RM_BOOST[rm.pk] || {};
  e.candidate_state_multiplier_json.forEach(function (x) {
    if (x.fields.candidate == HS.SAMUEL && boost[x.fields.state]) {
      x.fields.state_multiplier = x.fields.state_multiplier * boost[x.fields.state];
    }
  });

  e.candidate_image_url = me.fields.image_url;
  e.running_mate_image_url = rm.fields.image_url;
  e.candidate_last_name = me.fields.last_name;
  e.running_mate_last_name = rm.fields.last_name;
  e.running_mate_state_id = HS.RM_STATE[rm.pk];

  // FBI Suspicion Meter
  HS.suspicion = function () {
    var total = HS.RM_SUS[rm.pk] || 0;
    for (var i = 0; i < e.player_answers.length; i++) total += HS.SUS[e.player_answers[i]] || 0;
    return Math.max(0, Math.min(100, total));
  };
  var getter = function (name, fn) {
    try { Object.defineProperty(window, name, { get: fn, configurable: true }); } catch (err) {}
  };
  getter("hs_sus_pct", function () { return HS.suspicion() + "%"; });
  getter("hs_sus", function () {
    var pct = HS.suspicion();
    var color = pct < 34 ? "#2e8b57" : pct < 67 ? "#d4a017" : "#c0392b";
    var label = pct < 34 ? "Nobody suspects a thing" : pct < 67 ? "Agents are asking questions" : "The FBI is parked outside";
    return "<span style='display:inline-block;font-size:90%'><b>FBI Suspicion Meter:</b> " +
      "<span style='display:inline-block;vertical-align:middle;width:120px;height:10px;border:1px solid #555;background:#eee'>" +
      "<span style='display:block;height:10px;width:" + pct + "%;background:" + color + "'></span></span> " +
      pct + "% (" + label + ")</span>";
  });
  getter("hs_rm_name", function () { return rm.fields.first_name + " " + rm.fields.last_name; });
  getter("hs_rm_first", function () { return rm.fields.first_name; });
  getter("hs_rm_quip", function () { return HS.RM_QUIPS[rm.pk] || ""; });

  e.cyoa = true;
  window.cyoAdventure = function (question) {
    // Taking Kerry's deal hands most of Samuel's support to Kerry.
    var last = e.player_answers[e.player_answers.length - 1];
    if (last == HS.KERRY_DEAL && !HS.dealDone) {
      HS.dealDone = true;
      e.candidate_state_multiplier_json.forEach(function (x) {
        if (x.fields.candidate == HS.SAMUEL) x.fields.state_multiplier *= HS.DEAL_SAMUEL;
        if (x.fields.candidate == HS.KERRY) x.fields.state_multiplier *= HS.DEAL_KERRY;
      });
    }
    // If suspicion is high after the Rumsfeld photo, the FBI pays a visit.
    if (question && question.pk == HS.FBI_CHECK_QUESTION && HS.suspicion() >= HS.FBI_THRESHOLD) {
      e.questions_json[e.question_number + 1] = HS.FBI_QUESTION;
    }
  };

  // Endings
  e.multiple_endings = true;
  important_info = "return window.hsEnding(out, totv, aa, quickstats);";
})();
"""


def main():
    code1, code2 = build()
    for name, text in (("code1.js", code1), ("code2.js", code2)):
        with open(os.path.join(HERE, name), "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote %s (%d KB)" % (name, len(text.encode()) // 1024))


if __name__ == "__main__":
    main()
