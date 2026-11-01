package org.rsmod.content.raids.tfa.raid

import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.instances.InstanceId
import org.rsmod.api.instances.InstanceManager
import org.rsmod.api.instances.InstanceSession
import org.rsmod.api.instances.events.InstanceEndedEvent
import org.rsmod.api.instances.events.instanceEventId
import org.rsmod.api.player.hook.TeleportType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.statRestore
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc2
import org.rsmod.api.script.onOpNpc3
import org.rsmod.api.shops.Shops
import org.rsmod.api.shops.operation.ShopOperationMap
import org.rsmod.content.raids.tfa.party.TfaLobby
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkEncounter
import org.rsmod.content.raids.tfa.raid.encounter.dreadnoth.DreadnothEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielEncounter
import org.rsmod.content.raids.tfa.raid.encounter.guardian.GuardianEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ocellus.OcellusEncounter
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthEncounter
import org.rsmod.content.raids.tfa.raid.nexus.TfaNexus
import org.rsmod.content.raids.tfa.raid.nexus.TfaNexusStaff
import org.rsmod.content.raids.tfa.raid.nexus.TfaSupplyCredits
import org.rsmod.content.raids.tfa.raid.nexus.TfaSupplyShopOperations
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.map.Direction
import org.rsmod.game.queue.WorldQueueList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * The way through the Archive (tsps `Hub.FracturedArchive.js`, `docs/fractured-archive.md`): the
 * Guardian's fall opens section 1's doors in its room; each section's west or east door leads to a
 * boss; after a win the Alliance Navigator leads back to the Nexus beside the door taken, where the
 * next section's doors stand open; after the third section the Archive Door opens to Ocellus.
 *
 * Each room is its own instance, so the party moves as one: the first member through a door (or
 * after the Navigator) takes everyone in the room along. Live moves each player separately; the
 * spectator captures never showed how a party picks a side.
 */
