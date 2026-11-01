package org.rsmod.content.raids.tfa

import org.rsmod.api.death.NpcAttackValidateHook
import org.rsmod.api.death.PlayerDeathCleanupHook
import org.rsmod.api.death.PlayerRespawnHook
import org.rsmod.api.npc.hit.NpcHitpointsFloorHook
import org.rsmod.content.raids.tfa.raid.TfaDeathCleanupHook
import org.rsmod.content.raids.tfa.raid.TfaRespawnHook
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkAttackHook
import org.rsmod.content.raids.tfa.raid.encounter.bork.BorkHpFloor
import org.rsmod.content.raids.tfa.raid.encounter.dreadnoth.DreadnothAttackHook
import org.rsmod.content.raids.tfa.raid.encounter.dreadnoth.DreadnothHpFloor
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielAttackHook
import org.rsmod.content.raids.tfa.raid.encounter.ezekiel.EzekielHpFloor
import org.rsmod.content.raids.tfa.raid.encounter.guardian.GuardianAttackHook
import org.rsmod.content.raids.tfa.raid.encounter.guardian.GuardianHpFloor
import org.rsmod.content.raids.tfa.raid.encounter.ocellus.OcellusAttackHook
import org.rsmod.content.raids.tfa.raid.encounter.ocellus.OcellusShieldFloor
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthAttackHook
import org.rsmod.content.raids.tfa.raid.encounter.xarth.XarthHpFloor
import org.rsmod.plugin.module.PluginModule

class TfaModule : PluginModule() {
    override fun bind() {
        addSetBinding<NpcHitpointsFloorHook>(OcellusShieldFloor::class.java)
        addSetBinding<NpcHitpointsFloorHook>(GuardianHpFloor::class.java)
        addSetBinding<NpcHitpointsFloorHook>(DreadnothHpFloor::class.java)
        addSetBinding<NpcHitpointsFloorHook>(BorkHpFloor::class.java)
        addSetBinding<NpcHitpointsFloorHook>(XarthHpFloor::class.java)
        addSetBinding<NpcHitpointsFloorHook>(EzekielHpFloor::class.java)
        addSetBinding<NpcAttackValidateHook>(OcellusAttackHook::class.java)
        addSetBinding<NpcAttackValidateHook>(GuardianAttackHook::class.java)
        addSetBinding<NpcAttackValidateHook>(DreadnothAttackHook::class.java)
        addSetBinding<NpcAttackValidateHook>(BorkAttackHook::class.java)
        addSetBinding<NpcAttackValidateHook>(XarthAttackHook::class.java)
        addSetBinding<NpcAttackValidateHook>(EzekielAttackHook::class.java)
        addSetBinding<PlayerRespawnHook>(TfaRespawnHook::class.java)
        addSetBinding<PlayerDeathCleanupHook>(TfaDeathCleanupHook::class.java)
    }
}
