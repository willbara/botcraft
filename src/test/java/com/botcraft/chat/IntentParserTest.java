package com.botcraft.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentParserTest {
	private static final List<String> NAMES = List.of("Bob", "Alice_2", "herobrine");

	private static IntentParser.Parsed parse(String msg) {
		return IntentParser.parse(msg, NAMES);
	}

	private static Intent.Type type(String msg) {
		return parse(msg).intent().type();
	}

	@Test
	void addressing() {
		assertEquals(List.of("Bob"), parse("Bob, follow me").addressed());
		assertEquals(List.of("Bob"), parse("@bob stop").addressed());
		assertEquals(List.of("Alice_2"), parse("alice_2 come here").addressed());
		assertEquals(List.of(IntentParser.ALL), parse("everyone follow me").addressed());
		assertEquals(List.of(IntentParser.ALL), parse("all of you, stop").addressed());
		assertTrue(parse("follow me").addressed().isEmpty());
		assertTrue(parse("gather all the logs").addressed().isEmpty());
		assertTrue(parse("bobsled time").addressed().isEmpty());
		// Typos in a direct address still reach the right companion.
		assertEquals(List.of("herobrine"), parse("herobrin, follow me").addressed());
		assertEquals(List.of("Alice_2"), parse("alice_3 come here").addressed());
		assertTrue(parse("i saw a bobcat").addressed().isEmpty());
		// The reported bug: an order for Bob must not address herobrine.
		assertEquals(List.of("Bob"), parse("bob, go mine logs").addressed());
	}

	@Test
	void directAddress() {
		assertTrue(parse("Bob, what's up").direct());
		assertTrue(parse("thanks bob").direct());
		assertTrue(parse("hey bob how are you").direct());
		assertFalse(parse("i think bob is lost in the cave somewhere").direct());
	}

	@Test
	void standingOrders() {
		assertEquals(Intent.Type.FOLLOW, type("Bob follow me"));
		assertEquals(Intent.Type.FOLLOW, type("bob let's go"));
		assertEquals(Intent.Type.STOP, type("Bob stop"));
		assertEquals(Intent.Type.STOP, type("bob stop following me"));
		assertEquals(Intent.Type.COME_HERE, type("Bob come here"));
		assertEquals(Intent.Type.COME_HERE, type("bob get over here"));
		assertEquals(Intent.Type.DEFEND, type("bob, defend me"));
		assertEquals(Intent.Type.DEFEND, type("bob help me fight"));
		assertEquals(Intent.Type.CANCEL, type("bob never mind"));
		assertEquals(Intent.Type.CANCEL, type("bob forget it"));
	}

	@Test
	void questions() {
		assertEquals(Intent.Type.WHERE, type("bob where are you?"));
		assertEquals(Intent.Type.WHERE, type("bob what are your coords"));
		assertEquals(Intent.Type.INVENTORY, type("bob what do you have"));
		assertEquals(Intent.Type.INVENTORY, type("bob what's in your inventory"));
		assertEquals(Intent.Type.STATS, type("bob how many logs have you chopped"));
		assertEquals(Intent.Type.HELP, type("bob what can you do"));
		assertEquals(Intent.Type.STATUS, type("bob what are you doing?"));
		Intent found = parse("bob have you seen any diamonds").intent();
		assertEquals(Intent.Type.FOUND, found.type());
		assertEquals("diamonds", found.item());
		assertEquals("iron", parse("bob where is the iron").intent().item());
		assertEquals(Intent.Type.GREETING, type("hi bob"));
		assertEquals(Intent.Type.THANKS, type("thanks bob"));
		assertEquals(Intent.Type.UNKNOWN, type("bob the weather is nice"));
		assertEquals(Intent.Type.JOKE, type("bob tell me a joke"));
		assertEquals(Intent.Type.WHO, type("who are you bob"));
		assertEquals(Intent.Type.TIME, type("bob what time is it"));
		assertEquals(Intent.Type.COMPLIMENT, type("good job bob"));
		assertEquals(Intent.Type.INSULT, type("bob you suck"));
		assertEquals(Intent.Type.BYE, type("bye bob"));
		assertEquals(Intent.Type.LOVE, type("i love you bob"));
		Intent fav = parse("bob what's your favorite food").intent();
		assertEquals(Intent.Type.FAVORITE, fav.type());
		assertEquals("food", fav.item());
		Intent op = parse("bob do you like creepers?").intent();
		assertEquals(Intent.Type.OPINION, op.type());
		assertEquals("creepers", op.item());
	}

	@Test
	void acquisition() {
		Intent i = parse("Bob gather 10 logs").intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("logs", i.item());
		assertEquals(10, i.count());

		i = parse("bob go chop some trees").intent();
		assertEquals(Intent.Type.GATHER, i.type());
		assertEquals("trees", i.item());
		assertEquals(-1, i.count());

		i = parse("bob get me a stack of wood").intent();
		assertEquals("wood", i.item());
		assertEquals(64, i.count());

		i = parse("bob mine for coal").intent();
		assertEquals(Intent.Type.MINE, i.type());
		assertEquals("coal", i.item());

		i = parse("bob go mining").intent();
		assertEquals(Intent.Type.MINE, i.type());
		assertEquals("", i.item());

		i = parse("bob craft a wooden pickaxe please").intent();
		assertEquals(Intent.Type.CRAFT, i.type());
		assertEquals("wooden pickaxe", i.item());
		assertEquals(1, i.count());

		i = parse("bob make 4 torches").intent();
		assertEquals(Intent.Type.CRAFT, i.type());
		assertEquals("torches", i.item());
		assertEquals(4, i.count());

		i = parse("bob bring me 5 iron").intent();
		assertEquals(Intent.Type.BRING, i.type());
		assertEquals("iron", i.item());
		assertEquals(5, i.count());

		i = parse("bob give me 5 logs").intent();
		assertEquals(Intent.Type.GIVE, i.type());
		assertEquals("logs", i.item());
		assertEquals(5, i.count());
		assertEquals("all", parse("bob give me everything").intent().item());
	}

	@Test
	void chainedOrders() {
		IntentParser.Parsed p = parse("bob, go get logs and then mine for coal");
		assertEquals(2, p.intents().size());
		assertEquals(Intent.Type.GATHER, p.intents().get(0).type());
		assertEquals(Intent.Type.MINE, p.intents().get(1).type());
		assertEquals("coal", p.intents().get(1).item());

		p = parse("bob chop 10 logs, craft a stone pickaxe and bring me the coal");
		assertEquals(List.of(Intent.Type.GATHER, Intent.Type.CRAFT, Intent.Type.BRING), p.intents().stream().map(Intent::type).toList());

		p = parse("bob come here and give me the logs");
		assertEquals(List.of(Intent.Type.COME_HERE, Intent.Type.GIVE), p.intents().stream().map(Intent::type).toList());

		p = parse("bob also mine 5 iron");
		assertTrue(p.append());
		assertEquals(Intent.Type.MINE, p.intent().type());

		assertFalse(parse("bob mine 5 iron").append());
	}

	@Test
	void huntAndCook() {
		Intent i = parse("bob go hunting").intent();
		assertEquals(Intent.Type.HUNT, i.type());
		i = parse("bob kill 2 cows").intent();
		assertEquals(Intent.Type.HUNT, i.type());
		assertEquals("cows", i.item());
		assertEquals(2, i.count());
		assertEquals("food", parse("bob get us some food").intent().item());
		i = parse("bob cook the beef").intent();
		assertEquals(Intent.Type.SMELT, i.type());
		assertEquals("beef", i.item());
		i = parse("bob smelt 5 iron").intent();
		assertEquals(Intent.Type.SMELT, i.type());
		assertEquals("iron", i.item());
		assertEquals(5, i.count());
		// Defending still wins over hunting words.
		assertEquals(Intent.Type.DEFEND, parse("bob kill it!").intent().type());
	}

	@Test
	void targetsAndPurpose() {
		IntentParser.Parsed p = IntentParser.parse("alex go to steve", List.of("alex", "steve"), List.of());
		assertEquals(List.of("alex"), p.addressed());
		assertEquals(Intent.Type.COME_HERE, p.intent().type());
		assertEquals("steve", p.intent().target());

		p = IntentParser.parse("steve, give alex your logs", List.of("alex", "steve"), List.of());
		assertEquals(List.of("steve"), p.addressed());
		assertEquals(Intent.Type.GIVE, p.intent().type());
		assertEquals("alex", p.intent().target());
		assertEquals("logs", p.intent().item());

		p = IntentParser.parse("bob follow steve", NAMES, List.of("steve"));
		assertEquals(Intent.Type.FOLLOW, p.intent().type());
		assertEquals("steve", p.intent().target());

		// The planner works out the means; keep the purpose.
		p = parse("bob make a furnace to smelt iron and then make an iron pickaxe");
		assertEquals(List.of(Intent.Type.SMELT, Intent.Type.CRAFT), p.intents().stream().map(Intent::type).toList());
		assertEquals("iron pickaxe", p.intents().get(1).item());
		p = parse("bob craft iron to make iron pickaxes");
		assertEquals(Intent.Type.CRAFT, p.intent().type());
		assertEquals("iron pickaxe", p.intent().item());
	}

	@Test
	void typosSlangAndFallback() {
		assertEquals(Intent.Type.FOLLOW, type("bob folow me"));
		Intent i = parse("bob mien some coal").intent();
		assertEquals(Intent.Type.MINE, i.type());
		assertEquals("coal", i.item());
		assertEquals(Intent.Type.CRAFT, type("bob crfat a stone pickaxe"));
		assertEquals(Intent.Type.WHERE, type("bob where r u"));
		assertEquals(Intent.Type.GIVE, type("bob gimme the logs"));
		assertEquals(Intent.Type.FOLLOW, type("bob could you start following me around"));
		assertEquals(Intent.Type.GATHER, type("bob maybe some chopping would be good"));
	}

	@Test
	void freeMode() {
		assertEquals(Intent.Type.FREE, parse("bob, do your own thing").intent().type());
		assertEquals(Intent.Type.FREE, parse("bob go explore").intent().type());
		assertEquals(Intent.Type.FREE, parse("you're free bob").intent().type());
	}

	@Test
	void homeAndSleep() {
		assertEquals(Intent.Type.HOME_SET, type("bob, this is home"));
		assertEquals(Intent.Type.HOME_SET, type("bob set this as home"));
		assertEquals(Intent.Type.HOME_GO, type("bob go home"));
		assertEquals(Intent.Type.HOME_GO, type("bob come home"));
		assertEquals(Intent.Type.HOME_GO, type("bob come back home"));
		var chained = IntentParser.parse("everyone make an iron pickaxe and then come home", List.of("bob")).intents();
		assertEquals(Intent.Type.HOME_GO, chained.get(chained.size() - 1).type());
		assertEquals(Intent.Type.SLEEP, type("bob go to sleep"));
	}

	@Test
	void gearOrders() {
		var steps = IntentParser.parse("bob go mine diamonds, make diamond armor, and then come home", List.of("bob")).intents();
		assertEquals(3, steps.size(), steps.toString());
		assertEquals(Intent.Type.MINE, steps.get(0).type());
		assertEquals("diamonds", steps.get(0).item());
		assertEquals(Intent.Type.CRAFT, steps.get(1).type());
		assertEquals("diamond armor", steps.get(1).item());
		assertEquals(Intent.Type.HOME_GO, steps.get(2).type());
		var armour = IntentParser.parse("bob make a full set of iron armour", List.of("bob")).intents();
		assertEquals(Intent.Type.CRAFT, armour.get(0).type());
		assertEquals("full set of iron armour", armour.get(0).item());
		var gear = IntentParser.parse("bob get diamond gear", List.of("bob")).intents();
		assertEquals("diamond gear", gear.get(0).item());
	}

	@Test
	void flexibleOrders() {
		assertEquals(Intent.Type.STATUS, type("zob what's your task"));
		assertEquals(Intent.Type.STATUS, type("dog what you doing"));
		assertEquals(Intent.Type.STATUS, type("bob what are you working on"));
		assertEquals(Intent.Type.STATUS, type("bob whats the plan"));
		assertEquals(Intent.Type.BRING, type("bob i need some iron"));
		assertEquals(Intent.Type.BRING, type("bob I'm hungry"));
		assertEquals(Intent.Type.GATHER, type("bob we need coal"));
		assertEquals(Intent.Type.HUNT, type("bob we're out of food"));
		assertEquals(Intent.Type.GATHER, type("bob wood"));
		assertEquals(Intent.Type.GATHER, type("bob some wood please"));
		assertEquals(Intent.Type.CRAFT, type("bob iron pickaxe please"));
		assertEquals(Intent.Type.GATHER, type("bob grab some stone"));
		assertEquals(10, IntentParser.parse("bob could you grab me 10 logs", List.of("bob")).intent().count());
		assertEquals("sword", IntentParser.parse("bob make yourself a sword", List.of("bob")).intent().item());
		assertEquals(Intent.Type.CANCEL, type("bob drop everything and come here"));
		var after = IntentParser.parse("bob after you get logs make a crafting table", List.of("bob")).intents();
		assertEquals(Intent.Type.GATHER, after.get(0).type());
		assertEquals(Intent.Type.CRAFT, after.get(1).type());
		assertEquals("food", IntentParser.parse("bob get me something to eat", List.of("bob")).intent().item());
	}

	@Test
	void everyoneQuestions() {
		assertEquals(Intent.Type.STATUS, type("everyone ok?"));
		assertEquals(Intent.Type.STATUS, type("how's everyone doing"));
		assertEquals(Intent.Type.STATUS, type("what's everyone doing?"));
		assertEquals(Intent.Type.STATUS, type("everyone what are you doing"));
		assertEquals(Intent.Type.GATHER, type("everyone get some wood"));
		assertEquals(Intent.Type.FOLLOW, type("everyone follow me"));
		assertEquals(Intent.Type.FREE, type("bob go do something"));
	}

	@Test
	void baseStorage() {
		assertEquals(Intent.Type.CHEST_SET, type("everyone, this is our chest"));
		assertEquals(Intent.Type.CHEST_SET, type("bob use this chest"));
		assertEquals(Intent.Type.DEPOSIT, type("bob put your stuff away"));
		assertEquals(Intent.Type.DEPOSIT, type("everyone empty your inventory"));
		assertEquals(Intent.Type.DEPOSIT, type("bob store your things in the chest"));
		assertEquals(Intent.Type.STOCK, type("what's in the chests?"));
		assertEquals(Intent.Type.STOCK, type("bob what do we have"));
		assertEquals(Intent.Type.INVENTORY, type("bob what do you have"));
		assertEquals(Intent.Type.HOME_SET, type("everyone this is home"));
	}

	@Test
	void chestPurposes() {
		var ores = IntentParser.parse("everyone this chest is for ores", List.of("bob")).intent();
		assertEquals(Intent.Type.CHEST_SET, ores.type());
		assertEquals("ORES", ores.item());
		assertEquals("FOOD", IntentParser.parse("bob food goes in this chest", List.of("bob")).intent().item());
		assertEquals("WOOD", IntentParser.parse("bob put the wood here", List.of("bob")).intent().item());
		assertEquals("GEAR", IntentParser.parse("bob this is the tool chest", List.of("bob")).intent().item());
		assertEquals("", IntentParser.parse("bob this is our chest", List.of("bob")).intent().item());
		assertEquals(Intent.Type.COME_HERE, type("everyone come here"));
		assertFalse(type("everyone go here") == Intent.Type.CHEST_SET);
	}

	@Test
	void dance() {
		assertEquals(Intent.Type.DANCE, type("everyone dance"));
		assertEquals(Intent.Type.DANCE, type("bob dance!"));
		assertEquals(Intent.Type.DANCE, type("bob bust a move"));
		assertEquals(Intent.Type.DANCE, type("everyone dance party"));
	}

	@Test
	void scatter() {
		assertEquals(Intent.Type.SCATTER, type("everyone run away"));
		assertEquals(Intent.Type.SCATTER, type("bob run away!"));
		assertEquals(Intent.Type.SCATTER, type("everyone scatter"));
		assertEquals(Intent.Type.SCATTER, type("spread out guys"));
		assertEquals(Intent.Type.SCATTER, type("everyone split up"));
		assertEquals(Intent.Type.FREE, type("bob do your own thing"));
	}

	@Test
	void colonyWants() {
		Intent i = parse("the base needs 64 coal").intent();
		assertEquals(Intent.Type.COLONY_WANT, i.type());
		assertEquals("coal", i.item());
		assertEquals(64, i.count());
		i = parse("keep 20 glass in the chests").intent();
		assertEquals(Intent.Type.COLONY_WANT, i.type());
		assertEquals("glass", i.item());
		assertEquals(20, i.count());
		i = parse("we have enough cobblestone").intent();
		assertEquals(Intent.Type.COLONY_WANT, i.type());
		assertEquals("cobblestone", i.item());
		assertEquals(0, i.count());
		assertEquals(Intent.Type.COLONY_WANT, type("stop collecting torches"));
		assertEquals(Intent.Type.COLONY_WANT, type("everyone, the colony needs more food"));
		assertEquals(Intent.Type.GATHER, type("everyone, we need coal"));
		assertEquals(Intent.Type.GATHER, type("we need coal"));
	}

	@Test
	void lighting() {
		assertEquals(Intent.Type.LIGHT, type("everyone light up the base"));
		assertEquals(Intent.Type.LIGHT, type("bob place some torches"));
		assertEquals(Intent.Type.LIGHT, type("bob it's dark around here"));
	}

	@Test
	void oneVerbSeveralThings() {
		var p = IntentParser.parse("everyone make a iron pickaxe and iron armor", List.of("bob")).intents();
		assertEquals(2, p.size(), p.toString());
		assertEquals("iron pickaxe", p.get(0).item());
		assertEquals("iron armor", p.get(1).item());
		var q = IntentParser.parse("bob bring me logs and coal", List.of("bob")).intents();
		assertEquals(2, q.size(), q.toString());
	}

	@Test
	void stacksAndHalves() {
		assertEquals(32, IntentParser.parse("bob get half a stack of logs", List.of("bob")).intent().count());
		assertEquals(128, IntentParser.parse("bob mine two stacks of cobblestone", List.of("bob")).intent().count());
		assertEquals(96, IntentParser.parse("bob get a stack and a half of logs", List.of("bob")).intent().count());
		assertEquals(160, IntentParser.parse("bob get 2 and a half stacks of logs", List.of("bob")).intent().count());
		assertEquals("logs", IntentParser.parse("bob get half a stack of logs", List.of("bob")).intent().item());
		assertEquals(5, IntentParser.parse("bob get 5 logs", List.of("bob")).intent().count());
	}

	@Test
	void trustQuestions() {
		Intent i = IntentParser.parse("bob do you trust me?", List.of("bob")).intent();
		assertEquals(Intent.Type.TRUST, i.type());
		assertEquals("", i.target());
		assertEquals(Intent.Type.TRUST, IntentParser.parse("bob are we cool", List.of("bob")).intent().type());
		assertEquals(Intent.Type.OPINION, IntentParser.parse("bob do you like cake", List.of("bob")).intent().type());
	}

	@Test
	void restIsWhatWasSaidWithoutTheName() {
		assertEquals("make it 30", IntentParser.parse("bob, make it 30", List.of("bob")).rest());
	}

	@Test
	void farming() {
		assertEquals(Intent.Type.FARM, IntentParser.parse("bob go farm", List.of("bob")).intent().type());
		assertEquals(Intent.Type.FARM, IntentParser.parse("bob, harvest the wheat please", List.of("bob")).intent().type());
		assertEquals(Intent.Type.FARM, IntentParser.parse("bob tend the crops", List.of("bob")).intent().type());
		assertEquals(Intent.Type.GATHER, IntentParser.parse("bob get some wheat", List.of("bob")).intent().type());
	}

	@Test
	void fishing() {
		assertEquals(Intent.Type.FISH, IntentParser.parse("bob go fishing", List.of("bob")).intent().type());
		assertEquals(Intent.Type.FISH, IntentParser.parse("bob, catch some fish", List.of("bob")).intent().type());
		assertEquals(Intent.Type.FISH, IntentParser.parse("bob get us some salmon", List.of("bob")).intent().type());
		assertEquals(Intent.Type.FISH, IntentParser.parse("bob catch 5 fish", List.of("bob")).intent().type());
		assertEquals(5, IntentParser.parse("bob catch 5 fish", List.of("bob")).intent().count());
	}
}
