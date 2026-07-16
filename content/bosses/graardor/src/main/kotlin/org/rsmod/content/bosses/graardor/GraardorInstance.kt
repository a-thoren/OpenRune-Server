package org.rsmod.content.bosses.graardor

import jakarta.inject.Inject
import org.rsmod.api.instances.BossInstanceRegistry
import org.rsmod.api.instances.InstanceArea
import org.rsmod.api.instances.InstanceNpc
import org.rsmod.api.instances.InstanceScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.ScriptContext

/**
 * General Graardor lobby.
 *
 * Mirrors [org.rsmod.content.bosses.kbd.KingBlackDragonInstance]/
 * [org.rsmod.content.bosses.scurrius.ScurriusInstance]: the "public" encounter is a shared,
 * server-owned instance rather than the live overworld room, so it is joined the same way any
 * other public boss instance is - via [enterPublicRoom].
 *
 * The Bandos god-door carries the two entry options natively, matching the live game:
 * - **Op1 (open)** → [enterBandosPublic]: joins/creates the shared public instance, or - if the
 *   player is already inside one - leaves it (the door is copied into the instance region too).
 * - **Op2 (enter instance)** → [enterBandosPrivate]: create/join a private instance.
 * - **Op3 (peek)** → [peekBandosPublic]: reports how many players are in the shared public
 *   instance without entering it.
 *
 * Both routes are gated behind [REQUIRED_KILLCOUNT] Bandos kills, tracked in
 * `varbit.godwars_counter_bandos`. The interior Bandos altar is the framework exit object.
 */
class GraardorInstance
@Inject
constructor(registry: BossInstanceRegistry) : InstanceScript(registry) {

    override fun settingsRow(): String = "dbrow.instance_graardor"

    override fun area(): InstanceArea = INSTANCE

    override fun ScriptContext.configure() {
        onEnterObject { enterBandosPublic() }
        onOpLoc2(GOD_DOOR) { enterBandosPrivate() }
        onOpLoc3(GOD_DOOR) { peekBandosPublic() }
        onExitObject { defaultLeaveFlow() }
    }

    private suspend fun ProtectedAccess.enterBandosPublic() {
        if (manager.sessionForPlayer(player) != null) {
            defaultLeaveFlow()
            return
        }
        if (!hasRequiredKillcount()) return
        enterPublicRoom(INSTANCE)
    }

    private suspend fun ProtectedAccess.enterBandosPrivate() {
        if (!hasRequiredKillcount()) return
        defaultInstanceEntry()
    }

    private fun ProtectedAccess.peekBandosPublic() {
        val session = manager.sessionsForKey(key).firstOrNull { it.isServerOwned }
        val count = session?.occupants?.size ?: 0
        if (count == 0) {
            mes("The Bandos stronghold is currently empty.")
        } else {
            mes(
                "There ${if (count == 1) "is" else "are"} $count player${if (count == 1) "" else "s"} " +
                    "in the Bandos stronghold."
            )
        }
    }

    private suspend fun ProtectedAccess.hasRequiredKillcount(): Boolean {
        if (player.vars["varbit.godwars_counter_bandos"] < REQUIRED_KILLCOUNT) {
            mes(
                "You need a killcount of at least $REQUIRED_KILLCOUNT of Bandos' followers " +
                    "to enter."
            )
            return false
        }
        return true
    }

    private companion object {
        private const val REQUIRED_KILLCOUNT = 0

        private const val GOD_DOOR = "loc.godwars_dungeon_door_bandos"

        private val INSTANCE =
            InstanceArea.copyRegions(
                regionIds = listOf(11347),
                level = 2,
                npcSpawns =
                    listOf(
                        InstanceNpc("npc.godwars_bandos_avatar", CoordGrid(2872, 5358, 2)),
                        InstanceNpc("npc.godwars_sergeant_goblin1", CoordGrid(2866, 5358, 2)),
                        InstanceNpc("npc.godwars_sergeant_goblin2", CoordGrid(2872, 5352, 2)),
                        InstanceNpc("npc.godwars_sergeant_goblin3", CoordGrid(2868, 5362, 2)),
                    ),
            )
    }
}