@OptIn(InternalApi::class)
class TfaRaidProgressionScript
@Inject
constructor(
    private val lobby: TfaLobby,
    private val raids: TfaRaids,
    private val hud: TfaRaidHud,
    private val nexus: TfaNexus,
    private val deaths: TfaDeaths,
    private val manager: InstanceManager,
    private val launcher: ProtectedAccessLauncher,
    private val worldQueues: WorldQueueList,
    private val npcRepo: NpcRepository,
    private val collision: CollisionFlagMap,
    private val clock: MapClock,
    private val shops: Shops,
    private val operationMap: ShopOperationMap,
    private val supplyOperations: TfaSupplyShopOperations,
    private val guardian: GuardianEncounter,
    private val bork: BorkEncounter,
    private val ezekiel: EzekielEncounter,
    private val dreadnoth: DreadnothEncounter,
    private val xarth: XarthEncounter,
    private val ocellus: OcellusEncounter,
) : PluginScript() {
    override fun ScriptContext.startup() {
        operationMap.register(TfaSupplyShopOperations.CURRENCY, supplyOperations)

        onEvent<TfaRoomCleared> { cleared(instanceId, room) }
        onEvent<InstanceEndedEvent>(instanceEventId(TfaNexus.KEY)) {
            nexus.close(instanceId)
            lobby.raidEnded(instanceId)
        }

        for (door in TfaNexus.DOOR_LOCS) {
            onOpLoc1(door) { takeDoor(it.loc.coords, quick = false) }
            onOpLoc2(door) { takeDoor(it.loc.coords, quick = true) }
        }
        for (door in listOf(TfaNexus.OCELLUS_DOOR_CLOSED, TfaNexus.OCELLUS_DOOR_OPEN)) {
            onOpLoc1(door) { enterOcellus(it.loc.coords) }
            onOpLoc2(door) { enterOcellus(it.loc.coords) }
        }

        // tsps: "Follow" / "Quick-follow"; the op slots aren't captured, so every slot follows.
        onOpNpc1(NAVIGATOR) { follow() }
        onOpNpc2(NAVIGATOR) { follow() }
        onOpNpc3(NAVIGATOR) { follow() }

        // tsps: "Talk-to" is a messagebox and "Resupply" opens the shop (op slots unverified).
        onOpNpc1(TfaNexusStaff.QUARTERMASTER) { talkQuartermaster() }
        onOpNpc2(TfaNexusStaff.QUARTERMASTER) { resupply() }
        onOpNpc3(TfaNexusStaff.QUARTERMASTER) { resupply() }
    }

    // ---- winning a room ------------------------------------------------------------------------

    private fun cleared(id: InstanceId, key: String) {
        val party = lobby.raidParty(id) ?: return
        val raid = raids.of(party) ?: return
        val session = manager.sessionForId(id) ?: return
        val room = TfaRoom.byKey(key) ?: return
        // Captured: everyone, the dead too, back to full hitpoints and prayer.
        for (player in inside(raid, id)) {
            reviveIfDead(raid, player, session, room)
            player.statRestore("stat.hitpoints")
            player.statRestore("stat.prayer")
            TfaSupplyCredits.add(player, TfaSupplyCredits.PER_CHALLENGE)
        }
        if (room == TfaRoom.Guardian) {
            nexus.openFirstSection(raid, session)
            return
        }
        if (room.section != null) raid.cleared = maxOf(raid.cleared, room.section)
        val tile = room.navigator ?: return
        worldQueues.add(NAVIGATOR_DELAY) { callNavigator(id, tile) }
    }

    private fun reviveIfDead(
        raid: TfaRaid,
        player: Player,
        session: InstanceSession,
        room: TfaRoom,
    ) {
        if (player !in raid.dead) return
        raids.stopSpectating(raid, player)
        val arrival = room.arrival?.let { manager.resolveCoord(session, it) } ?: return
        PathingEntityCommon.telejump(player, collision, arrival)
    }

    /** The Alliance Navigator fades in three ticks after the boss falls (captured). */
    private fun callNavigator(id: InstanceId, tile: CoordGrid) {
        val session = manager.sessionForId(id) ?: return
        val at = manager.resolveCoord(session, tile) ?: return
        val npc = runCatching { Npc(NAVIGATOR, at) }.getOrNull() ?: return
        npc.respawnDir = Direction.East
        npcRepo.add(npc, Int.MAX_VALUE)
        manager.attachNpc(id, npc)
    }

    // ---- the doors -----------------------------------------------------------------------------

    private suspend fun ProtectedAccess.takeDoor(coords: CoordGrid, quick: Boolean) {
        val (raid, session) = raidHere() ?: return
        val (section, side) = nexus.doorAt(session, coords) ?: return
        if (!doorOpen(raid, session, section)) {
            mes(NOT_OPEN)
            return
        }
        val taken = raid.path[section]
        if (taken != null && taken != side) {
            mes(OTHER_SIDE)
            return
        }
        val room = TfaRoom.behind(section, side)
        if (!room.built) {
            mes(SEALED)
            return
        }
        if (!quick && !choice2("Yes.", true, "No.", false, title = "Enter this chamber?")) return
        if (raid.wiping || manager.sessionForPlayer(player)?.id != session.id) return
        if (raid.path[section].let { it != null && it != side }) return
        if (raid.path[section] == null) {
            raid.path[section] = side
            nexus.closeOtherSide(raid, section, side)
        }
        moveParty(raid, session.id, room)
    }

    /** The doors of the section after the last one won; section 1 waits for the Guardian. */
    private fun doorOpen(raid: TfaRaid, session: InstanceSession, section: Int): Boolean {
        if (section != raid.cleared + 1) return false
        if (raid.room != TfaRoom.Guardian) return raid.room == TfaRoom.Nexus
        return guardian.of(session.id)?.finished == true
    }

    private fun ProtectedAccess.enterOcellus(coords: CoordGrid) {
        val (raid, session) = raidHere() ?: return
        if (!nexus.isOcellusDoor(session, coords)) return
        if (raid.room != TfaRoom.Nexus || raid.cleared < TfaNexus.SECTIONS.size) {
            mes(NOT_OPEN)
            return
        }
        moveParty(raid, session.id, TfaRoom.Ocellus)
    }

    // ---- the Navigator and the Quartermaster ---------------------------------------------------

    private fun ProtectedAccess.follow() {
        val (raid, session) = raidHere() ?: return
        val section = raid.room.section ?: return
        if (deaths.fight(session.id)?.finished != true) return
        raid.path.putIfAbsent(section, raid.room.side ?: TfaSide.East)
        moveParty(raid, session.id, TfaRoom.Nexus)
    }

    private suspend fun ProtectedAccess.talkQuartermaster() {
        mesbox(
            "The Quartermaster trades supplies for the Supply Credits your party has earned in " +
                "the Archive."
        )
    }

    private fun ProtectedAccess.resupply() {
        val (raid, _) = raidHere() ?: return
        val stock =
            if (raid.deaths > 0) TfaSupplyShopOperations.SHOP_FAILURE
            else TfaSupplyShopOperations.SHOP
        shops.open(
            player = player,
            title = "Alliance Supplies",
            shopInv = stock,
            buyPercentage = 100.0,
            sellPercentage = 100.0,
            changePercentage = 0.0,
            currency = TfaSupplyShopOperations.CURRENCY,
        )
    }

    // ---- moving the party ----------------------------------------------------------------------

    private fun ProtectedAccess.raidHere(): Pair<TfaRaid, InstanceSession>? {
        val session = manager.sessionForPlayer(player) ?: return null
        val party = lobby.raidParty(session.id) ?: return null
        val raid = raids.of(party) ?: return null
        if (raid.wiping || raid.roomId != session.id || player in raid.dead) return null
        return raid to session
    }

    private fun inside(raid: TfaRaid, id: InstanceId): List<Player> =
        raid.party.members.filter { manager.sessionForPlayer(it)?.id == id }

    private fun moveParty(raid: TfaRaid, from: InstanceId, room: TfaRoom) {
        val party = raid.party
        val inside = inside(raid, from)
        if (inside.isEmpty()) return
        val owner = party.leader?.takeIf { it in inside } ?: inside.first()
        val old = manager.sessionForId(from) ?: return
        val back = raid.room
        for (player in inside) raids.stopSpectating(raid, player)

        lateinit var exits: Map<Player, CoordGrid>
        val opened =
            lobby.moving(party) {
                exits = inside.associateWith { manager.leave(it, old, clock.cycle) }
                open(room, owner, raid, back)?.also { (fight, _) ->
                    lobby.bindRaid(fight.session.id, party)
                }
            }
        if (opened == null) {
            lobby.endRaid(party)
            for ((player, exit) in exits) {
                player.mes("The Archive refuses to open the next chamber.")
                launcher.launchLenient(player) { telejump(exit, TeleportType.Exempt) }
            }
            return
        }
        val (fight, enter) = opened
        raid.room = room
        raid.roomId = fight.session.id
        hud.track(fight.session.id)
        for (player in inside) {
            if (player === owner) {
                send(player, enter, fight)
                continue
            }
            val joined = manager.join(player, fight.session, clock.cycle, forceAccess = true)
            if (joined is InstanceManager.Result.Joined) send(player, enter, fight)
        }
    }

    private fun send(player: Player, enter: CoordGrid, fight: TfaFight) {
        launcher.launchLenient(player) {
            telejump(enter, TeleportType.Exempt)
            manager.finalizeEntry(player, fight.session, clock.cycle)
            mes("The Archive shifts around you...")
        }
    }

    private fun open(
        room: TfaRoom,
        owner: Player,
        raid: TfaRaid,
        from: TfaRoom,
    ): Pair<TfaFight, CoordGrid>? =
        when (room) {
            TfaRoom.Bork ->
                (bork.open(owner) as? BorkEncounter.Opened.Room)?.let { it.fight to it.enter }
            TfaRoom.Ezekiel ->
                (ezekiel.open(owner) as? EzekielEncounter.Opened.Room)?.let { it.fight to it.enter }
            TfaRoom.Dreadnoth ->
                (dreadnoth.open(owner) as? DreadnothEncounter.Opened.Room)?.let {
                    it.fight to it.enter
                }
            TfaRoom.Xarth ->
                (xarth.open(owner) as? XarthEncounter.Opened.Room)?.let { it.fight to it.enter }
            TfaRoom.Ocellus ->
                (ocellus.open(owner) as? OcellusEncounter.Opened.Room)?.let { it.fight to it.enter }
            TfaRoom.Nexus -> {
                val section = from.section ?: return null
                val side = raid.path[section] ?: from.side ?: return null
                nexus.open(owner, raid, section, side)
            }
            else -> null
        }

    private companion object {
        const val NAVIGATOR = "npc.tfa_room_navigator"
        const val NAVIGATOR_DELAY = 3

        const val NOT_OPEN = "That way isn't open yet."
        const val OTHER_SIDE = "Your party has already gone the other way."
        const val SEALED = "This chamber is sealed."
    }
}
