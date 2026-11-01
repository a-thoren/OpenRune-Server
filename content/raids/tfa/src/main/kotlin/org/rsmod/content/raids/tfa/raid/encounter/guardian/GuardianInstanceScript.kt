package org.rsmod.content.raids.tfa.raid.encounter.guardian

import jakarta.inject.Inject
import org.rsmod.api.instances.events.InstanceEndedEvent
import org.rsmod.api.instances.events.InstancePlayerJoinUnboundEvent
import org.rsmod.api.instances.events.InstancePlayerLeaveUnboundEvent
import org.rsmod.api.instances.events.instanceEventId
import org.rsmod.api.script.onEvent
import org.rsmod.content.raids.tfa.party.TfaLobby
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkEncounter
import org.rsmod.content.raids.tfa.raid.encounter.dreadnoth.DreadnothEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielEncounter
import org.rsmod.content.raids.tfa.raid.encounter.ocellus.OcellusEncounter
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthEncounter
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class GuardianInstanceScript
@Inject
constructor(private val encounter: GuardianEncounter, private val lobby: TfaLobby) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onEvent<InstanceEndedEvent>(instanceEventId(GuardianEncounter.KEY)) {
            encounter.close(instanceId)
            lobby.raidEnded(instanceId)
        }
        onEvent<InstancePlayerJoinUnboundEvent> {
            if (key in ROOM_KEYS) player.npcViewDistance = NPC_VIEW_DISTANCE
        }
        onEvent<InstancePlayerLeaveUnboundEvent> {
            if (key in ROOM_KEYS) player.npcViewDistance = null
        }
    }

    private companion object {
        val ROOM_KEYS =
            setOf(
                GuardianEncounter.KEY,
                OcellusEncounter.KEY,
                DreadnothEncounter.KEY,
                BorkEncounter.KEY,
                XarthEncounter.KEY,
                EzekielEncounter.KEY,
            )
        const val NPC_VIEW_DISTANCE = 48
    }
}
